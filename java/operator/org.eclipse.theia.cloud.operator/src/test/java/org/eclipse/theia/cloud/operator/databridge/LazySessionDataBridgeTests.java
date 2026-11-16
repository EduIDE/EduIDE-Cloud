/********************************************************************************
 * Copyright (C) 2026 TUM and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License v. 2.0 are satisfied: GNU General Public License, version 2
 * with the GNU Classpath Exception which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 ********************************************************************************/
package org.eclipse.theia.cloud.operator.databridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinitionSpec;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionSpec;
import org.eclipse.theia.cloud.common.util.DataBridgeUtil;
import org.eclipse.theia.cloud.operator.TheiaCloudOperatorArguments;
import org.eclipse.theia.cloud.operator.handler.session.LazySessionHandler;
import org.eclipse.theia.cloud.operator.replacements.DefaultDeploymentTemplateReplacements;
import org.eclipse.theia.cloud.operator.util.SessionEnvCollector;
import org.eclipse.theia.cloud.operator.util.TheiaCloudHandlerUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.sentry.ISpan;

/** A lazy session of an app with the data bridge gets a token of its own and its environment through the bridge. */
@SuppressWarnings({ "unchecked", "rawtypes" })
class LazySessionDataBridgeTests {

    private AppDefinition bridgeApp;
    private AppDefinition plainApp;
    private Session session;
    private TheiaCloudClient client;
    private Resource<Secret> secretResource;
    private DataBridgeTokenManager tokens;

    @BeforeEach
    void setUp() throws Exception {
        bridgeApp = appDefinition(true);
        plainApp = appDefinition(false);
        session = new Session();
        session.setSpec(new SessionSpec("lazy-session", "java-25", "user@example.org"));
        session.setMetadata(new ObjectMetaBuilder().withName("lazy-session")
                .withUid("8c2a4a6e-6f0e-4d1b-8f1d-2b9d1f3a7c01").build());

        client = mock(TheiaCloudClient.class);
        NamespacedKubernetesClient kubernetes = mock(NamespacedKubernetesClient.class);
        MixedOperation<Secret, ?, Resource<Secret>> secrets = mock(MixedOperation.class);
        NonNamespaceOperation<Secret, ?, Resource<Secret>> namespaced = mock(NonNamespaceOperation.class);
        secretResource = mock(Resource.class);
        when(client.namespace()).thenReturn("theiacloud");
        when(client.kubernetes()).thenReturn(kubernetes);
        when(kubernetes.secrets()).thenReturn((MixedOperation) secrets);
        when(secrets.inNamespace("theiacloud")).thenReturn((NonNamespaceOperation) namespaced);
        when(namespaced.resource(any(Secret.class))).thenReturn(secretResource);
        tokens = new DataBridgeTokenManager();
        setField(tokens, "client", client);
    }

    private AppDefinition appDefinition(boolean bridge) throws Exception {
        AppDefinitionSpec spec = new AppDefinitionSpec();
        setField(spec, "name", "java-25");
        setField(spec, "port", 3000);
        setField(spec, "image", "registry/java-25:1");
        if (bridge) {
            setField(spec, "options", Map.of(DataBridgeUtil.DATA_BRIDGE_ENABLED_OPTION, "true"));
        }
        AppDefinition appDefinition = new AppDefinition();
        appDefinition.setSpec(spec);
        appDefinition.setMetadata(new ObjectMetaBuilder().withName("java-25")
                .withUid("d3d0bb51-1c8f-4ba8-9c6b-0d5f0c8b7ad1").build());
        return appDefinition;
    }

    private Deployment deployment() {
        return new DeploymentBuilder().withNewSpec().withNewTemplate().withNewSpec().addNewContainer()
                .withName("java-25").withImage("registry/java-25:1").endContainer().endSpec().endTemplate().endSpec().build();
    }

    @Test
    void prepareDeployment_createsATokenSecretOwnedByTheSessionAndReferencesIt() {
        Deployment deployment = deployment();

        tokens.prepareDeployment(deployment, bridgeApp, session, "cid");

        ArgumentCaptor<Secret> created = ArgumentCaptor.forClass(Secret.class);
        verify(client.kubernetes().secrets().inNamespace("theiacloud")).resource(created.capture());
        Secret secret = created.getValue();
        assertEquals(DataBridgeTokenManager.getSecretName(session), secret.getMetadata().getName());
        assertEquals(1, secret.getMetadata().getOwnerReferences().size());
        assertEquals("Session", secret.getMetadata().getOwnerReferences().get(0).getKind());
        assertEquals(session.getMetadata().getUid(), secret.getMetadata().getOwnerReferences().get(0).getUid());
        String token = new String(Base64.getDecoder().decode(secret.getData().get("token")), StandardCharsets.UTF_8);
        assertTrue(token.length() >= 43, "256 bit token");

        var env = deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().get(0);
        assertEquals(DataBridgeTokenManager.TOKEN_ENV, env.getName());
        assertEquals(secret.getMetadata().getName(), env.getValueFrom().getSecretKeyRef().getName());
        assertEquals("token", env.getValueFrom().getSecretKeyRef().getKey());
    }

    @Test
    void prepareDeployment_appWithoutBridgeIsUnchanged() {
        Deployment deployment = deployment();

        tokens.prepareDeployment(deployment, plainApp, session, "cid");

        verify(client.kubernetes().secrets(), never()).inNamespace(anyString());
        assertTrue(deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv() == null
                || deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().isEmpty());
    }

    @Test
    void readForSession_aLazySessionReadsItsOwnSecret() {
        Secret stored = new io.fabric8.kubernetes.api.model.SecretBuilder().withNewMetadata()
                .withName(DataBridgeTokenManager.getSecretName(session)).endMetadata()
                .withData(Map.of("token", Base64.getEncoder().encodeToString("t0ken".getBytes(StandardCharsets.UTF_8))))
                .build();
        var namespaced = client.kubernetes().secrets().inNamespace("theiacloud");
        Resource<Secret> byName = mock(Resource.class);
        when(namespaced.withName(DataBridgeTokenManager.getSecretName(session))).thenReturn(byName);
        when(byName.get()).thenReturn(stored);

        assertEquals("t0ken", tokens.readForSession(session, "cid").orElseThrow());
    }

    @Test
    void replacements_lazySessionOfABridgeAppEnablesTheBridge() throws Exception {
        Map<String, String> replacements = replacements(bridgeApp);

        assertEquals("1", replacements.get(TheiaCloudHandlerUtil.PLACEHOLDER_DATA_BRIDGE_ENABLED));
        assertEquals("data-bridge", replacements.get(TheiaCloudHandlerUtil.PLACEHOLDER_DATA_BRIDGE_STRATEGY));
        assertFalse(replacements.get(TheiaCloudHandlerUtil.PLACEHOLDER_DATA_BRIDGE_ENV_PORT).isEmpty());
    }

    @Test
    void replacements_lazySessionOfAnAppWithoutBridgeIsUnchanged() throws Exception {
        Map<String, String> replacements = replacements(plainApp);

        assertEquals("0", replacements.get(TheiaCloudHandlerUtil.PLACEHOLDER_DATA_BRIDGE_ENABLED));
        assertEquals("", replacements.get(TheiaCloudHandlerUtil.PLACEHOLDER_DATA_BRIDGE_STRATEGY));
    }

    private Map<String, String> replacements(AppDefinition appDefinition) throws Exception {
        DefaultDeploymentTemplateReplacements replacements = new DefaultDeploymentTemplateReplacements();
        setField(replacements, "arguments", new TheiaCloudOperatorArguments());
        org.eclipse.theia.cloud.operator.ingress.IngressPathProvider paths = mock(
                org.eclipse.theia.cloud.operator.ingress.IngressPathProvider.class);
        when(paths.getPath(any(AppDefinition.class), any(Session.class))).thenReturn("/lazy");
        setField(replacements, "ingressPathProvider", paths);
        return replacements.getReplacements("theiacloud", appDefinition, session);
    }

    @Test
    void lazyHandler_schedulesAnInjectionOnlyForABridgeApp() throws Exception {
        LazySessionHandler handler = new LazySessionHandler();
        SessionEnvCollector collector = mock(SessionEnvCollector.class);
        AsyncDataInjector injector = mock(AsyncDataInjector.class);
        when(collector.collect(session, "cid")).thenReturn(Map.of("ARTEMIS_URL", "https://artemis.example"));
        setField(handler, "sessionEnvCollector", collector);
        setField(handler, "asyncDataInjector", injector);
        ISpan span = mock(ISpan.class);

        handler.scheduleDataBridgeInjection(span, session, plainApp, "cid");
        verify(injector, never()).scheduleInjection(any(), any(), any(), anyString());

        handler.scheduleDataBridgeInjection(span, session, bridgeApp, "cid");
        verify(injector).scheduleInjection(span, session, Map.of("ARTEMIS_URL", "https://artemis.example"), "cid");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
