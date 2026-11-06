/********************************************************************************
 * Copyright (C) 2026 EclipseSource and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.theia.cloud.operator.databridge;

import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatLogMessage;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.util.DataBridgeUtil;
import org.eclipse.theia.cloud.common.util.NamingUtil;
import org.eclipse.theia.cloud.operator.handler.session.EagerSessionHandler;
import org.eclipse.theia.cloud.operator.util.TheiaCloudPersistentVolumeUtil;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;

/**
 * Keeps one random token per prewarmed instance in a Secret. The instance's pod reads it as {@value #TOKEN_ENV} when
 * it starts, and the data bridge in that pod accepts only injections that carry it as a bearer token. The operator
 * reads the Secret on every injection instead of caching it, so a restarted operator still knows every token.
 */
@Singleton
public class DataBridgeTokenManager {

    private static final Logger LOGGER = LogManager.getLogger(DataBridgeTokenManager.class);

    public static final String TOKEN_ENV = "DATA_BRIDGE_TOKEN";
    static final String TOKEN_KEY = "token";
    private static final String NAME_SUFFIX = "bridge-token";

    private static final SecureRandom RANDOM = new SecureRandom();

    @Inject
    private TheiaCloudClient client;

    public static String getSecretName(AppDefinition appDef, int instance) {
        return NamingUtil.createNameWithSuffix(appDef, instance, NAME_SUFFIX);
    }

    /**
     * Writes a fresh token into the instance's Secret, creating it if needed. A pod started afterwards gets the new
     * token, a running pod keeps the one it started with.
     *
     * @return false if the Secret could not be written
     */
    public boolean rotate(AppDefinition appDef, int instance, String correlationId) {
        String name = getSecretName(appDef, instance);
        Secret secret = new SecretBuilder().withNewMetadata().withName(name).withNamespace(client.namespace())
                .addNewOwnerReference().withApiVersion(AppDefinition.API).withKind(AppDefinition.KIND)
                .withName(appDef.getMetadata().getName()).withUid(appDef.getMetadata().getUid()).endOwnerReference()
                .endMetadata().withType("Opaque").withData(Map.of(TOKEN_KEY, newToken())).build();
        try {
            client.kubernetes().secrets().inNamespace(client.namespace()).resource(secret)
                    .createOr(NonDeletingOperation::update);
            LOGGER.info(formatLogMessage(correlationId, "Wrote a new data bridge token to Secret " + name));
            return true;
        } catch (RuntimeException e) {
            LOGGER.error(formatLogMessage(correlationId, "Could not write data bridge token Secret " + name), e);
            return false;
        }
    }

    /** Reads the token the instance's pod started with. Empty if the Secret is missing or unreadable. */
    public Optional<String> read(AppDefinition appDef, int instance, String correlationId) {
        String name = getSecretName(appDef, instance);
        try {
            Secret secret = client.kubernetes().secrets().inNamespace(client.namespace()).withName(name).get();
            if (secret == null || secret.getData() == null || secret.getData().get(TOKEN_KEY) == null) {
                LOGGER.warn(formatLogMessage(correlationId, "Data bridge token Secret " + name + " is missing"));
                return Optional.empty();
            }
            return Optional.of(new String(Base64.getDecoder().decode(secret.getData().get(TOKEN_KEY)),
                    StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            LOGGER.warn(formatLogMessage(correlationId, "Could not read data bridge token Secret " + name), e);
            return Optional.empty();
        }
    }

    /** Reads the token of the instance a prewarmed session claimed. Empty if the session claimed none. */
    public Optional<String> readForSession(Session session, String correlationId) {
        Map<String, String> annotations = session.getMetadata().getAnnotations();
        String instance = annotations == null ? null
                : annotations.get(EagerSessionHandler.SESSION_INSTANCE_ID_ANNOTATION);
        if (instance == null) {
            LOGGER.warn(formatLogMessage(correlationId,
                    "Session " + session.getMetadata().getName() + " has no instance, sending no data bridge token"));
            return Optional.empty();
        }
        try {
            Optional<AppDefinition> appDef = client.appDefinitions().get(session.getSpec().getAppDefinition());
            return appDef.isEmpty() ? Optional.empty()
                    : read(appDef.get(), Integer.parseInt(instance.trim()), correlationId);
        } catch (RuntimeException e) {
            LOGGER.warn(formatLogMessage(correlationId, "Could not look up the data bridge token"), e);
            return Optional.empty();
        }
    }

    public void delete(AppDefinition appDef, int instance, String correlationId) {
        String name = getSecretName(appDef, instance);
        try {
            client.kubernetes().secrets().inNamespace(client.namespace()).withName(name).delete();
        } catch (RuntimeException e) {
            LOGGER.warn(formatLogMessage(correlationId, "Could not delete data bridge token Secret " + name), e);
        }
    }

    /**
     * Gives a new prewarmed deployment a fresh token and points its Theia container at it. When the Secret cannot be
     * written the deployment gets no reference, because a reference to a missing Secret keeps the pod from starting;
     * a data bridge that requires the token then rejects every injection.
     */
    public void prepareDeployment(Deployment deployment, AppDefinition appDef, int instance, String correlationId) {
        if (!DataBridgeUtil.isDataBridgeEnabled(appDef.getSpec()) || !rotate(appDef, instance, correlationId)) {
            return;
        }
        Container container = TheiaCloudPersistentVolumeUtil
                .getTheiaContainer(deployment.getSpec().getTemplate().getSpec(), appDef.getSpec());
        if (container.getEnv() == null) {
            container.setEnv(new ArrayList<>());
        }
        container.getEnv().removeIf(env -> TOKEN_ENV.equals(env.getName()));
        container.getEnv().add(new EnvVarBuilder().withName(TOKEN_ENV).withNewValueFrom().withNewSecretKeyRef()
                .withName(getSecretName(appDef, instance)).withKey(TOKEN_KEY).endSecretKeyRef().endValueFrom()
                .build());
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }
}
