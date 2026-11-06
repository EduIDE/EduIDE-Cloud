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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.theia.cloud.common.k8s.client.AppDefinitionResourceClient;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinitionSpec;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionSpec;
import org.eclipse.theia.cloud.common.util.DataBridgeUtil;
import org.eclipse.theia.cloud.operator.handler.session.EagerSessionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;

class DataBridgeTokenManagerTests {

    private static final String NAMESPACE = "theiacloud";
    private static final String APP_DEFINITION = "java-17-latest";
    private static final String IMAGE = "ghcr.io/eduide/java-17";

    private final DataBridgeTokenManager tokens = new DataBridgeTokenManager();
    private final AppDefinitionResourceClient appDefinitions = Mockito.mock(AppDefinitionResourceClient.class);
    private final Resource<Secret> written = mockResource();
    private final Resource<Secret> named = mockResource();
    private NonNamespaceOperation<Secret, SecretList, Resource<Secret>> secrets;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void wire() throws Exception {
        TheiaCloudClient client = Mockito.mock(TheiaCloudClient.class);
        NamespacedKubernetesClient kubernetes = Mockito.mock(NamespacedKubernetesClient.class);
        MixedOperation<Secret, SecretList, Resource<Secret>> allSecrets = Mockito.mock(MixedOperation.class);
        secrets = Mockito.mock(NonNamespaceOperation.class);
        when(client.namespace()).thenReturn(NAMESPACE);
        when(client.kubernetes()).thenReturn(kubernetes);
        when(client.appDefinitions()).thenReturn(appDefinitions);
        when(kubernetes.secrets()).thenReturn(allSecrets);
        when(allSecrets.inNamespace(NAMESPACE)).thenReturn(secrets);
        when(secrets.resource(any(Secret.class))).thenReturn(written);
        when(secrets.withName(any())).thenReturn(named);

        Field field = DataBridgeTokenManager.class.getDeclaredField("client");
        field.setAccessible(true);
        field.set(tokens, client);
    }

    @Test
    void rotate_writesAFreshTokenOwnedByTheAppDefinition() {
        AppDefinition appDef = appDefinition(true);

        assertTrue(tokens.rotate(appDef, 2, "cid"));
        assertTrue(tokens.rotate(appDef, 2, "cid"));

        ArgumentCaptor<Secret> captor = ArgumentCaptor.forClass(Secret.class);
        verify(secrets, times(2)).resource(captor.capture());
        verify(written, times(2)).createOr(any());
        Secret first = captor.getAllValues().get(0);
        assertEquals(DataBridgeTokenManager.getSecretName(appDef, 2), first.getMetadata().getName());
        assertTrue(first.getMetadata().getName().endsWith("bridge-token"));
        assertEquals(appDef.getMetadata().getUid(), first.getMetadata().getOwnerReferences().get(0).getUid());
        assertEquals(AppDefinition.KIND, first.getMetadata().getOwnerReferences().get(0).getKind());
        String token = decode(first);
        assertTrue(token.length() >= 40);
        assertNotEquals(token, decode(captor.getAllValues().get(1)));
    }

    @Test
    void rotate_reportsAFailedWrite() {
        when(written.createOr(any())).thenThrow(new KubernetesClientException("forbidden"));

        assertFalse(tokens.rotate(appDefinition(true), 1, "cid"));
    }

    @Test
    void read_returnsTheStoredToken() {
        when(named.get()).thenReturn(secretWithToken("s3cr3t"));

        assertEquals(Optional.of("s3cr3t"), tokens.read(appDefinition(true), 1, "cid"));
    }

    @Test
    void read_missingSecret_isEmpty() {
        assertEquals(Optional.empty(), tokens.read(appDefinition(true), 1, "cid"));
    }

    @Test
    void readForSession_readsTheTokenOfTheClaimedInstance() {
        AppDefinition appDef = appDefinition(true);
        when(appDefinitions.get(APP_DEFINITION)).thenReturn(Optional.of(appDef));
        when(secrets.withName(DataBridgeTokenManager.getSecretName(appDef, 3))).thenReturn(named);
        when(named.get()).thenReturn(secretWithToken("s3cr3t"));

        assertEquals(Optional.of("s3cr3t"), tokens.readForSession(session("3"), "cid"));
    }

    @Test
    void readForSession_withoutInstance_isEmpty() {
        assertEquals(Optional.empty(), tokens.readForSession(session(null), "cid"));
        verify(secrets, never()).withName(any());
    }

    @Test
    void prepareDeployment_writesTheSecretAndReferencesIt() {
        AppDefinition appDef = appDefinition(true);
        Deployment deployment = deployment();

        tokens.prepareDeployment(deployment, appDef, 1, "cid");

        verify(written).createOr(any());
        List<EnvVar> env = theiaEnv(deployment);
        assertEquals(1, env.size());
        assertEquals(DataBridgeTokenManager.TOKEN_ENV, env.get(0).getName());
        assertEquals(DataBridgeTokenManager.getSecretName(appDef, 1),
                env.get(0).getValueFrom().getSecretKeyRef().getName());
        assertEquals(DataBridgeTokenManager.TOKEN_KEY, env.get(0).getValueFrom().getSecretKeyRef().getKey());
    }

    @Test
    void prepareDeployment_failedWrite_leavesNoReference() {
        when(written.createOr(any())).thenThrow(new KubernetesClientException("forbidden"));
        Deployment deployment = deployment();

        tokens.prepareDeployment(deployment, appDefinition(true), 1, "cid");

        assertTrue(theiaEnv(deployment).isEmpty());
    }

    @Test
    void prepareDeployment_bridgeDisabled_doesNothing() {
        Deployment deployment = deployment();

        tokens.prepareDeployment(deployment, appDefinition(false), 1, "cid");

        verify(secrets, never()).resource(any(Secret.class));
        assertTrue(theiaEnv(deployment).isEmpty());
    }

    private static String decode(Secret secret) {
        return new String(Base64.getDecoder().decode(secret.getData().get(DataBridgeTokenManager.TOKEN_KEY)),
                StandardCharsets.UTF_8);
    }

    private static Secret secretWithToken(String token) {
        return new SecretBuilder().withData(Map.of(DataBridgeTokenManager.TOKEN_KEY,
                Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)))).build();
    }

    private static Deployment deployment() {
        return new DeploymentBuilder().withNewSpec().withNewTemplate().withNewSpec().addNewContainer()
                .withName("theia").withImage(IMAGE + ":1.0").endContainer().endSpec().endTemplate().endSpec()
                .build();
    }

    private static List<EnvVar> theiaEnv(Deployment deployment) {
        List<EnvVar> env = deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv();
        return env == null ? List.of() : env;
    }

    private static Session session(String instance) {
        Session session = new Session();
        session.setSpec(new SessionSpec("session-a", APP_DEFINITION, "user"));
        ObjectMetaBuilder metadata = new ObjectMetaBuilder().withName("session-a");
        if (instance != null) {
            metadata.withAnnotations(Map.of(EagerSessionHandler.SESSION_INSTANCE_ID_ANNOTATION, instance));
        }
        session.setMetadata(metadata.build());
        return session;
    }

    private static AppDefinition appDefinition(boolean bridgeEnabled) {
        AppDefinitionSpec spec = new AppDefinitionSpec();
        setField(spec, "name", APP_DEFINITION);
        setField(spec, "image", IMAGE);
        setField(spec, "options", Map.of(DataBridgeUtil.DATA_BRIDGE_ENABLED_OPTION, String.valueOf(bridgeEnabled)));
        AppDefinition appDef = new AppDefinition();
        appDef.setSpec(spec);
        appDef.setMetadata(new ObjectMetaBuilder().withName(APP_DEFINITION)
                .withUid("d3d0bb51-1c8f-4ba8-9c6b-0d5f0c8b7ad1").build());
        return appDef;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Resource<Secret> mockResource() {
        return Mockito.mock(Resource.class);
    }
}
