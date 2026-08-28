/********************************************************************************
 * Copyright (C) 2026 EclipseSource and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.theia.cloud.operator.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.eclipse.theia.cloud.common.k8s.client.SessionResourceClient;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinitionSpec;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionSpec;
import org.eclipse.theia.cloud.operator.handler.AddedHandlerUtil;
import org.eclipse.theia.cloud.operator.handler.session.EagerSessionHandler;
import org.eclipse.theia.cloud.operator.util.TheiaCloudConfigMapUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;

class PrewarmedResourcePoolTests {

    private static final String NAMESPACE = "theiacloud";
    private static final String APP_DEFINITION = "java-17-latest";
    private static final String USER = "matthias.linhuber@tum.de";

    // ========== computeClaimedInstanceEmails ==========

    @Test
    void computeClaimedInstanceEmails_sessionOnInstance() {
        AppDefinition appDefinition = createAppDefinition();
        Session session = createSession(APP_DEFINITION, USER, "1");

        Map<Integer, String> claims = PrewarmedResourcePool.computeClaimedInstanceEmails(appDefinition,
                List.of(session));

        assertEquals(Map.of(1, USER), claims);
    }

    @Test
    void computeClaimedInstanceEmails_ignoresSessionsOfOtherAppDefinitions() {
        AppDefinition appDefinition = createAppDefinition();
        Session session = createSession("python-latest", USER, "1");

        Map<Integer, String> claims = PrewarmedResourcePool.computeClaimedInstanceEmails(appDefinition,
                List.of(session));

        assertTrue(claims.isEmpty());
    }

    @Test
    void computeClaimedInstanceEmails_ignoresSessionsWithoutInstanceAnnotation() {
        AppDefinition appDefinition = createAppDefinition();
        Session session = createSession(APP_DEFINITION, USER, null);

        Map<Integer, String> claims = PrewarmedResourcePool.computeClaimedInstanceEmails(appDefinition,
                List.of(session));

        assertTrue(claims.isEmpty());
    }

    @Test
    void computeClaimedInstanceEmails_ignoresUnparseableInstanceAnnotation() {
        AppDefinition appDefinition = createAppDefinition();
        Session session = createSession(APP_DEFINITION, USER, "not-a-number");

        Map<Integer, String> claims = PrewarmedResourcePool.computeClaimedInstanceEmails(appDefinition,
                List.of(session));

        assertTrue(claims.isEmpty());
    }

    @Test
    void computeClaimedInstanceEmails_ignoresSessionsWithoutUser() {
        AppDefinition appDefinition = createAppDefinition();
        Session session = createSession(APP_DEFINITION, "  ", "1");

        Map<Integer, String> claims = PrewarmedResourcePool.computeClaimedInstanceEmails(appDefinition,
                List.of(session));

        assertTrue(claims.isEmpty());
    }

    @Test
    void computeClaimedInstanceEmails_multipleSessionsOnDifferentInstances() {
        AppDefinition appDefinition = createAppDefinition();
        Session first = createSession(APP_DEFINITION, USER, "1");
        Session second = createSession(APP_DEFINITION, "other@tum.de", "3");

        Map<Integer, String> claims = PrewarmedResourcePool.computeClaimedInstanceEmails(appDefinition,
                List.of(first, second));

        assertEquals(Map.of(1, USER, 3, "other@tum.de"), claims);
    }

    // ========== restoreEmailConfigsOfClaimedInstances ==========

    @Test
    void restoreEmailConfigsOfClaimedInstances_rewritesEmptyEmailConfig() throws Exception {
        AppDefinition appDefinition = createAppDefinition();
        Session session = createSession(APP_DEFINITION, USER, "1");
        ConfigMap emailConfigMap = createEmailConfigMap(appDefinition, null);

        PoolFixture fixture = createFixture(appDefinition, List.of(session), emailConfigMap);

        assertTrue(fixture.pool.restoreEmailConfigsOfClaimedInstances(appDefinition, "correlationId"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UnaryOperator<ConfigMap>> edit = ArgumentCaptor.forClass(UnaryOperator.class);
        verify(fixture.emailConfigMapResource).edit(edit.capture());
        ConfigMap edited = edit.getValue().apply(emailConfigMap);
        assertEquals(USER, edited.getData().get(AddedHandlerUtil.FILENAME_AUTHENTICATED_EMAILS_LIST));
    }

    @Test
    void restoreEmailConfigsOfClaimedInstances_leavesUpToDateEmailConfigAlone() throws Exception {
        AppDefinition appDefinition = createAppDefinition();
        Session session = createSession(APP_DEFINITION, USER, "1");
        ConfigMap emailConfigMap = createEmailConfigMap(appDefinition, USER);

        PoolFixture fixture = createFixture(appDefinition, List.of(session), emailConfigMap);

        assertTrue(fixture.pool.restoreEmailConfigsOfClaimedInstances(appDefinition, "correlationId"));

        verify(fixture.emailConfigMapResource, never()).edit(any(UnaryOperator.class));
    }

    @Test
    void restoreEmailConfigsOfClaimedInstances_noClaimedInstances() throws Exception {
        AppDefinition appDefinition = createAppDefinition();
        Session session = createSession(APP_DEFINITION, USER, null);
        ConfigMap emailConfigMap = createEmailConfigMap(appDefinition, null);

        PoolFixture fixture = createFixture(appDefinition, List.of(session), emailConfigMap);

        assertTrue(fixture.pool.restoreEmailConfigsOfClaimedInstances(appDefinition, "correlationId"));

        verify(fixture.emailConfigMapResource, never()).edit(any(UnaryOperator.class));
    }

    @Test
    void restoreEmailConfigsOfClaimedInstances_missingEmailConfig() throws Exception {
        AppDefinition appDefinition = createAppDefinition();
        Session session = createSession(APP_DEFINITION, USER, "1");

        PoolFixture fixture = createFixture(appDefinition, List.of(session), null);

        assertTrue(fixture.pool.restoreEmailConfigsOfClaimedInstances(appDefinition, "correlationId"));

        verify(fixture.emailConfigMapResource, never()).edit(any(UnaryOperator.class));
    }

    // ========== Helpers ==========

    private static final class PoolFixture {
        private PrewarmedResourcePool pool;
        private Resource<ConfigMap> emailConfigMapResource;
    }

    @SuppressWarnings("unchecked")
    private PoolFixture createFixture(AppDefinition appDefinition, List<Session> sessions, ConfigMap emailConfigMap)
            throws Exception {
        TheiaCloudClient client = Mockito.mock(TheiaCloudClient.class);
        SessionResourceClient sessionClient = Mockito.mock(SessionResourceClient.class);
        NamespacedKubernetesClient kubernetes = Mockito.mock(NamespacedKubernetesClient.class);
        MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> configMaps = Mockito.mock(MixedOperation.class);
        NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> namespacedConfigMaps = Mockito
                .mock(NonNamespaceOperation.class);
        Resource<ConfigMap> emailConfigMapResource = Mockito.mock(Resource.class);

        when(client.namespace()).thenReturn(NAMESPACE);
        when(client.sessions()).thenReturn(sessionClient);
        when(sessionClient.list()).thenReturn(sessions);
        when(client.kubernetes()).thenReturn(kubernetes);
        when(kubernetes.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace(NAMESPACE)).thenReturn(namespacedConfigMaps);
        when(namespacedConfigMaps.withName(TheiaCloudConfigMapUtil.getEmailConfigName(appDefinition, 1)))
                .thenReturn(emailConfigMapResource);
        when(emailConfigMapResource.get()).thenReturn(emailConfigMap);

        PoolFixture fixture = new PoolFixture();
        fixture.pool = new PrewarmedResourcePool();
        fixture.emailConfigMapResource = emailConfigMapResource;
        setField(fixture.pool, "client", client);
        return fixture;
    }

    private ConfigMap createEmailConfigMap(AppDefinition appDefinition, String authenticatedEmails) {
        return new ConfigMapBuilder()
                .withNewMetadata()
                .withName(TheiaCloudConfigMapUtil.getEmailConfigName(appDefinition, 1))
                .withNamespace(NAMESPACE)
                .endMetadata()
                .withData(Map.of(AddedHandlerUtil.FILENAME_AUTHENTICATED_EMAILS_LIST,
                        authenticatedEmails == null ? "" : authenticatedEmails))
                .build();
    }

    private AppDefinition createAppDefinition() {
        AppDefinitionSpec spec = new AppDefinitionSpec();
        setFieldUnchecked(spec, "name", APP_DEFINITION);

        AppDefinition appDefinition = new AppDefinition();
        appDefinition.setSpec(spec);
        appDefinition.setMetadata(new ObjectMetaBuilder().withName(APP_DEFINITION)
                .withUid("d3d0bb51-1c8f-4ba8-9c6b-0d5f0c8b7ad1").build());
        return appDefinition;
    }

    private Session createSession(String appDefinition, String user, String instanceId) {
        Session session = new Session();
        session.setSpec(new SessionSpec("ws-" + user + "-session", appDefinition, user));
        ObjectMetaBuilder metadata = new ObjectMetaBuilder().withName("ws-" + user + "-session")
                .withUid("8c2a4a6e-6f0e-4d1b-8f1d-2b9d1f3a7c" + Math.abs(user.hashCode() % 100));
        if (instanceId != null) {
            metadata.withAnnotations(Map.of(EagerSessionHandler.SESSION_INSTANCE_ID_ANNOTATION, instanceId));
        }
        session.setMetadata(metadata.build());
        return session;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private void setFieldUnchecked(Object target, String fieldName, Object value) {
        try {
            setField(target, fieldName, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
