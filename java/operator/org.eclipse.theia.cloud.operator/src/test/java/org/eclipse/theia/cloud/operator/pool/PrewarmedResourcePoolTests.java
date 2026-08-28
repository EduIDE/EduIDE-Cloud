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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.eclipse.theia.cloud.operator.util.TheiaCloudDeploymentUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;

class PrewarmedResourcePoolTests {

    private static final String NAMESPACE = "theiacloud";
    private static final String APP_DEFINITION = "java-17-latest";
    private static final String USER = "matthias.linhuber@tum.de";

    // ========== computeClaimedInstances ==========

    @Test
    void computeClaimedInstances_sessionOnInstance() {
        AppDefinition appDefinition = createAppDefinition();
        Session session = createSession(APP_DEFINITION, USER, "1");

        Map<Integer, Session> claims = PrewarmedResourcePool.computeClaimedInstances(appDefinition,
                List.of(session));

        assertEquals(Map.of(1, session), claims);
    }

    @Test
    void computeClaimedInstances_ignoresSessionsOfOtherAppDefinitions() {
        AppDefinition appDefinition = createAppDefinition();
        Session session = createSession("python-latest", USER, "1");

        Map<Integer, Session> claims = PrewarmedResourcePool.computeClaimedInstances(appDefinition,
                List.of(session));

        assertTrue(claims.isEmpty());
    }

    @Test
    void computeClaimedInstances_ignoresSessionsWithoutInstanceAnnotation() {
        AppDefinition appDefinition = createAppDefinition();
        Session session = createSession(APP_DEFINITION, USER, null);

        Map<Integer, Session> claims = PrewarmedResourcePool.computeClaimedInstances(appDefinition,
                List.of(session));

        assertTrue(claims.isEmpty());
    }

    @Test
    void computeClaimedInstances_ignoresUnparseableInstanceAnnotation() {
        AppDefinition appDefinition = createAppDefinition();
        Session session = createSession(APP_DEFINITION, USER, "not-a-number");

        Map<Integer, Session> claims = PrewarmedResourcePool.computeClaimedInstances(appDefinition,
                List.of(session));

        assertTrue(claims.isEmpty());
    }

    @Test
    void computeClaimedInstances_ignoresSessionsWithoutUser() {
        AppDefinition appDefinition = createAppDefinition();
        Session session = createSession(APP_DEFINITION, "  ", "1");

        Map<Integer, Session> claims = PrewarmedResourcePool.computeClaimedInstances(appDefinition,
                List.of(session));

        assertTrue(claims.isEmpty());
    }

    @Test
    void computeClaimedInstances_ignoresSessionsBeingDeleted() {
        AppDefinition appDefinition = createAppDefinition();
        Session session = createSession(APP_DEFINITION, USER, "1");
        session.getMetadata().setDeletionTimestamp("2026-08-28T00:11:36Z");

        Map<Integer, Session> claims = PrewarmedResourcePool.computeClaimedInstances(appDefinition,
                List.of(session));

        assertTrue(claims.isEmpty());
    }

    @Test
    void computeClaimedInstances_multipleSessionsOnDifferentInstances() {
        AppDefinition appDefinition = createAppDefinition();
        Session first = createSession(APP_DEFINITION, USER, "1");
        Session second = createSession(APP_DEFINITION, "other@tum.de", "3");

        Map<Integer, Session> claims = PrewarmedResourcePool.computeClaimedInstances(appDefinition,
                List.of(first, second));

        assertEquals(Map.of(1, first, 3, second), claims);
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
    void restoreEmailConfigsOfClaimedInstances_refreshesPodsOfRestoredInstance() throws Exception {
        AppDefinition appDefinition = createAppDefinition();
        Session session = createSession(APP_DEFINITION, USER, "1");
        ConfigMap emailConfigMap = createEmailConfigMap(appDefinition, null);

        PoolFixture fixture = createFixture(appDefinition, List.of(session), emailConfigMap);

        assertTrue(fixture.pool.restoreEmailConfigsOfClaimedInstances(appDefinition, "correlationId"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UnaryOperator<Pod>> refresh = ArgumentCaptor.forClass(UnaryOperator.class);
        verify(fixture.podResource).edit(refresh.capture());
        Pod refreshed = refresh.getValue().apply(new Pod());
        assertNotNull(refreshed.getMetadata().getAnnotations()
                .get(PrewarmedResourcePool.EAGER_START_REFRESH_ANNOTATION));
    }

    @Test
    void restoreEmailConfigsOfClaimedInstances_leavesUpToDateEmailConfigAlone() throws Exception {
        AppDefinition appDefinition = createAppDefinition();
        Session session = createSession(APP_DEFINITION, USER, "1");
        ConfigMap emailConfigMap = createEmailConfigMap(appDefinition, USER);

        PoolFixture fixture = createFixture(appDefinition, List.of(session), emailConfigMap);

        assertTrue(fixture.pool.restoreEmailConfigsOfClaimedInstances(appDefinition, "correlationId"));

        verify(fixture.emailConfigMapResource, never()).edit(any(UnaryOperator.class));
        verify(fixture.podResource, never()).edit(any(UnaryOperator.class));
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
    void restoreEmailConfigsOfClaimedInstances_sessionDeletedWhileRestoring() throws Exception {
        AppDefinition appDefinition = createAppDefinition();
        Session session = createSession(APP_DEFINITION, USER, "1");
        ConfigMap emailConfigMap = createEmailConfigMap(appDefinition, null);

        PoolFixture fixture = createFixture(appDefinition, List.of(session), emailConfigMap);
        // The session is released and gone by the time the write would happen.
        when(fixture.sessionClient.get(session.getMetadata().getName())).thenReturn(Optional.empty());

        assertTrue(fixture.pool.restoreEmailConfigsOfClaimedInstances(appDefinition, "correlationId"));

        verify(fixture.emailConfigMapResource, never()).edit(any(UnaryOperator.class));
        verify(fixture.podResource, never()).edit(any(UnaryOperator.class));
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
        private PodResource podResource;
        private SessionResourceClient sessionClient;
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
        AppsAPIGroupDSL apps = Mockito.mock(AppsAPIGroupDSL.class);
        MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments = Mockito
                .mock(MixedOperation.class);
        NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> namespacedDeployments = Mockito
                .mock(NonNamespaceOperation.class);
        RollableScalableResource<Deployment> deploymentResource = Mockito.mock(RollableScalableResource.class);

        when(client.namespace()).thenReturn(NAMESPACE);
        when(client.sessions()).thenReturn(sessionClient);
        when(sessionClient.list()).thenReturn(sessions);
        // The restore re-reads a claiming session right before it writes its email back.
        for (Session session : sessions) {
            when(sessionClient.get(session.getMetadata().getName())).thenReturn(Optional.of(session));
        }
        when(client.kubernetes()).thenReturn(kubernetes);
        when(kubernetes.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace(NAMESPACE)).thenReturn(namespacedConfigMaps);
        when(namespacedConfigMaps.withName(TheiaCloudConfigMapUtil.getEmailConfigName(appDefinition, 1)))
                .thenReturn(emailConfigMapResource);
        when(emailConfigMapResource.get()).thenReturn(emailConfigMap);
        String deploymentName = TheiaCloudDeploymentUtil.getDeploymentName(appDefinition, 1);
        when(kubernetes.apps()).thenReturn(apps);
        when(apps.deployments()).thenReturn(deployments);
        when(deployments.inNamespace(NAMESPACE)).thenReturn(namespacedDeployments);
        when(namespacedDeployments.withName(deploymentName)).thenReturn(deploymentResource);
        when(deploymentResource.get()).thenReturn(createDeployment(deploymentName));

        // The pod refresh looks pods up through kubernetes() and edits them through the client itself.
        MixedOperation<Pod, PodList, PodResource> listedPods = Mockito.mock(MixedOperation.class);
        NonNamespaceOperation<Pod, PodList, PodResource> namespacedListedPods = Mockito
                .mock(NonNamespaceOperation.class);
        FilterWatchListDeletable<Pod, PodList, PodResource> selectedPods = Mockito
                .mock(FilterWatchListDeletable.class);
        MixedOperation<Pod, PodList, PodResource> editedPods = Mockito.mock(MixedOperation.class);
        NonNamespaceOperation<Pod, PodList, PodResource> namespacedEditedPods = Mockito
                .mock(NonNamespaceOperation.class);
        PodResource podResource = Mockito.mock(PodResource.class);
        Pod pod = createPod(deploymentName);

        when(kubernetes.pods()).thenReturn(listedPods);
        when(listedPods.inNamespace(NAMESPACE)).thenReturn(namespacedListedPods);
        when(namespacedListedPods.withLabelSelector("app=" + deploymentName)).thenReturn(selectedPods);
        when(selectedPods.list()).thenReturn(new PodListBuilder().withItems(pod).build());
        when(client.pods()).thenReturn(editedPods);
        when(editedPods.inNamespace(NAMESPACE)).thenReturn(namespacedEditedPods);
        when(namespacedEditedPods.withName(pod.getMetadata().getName())).thenReturn(podResource);

        PoolFixture fixture = new PoolFixture();
        fixture.pool = new PrewarmedResourcePool();
        fixture.emailConfigMapResource = emailConfigMapResource;
        fixture.podResource = podResource;
        fixture.sessionClient = sessionClient;
        setField(fixture.pool, "client", client);
        return fixture;
    }

    private Deployment createDeployment(String deploymentName) {
        return new DeploymentBuilder()
                .withNewMetadata().withName(deploymentName).withNamespace(NAMESPACE).endMetadata()
                .withNewSpec()
                .withNewSelector().withMatchLabels(Map.of("app", deploymentName)).endSelector()
                .endSpec()
                .build();
    }

    private Pod createPod(String deploymentName) {
        return new PodBuilder()
                .withNewMetadata()
                .withName(deploymentName + "-abc123")
                .withNamespace(NAMESPACE)
                .withOwnerReferences(new OwnerReferenceBuilder().withKind("ReplicaSet")
                        .withName(deploymentName + "-5f7c").build())
                .endMetadata()
                .build();
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
