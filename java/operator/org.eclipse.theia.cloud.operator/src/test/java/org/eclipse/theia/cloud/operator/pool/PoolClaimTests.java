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
package org.eclipse.theia.cloud.operator.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
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
import org.eclipse.theia.cloud.operator.TheiaCloudOperatorArguments;
import org.eclipse.theia.cloud.operator.databridge.DataBridgeTokenManager;
import org.eclipse.theia.cloud.operator.handler.session.EagerSessionHandler;
import org.eclipse.theia.cloud.operator.sidecar.SidecarManager;
import org.eclipse.theia.cloud.operator.util.K8sResourceFactory;
import org.eclipse.theia.cloud.operator.util.TheiaCloudDeploymentUtil;
import org.eclipse.theia.cloud.operator.util.TheiaCloudServiceUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.fabric8.kubernetes.client.dsl.ServiceResource;

/**
 * An instance of the pool is free when nobody claims it. The claim is a label on its services that is set together
 * with the owner reference of the session and removed only when the instance was reset.
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
class PoolClaimTests {

    private static final String NAMESPACE = "theiacloud";
    private static final String APP_DEFINITION = "java-17-latest";
    private static final String SESSION_UID = "8c2a4a6e-6f0e-4d1b-8f1d-2b9d1f3a7c01";

    private AppDefinition appDefinition;
    private TheiaCloudClient client;
    private NonNamespaceOperation<Service, ServiceList, ServiceResource<Service>> namespacedServices;
    private Map<String, ServiceResource<Service>> serviceResources = new java.util.HashMap<>();
    private NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> namespacedDeployments;
    private MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments;
    private RollableScalableResource<Deployment> deploymentResource = mock(RollableScalableResource.class);
    private PrewarmedResourcePool pool;
    private SidecarManager sidecarManager;
    private K8sResourceFactory resourceFactory;
    private Service external;
    private Service internal;

    @BeforeEach
    void setUp() throws Exception {
        AppDefinitionSpec spec = new AppDefinitionSpec();
        setField(spec, "name", APP_DEFINITION);
        setField(spec, "minInstances", 2);
        appDefinition = new AppDefinition();
        appDefinition.setSpec(spec);
        appDefinition.setMetadata(new ObjectMetaBuilder().withName(APP_DEFINITION)
                .withUid("d3d0bb51-1c8f-4ba8-9c6b-0d5f0c8b7ad1").withGeneration(1L).build());

        client = mock(TheiaCloudClient.class);
        when(client.kubernetes()).thenReturn(client);
        when(client.namespace()).thenReturn(NAMESPACE);
        MixedOperation<Service, ServiceList, ServiceResource<Service>> services = mock(MixedOperation.class);
        namespacedServices = mock(NonNamespaceOperation.class);
        when(client.services()).thenReturn(services);
        when(services.inNamespace(any())).thenReturn(namespacedServices);
        when(namespacedServices.withName(anyString())).thenAnswer(
                invocation -> serviceResources.computeIfAbsent(invocation.getArgument(0), n -> mock(ServiceResource.class)));

        AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
        deployments = mock(MixedOperation.class);
        namespacedDeployments = mock(NonNamespaceOperation.class);
        when(client.apps()).thenReturn(apps);
        when(apps.deployments()).thenReturn(deployments);
        when(deployments.inNamespace(any())).thenReturn(namespacedDeployments);
        when(deployments.withName(anyString())).thenReturn(deploymentResource);
        when(namespacedDeployments.withName(anyString())).thenReturn(deploymentResource);
        when(deploymentResource.get()).thenReturn(
                new DeploymentBuilder().withNewStatus().withReadyReplicas(1).endStatus().build());
        when(namespacedDeployments.list()).thenReturn(new DeploymentList());

        MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> configMaps = mock(MixedOperation.class);
        NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> namespacedConfigMaps = mock(
                NonNamespaceOperation.class);
        when(client.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace(any())).thenReturn(namespacedConfigMaps);
        when(namespacedConfigMaps.list()).thenReturn(new ConfigMapList());

        SessionResourceClient sessions = mock(SessionResourceClient.class);
        when(client.sessions()).thenReturn(sessions);
        when(sessions.list()).thenReturn(List.of());

        external = service(TheiaCloudServiceUtil.getServiceName(appDefinition, 1), null);
        internal = service(TheiaCloudServiceUtil.getInternalServiceName(appDefinition, 1), null);
        listServices(external, internal);

        pool = Mockito.spy(new PrewarmedResourcePool());
        setField(pool, "client", client);
        setField(pool, "arguments", mock(TheiaCloudOperatorArguments.class));
        setField(pool, "dataBridgeTokens", mock(DataBridgeTokenManager.class));
        sidecarManager = mock(SidecarManager.class);
        setField(pool, "sidecarManager", sidecarManager);
        resourceFactory = mock(K8sResourceFactory.class);
        setField(pool, "resourceFactory", resourceFactory);
    }

    private Service service(String name, String claimedBy) {
        ObjectMetaBuilder meta = new ObjectMetaBuilder().withName(name).withNamespace(NAMESPACE)
                .withOwnerReferences(new OwnerReferenceBuilder().withName(APP_DEFINITION)
                        .withUid(appDefinition.getMetadata().getUid()).build());
        if (claimedBy != null) {
            meta.addToLabels(PrewarmedResourcePool.CLAIM_LABEL, claimedBy);
        }
        return new ServiceBuilder().withMetadata(meta.build()).build();
    }

    private void listServices(Service... services) {
        ServiceList list = new ServiceList();
        list.setItems(new ArrayList<>(List.of(services)));
        when(namespacedServices.list()).thenReturn(list);
        FilterWatchListDeletable<Service, ServiceList, ServiceResource<Service>> selected = mock(
                FilterWatchListDeletable.class);
        when(namespacedServices.withLabelSelector(anyString())).thenReturn(selected);
        when(selected.list()).thenReturn(list);
    }

    private Session session(String uid, String instanceId) {
        Session session = new Session();
        session.setSpec(new SessionSpec("session", APP_DEFINITION, "user@example.org"));
        session.setMetadata(new ObjectMetaBuilder().withName("session").withUid(uid)
                .withAnnotations(instanceId == null ? Map.of()
                        : Map.of(EagerSessionHandler.SESSION_INSTANCE_ID_ANNOTATION, instanceId))
                .build());
        return session;
    }

    private Service applyLastEdit(String serviceName, Service service) {
        ArgumentCaptor<UnaryOperator> edit = ArgumentCaptor.forClass(UnaryOperator.class);
        verify(serviceResources.get(serviceName), Mockito.atLeastOnce()).edit(edit.capture());
        List<UnaryOperator> edits = edit.getAllValues();
        return (Service) edits.get(edits.size() - 1).apply(service);
    }

    // ========== claim label ==========

    @Test
    void reserveInstance_setsTheClaimLabelTogetherWithTheOwner() {
        Session session = session(SESSION_UID, null);

        var result = pool.reserveInstance(session, appDefinition, "cid");

        assertEquals(PrewarmedResourcePool.ReservationOutcome.SUCCESS, result.getOutcome());
        Service claimed = applyLastEdit(external.getMetadata().getName(), external);
        assertEquals(SESSION_UID, claimed.getMetadata().getLabels().get(PrewarmedResourcePool.CLAIM_LABEL));
        assertEquals(2, claimed.getMetadata().getOwnerReferences().size());
    }

    @Test
    void reserveInstance_skipsAnInstanceThatIsClaimedEvenWhenItsOwnerIsGone() {
        // the garbage collector already removed the owner reference of the deleted session
        listServices(service(external.getMetadata().getName(), "old-session"),
                service(internal.getMetadata().getName(), "old-session"));

        var result = pool.reserveInstance(session(SESSION_UID, null), appDefinition, "cid");

        assertEquals(PrewarmedResourcePool.ReservationOutcome.NO_CAPACITY, result.getOutcome());
        verify(namespacedServices, never()).withName(external.getMetadata().getName());
    }

    @Test
    void reserveInstance_skipsAnInstanceWithoutDeployment() {
        when(deploymentResource.get()).thenReturn(null);

        var result = pool.reserveInstance(session(SESSION_UID, null), appDefinition, "cid");

        assertEquals(PrewarmedResourcePool.ReservationOutcome.NO_CAPACITY, result.getOutcome());
    }

    private void podsOfDeployment(String waitingReason) {
        MixedOperation<Pod, PodList, PodResource> pods = mock(MixedOperation.class);
        NonNamespaceOperation<Pod, PodList, PodResource> namespacedPods = mock(NonNamespaceOperation.class);
        FilterWatchListDeletable<Pod, PodList, PodResource> selected = mock(FilterWatchListDeletable.class);
        when(client.pods()).thenReturn(pods);
        when(pods.inNamespace(any())).thenReturn(namespacedPods);
        when(namespacedPods.withLabelSelector(anyString())).thenReturn(selected);
        Pod pod = new PodBuilder().withNewStatus().addNewContainerStatus().withNewState().withNewWaiting()
                .withReason(waitingReason).endWaiting().endState().endContainerStatus().endStatus().build();
        when(selected.list()).thenReturn(new PodListBuilder().withItems(pod).build());
    }

    private Deployment notReadyDeployment() {
        return new DeploymentBuilder().withNewSpec().withNewSelector().addToMatchLabels("app", "x").endSelector()
                .endSpec().withNewStatus().withReadyReplicas(0).endStatus().build();
    }

    @Test
    void reserveInstance_skipsAnInstanceWhoseImageCannotBePulled() {
        when(deploymentResource.get()).thenReturn(notReadyDeployment());
        podsOfDeployment("ImagePullBackOff");

        var result = pool.reserveInstance(session(SESSION_UID, null), appDefinition, "cid");

        assertEquals(PrewarmedResourcePool.ReservationOutcome.NO_CAPACITY, result.getOutcome());
        verify(namespacedServices, never()).withName(external.getMetadata().getName());
    }

    @Test
    void reserveInstance_usesAnInstanceThatIsStillStarting() {
        when(deploymentResource.get()).thenReturn(notReadyDeployment());
        podsOfDeployment("ContainerCreating");

        var result = pool.reserveInstance(session(SESSION_UID, null), appDefinition, "cid");

        assertEquals(PrewarmedResourcePool.ReservationOutcome.SUCCESS, result.getOutcome());
    }

    @Test
    void reserveInstance_prefersAReadyInstanceOverAStartingOne() {
        Service external2 = service(TheiaCloudServiceUtil.getServiceName(appDefinition, 2), null);
        Service internal2 = service(TheiaCloudServiceUtil.getInternalServiceName(appDefinition, 2), null);
        listServices(external, internal, external2, internal2);
        RollableScalableResource<Deployment> startingDeployment = mock(RollableScalableResource.class);
        when(startingDeployment.get()).thenReturn(notReadyDeployment());
        when(namespacedDeployments.withName(TheiaCloudDeploymentUtil.getDeploymentName(appDefinition, 1)))
                .thenReturn(startingDeployment);
        podsOfDeployment("ContainerCreating");

        var result = pool.reserveInstance(session(SESSION_UID, null), appDefinition, "cid");

        assertEquals(2, result.getInstance().get().getInstanceId());
    }

    @Test
    void reserveInstance_ignoresTheServiceOfASidecar() {
        Service sidecar = service("instance-1-lsp-" + APP_DEFINITION, null);
        sidecar.getMetadata().getLabels().put("theia-cloud.io/sidecar", "lsp");
        listServices(sidecar, external, internal);

        var result = pool.reserveInstance(session(SESSION_UID, null), appDefinition, "cid");

        assertEquals(PrewarmedResourcePool.ReservationOutcome.SUCCESS, result.getOutcome());
        assertEquals(external.getMetadata().getName(), result.getInstance().get().getExternalService().getMetadata().getName());
        assertFalse(serviceResources.containsKey(sidecar.getMetadata().getName()));
    }

    @Test
    void releaseInstance_removesTheClaimLabelLast() throws Exception {
        listServices(service(external.getMetadata().getName(), SESSION_UID),
                service(internal.getMetadata().getName(), SESSION_UID));
        Mockito.doNothing().when(pool).resetPod(any(), anyInt(), anyString(), anyString());
        Mockito.doReturn(false).when(pool).needsRecreation(any(), anyInt());

        assertTrue(pool.releaseInstance(session(SESSION_UID, "1"), appDefinition, "cid"));

        InOrder order = Mockito.inOrder(pool, deploymentResource, serviceResources.get(external.getMetadata().getName()));
        order.verify(pool).resetPod(any(), Mockito.eq(1), anyString(), anyString());
        order.verify(deploymentResource).edit(any(UnaryOperator.class));
        order.verify(serviceResources.get(external.getMetadata().getName())).edit(any(UnaryOperator.class));
        Service freed = applyLastEdit(external.getMetadata().getName(),
                service(external.getMetadata().getName(), SESSION_UID));
        assertFalse(freed.getMetadata().getLabels().containsKey(PrewarmedResourcePool.CLAIM_LABEL));
    }

    @Test
    void releaseInstance_usesTheInstanceOfTheSessionAnnotation() {
        Service otherExternal = service(TheiaCloudServiceUtil.getServiceName(appDefinition, 2), SESSION_UID);
        Service otherInternal = service(TheiaCloudServiceUtil.getInternalServiceName(appDefinition, 2), SESSION_UID);
        listServices(external, internal, otherExternal, otherInternal);
        // the session is still there while it is being deleted, so the sweep after the release leaves it alone
        when(client.sessions().list()).thenReturn(List.of(session(SESSION_UID, "2")));
        Mockito.doNothing().when(pool).resetPod(any(), anyInt(), anyString(), anyString());
        Mockito.doReturn(false).when(pool).needsRecreation(any(), anyInt());

        assertTrue(pool.releaseInstance(session(SESSION_UID, "2"), appDefinition, "cid"));

        verify(pool).resetPod(any(), Mockito.eq(2), anyString(), anyString());
    }

    @Test
    void releaseInstance_leavesAnInstanceAloneThatAnotherSessionClaimed() {
        listServices(service(external.getMetadata().getName(), "someone-else"),
                service(internal.getMetadata().getName(), "someone-else"));

        assertTrue(pool.releaseInstance(session(SESSION_UID, "1"), appDefinition, "cid"));

        verify(pool, never()).resetPod(any(), anyInt(), anyString(), anyString());
        assertTrue(serviceResources.isEmpty());
    }

    @Test
    void releaseInstance_outdatedInstanceIsDiscardedUnderTheLockInsteadOfReset() {
        listServices(service(external.getMetadata().getName(), SESSION_UID),
                service(internal.getMetadata().getName(), SESSION_UID));
        Mockito.doReturn(true).when(pool).needsRecreation(any(), anyInt());
        when(client.sessions().list()).thenReturn(List.of(session(SESSION_UID, "1")));

        assertTrue(pool.releaseInstance(session(SESSION_UID, "1"), appDefinition, "cid"));

        verify(pool, never()).resetPod(any(), anyInt(), anyString(), anyString());
        verify(namespacedDeployments).withName(TheiaCloudDeploymentUtil.getDeploymentName(appDefinition, 1));
        verify(deploymentResource).delete();
        verify(serviceResources.get(external.getMetadata().getName())).delete();
        verify(serviceResources.get(internal.getMetadata().getName())).delete();
    }

    // ========== orphaned claims ==========

    @Test
    void resetOrphanedClaims_resetsAnInstanceWhoseSessionIsGone() {
        listServices(service(external.getMetadata().getName(), "deleted-session"),
                service(internal.getMetadata().getName(), "deleted-session"));
        Mockito.doNothing().when(pool).resetPod(any(), anyInt(), anyString(), anyString());
        Mockito.doReturn(false).when(pool).needsRecreation(any(), anyInt());

        pool.resetOrphanedClaims(appDefinition, "cid");

        verify(pool).resetPod(any(), Mockito.eq(1), anyString(), anyString());
        Service freed = applyLastEdit(external.getMetadata().getName(),
                service(external.getMetadata().getName(), "deleted-session"));
        assertFalse(freed.getMetadata().getLabels().containsKey(PrewarmedResourcePool.CLAIM_LABEL));
    }

    @Test
    void resetOrphanedClaims_leavesTheSidecarOfAnInstanceAlone() {
        Service sidecar = service("instance-1-lsp-" + APP_DEFINITION, "deleted-session");
        sidecar.getMetadata().getLabels().put("theia-cloud.io/sidecar", "lsp");
        listServices(sidecar, external, internal);

        pool.resetOrphanedClaims(appDefinition, "cid");

        verify(pool, never()).resetPod(any(), anyInt(), anyString(), anyString());
        assertTrue(serviceResources.isEmpty());
    }

    @Test
    void resetOrphanedClaims_leavesTheClaimOfASessionWhoseSetupIsRunning() {
        // the session was deleted while its added handler runs, so it is no longer listed
        pool.reserveInstance(session(SESSION_UID, null), appDefinition, "cid");
        listServices(service(external.getMetadata().getName(), SESSION_UID),
                service(internal.getMetadata().getName(), SESSION_UID));

        assertTrue(pool.resetOrphanedClaims(appDefinition, "cid").isEmpty());
        verify(pool, never()).resetPod(any(), anyInt(), anyString(), anyString());

        pool.sessionSetupFinished(session(SESSION_UID, null));
        Mockito.doNothing().when(pool).resetPod(any(), anyInt(), anyString(), anyString());
        Mockito.doReturn(false).when(pool).needsRecreation(any(), anyInt());
        assertEquals(List.of(1), pool.resetOrphanedClaims(appDefinition, "cid"));
    }

    @Test
    void resetOrphanedClaims_withConfirmationResetsOnlyOnTheSecondRun() {
        listServices(service(external.getMetadata().getName(), "deleted-session"),
                service(internal.getMetadata().getName(), "deleted-session"));
        Mockito.doNothing().when(pool).resetPod(any(), anyInt(), anyString(), anyString());
        Mockito.doReturn(false).when(pool).needsRecreation(any(), anyInt());

        assertTrue(pool.resetOrphanedClaims(appDefinition, "cid", true).isEmpty());
        verify(pool, never()).resetPod(any(), anyInt(), anyString(), anyString());
        assertEquals(List.of(1), pool.resetOrphanedClaims(appDefinition, "cid", true));
    }

    @Test
    void resetOrphanedClaims_withConfirmationForgetsAClaimThatWentAway() {
        listServices(service(external.getMetadata().getName(), "deleted-session"),
                service(internal.getMetadata().getName(), "deleted-session"));
        pool.resetOrphanedClaims(appDefinition, "cid", true);
        listServices(external, internal);
        pool.resetOrphanedClaims(appDefinition, "cid", true);
        listServices(service(external.getMetadata().getName(), "deleted-session"),
                service(internal.getMetadata().getName(), "deleted-session"));

        assertTrue(pool.resetOrphanedClaims(appDefinition, "cid", true).isEmpty());
    }

    @Test
    void holdsInstance_isFalseOnceTheClaimIsGone() {
        var instance = new PrewarmedResourcePool.PoolInstance(1, external, internal, "d");
        listServices(service(external.getMetadata().getName(), SESSION_UID),
                service(internal.getMetadata().getName(), SESSION_UID));
        assertTrue(pool.holdsInstance(session(SESSION_UID, "1"), appDefinition, instance));

        listServices(external, internal);
        assertFalse(pool.holdsInstance(session(SESSION_UID, "1"), appDefinition, instance));

        listServices(service(external.getMetadata().getName(), "someone-else"),
                service(internal.getMetadata().getName(), "someone-else"));
        assertFalse(pool.holdsInstance(session(SESSION_UID, "1"), appDefinition, instance));
    }

    @Test
    void releaseInstance_restartsTheSidecarPodsOnlyOfAnInstanceItReleases() {
        listServices(service(external.getMetadata().getName(), "someone-else"),
                service(internal.getMetadata().getName(), "someone-else"));
        pool.releaseInstance(session(SESSION_UID, "1"), appDefinition, "cid");
        verify(sidecarManager, never()).restartPrewarmedSidecarPods(any(), anyInt(), anyString());

        listServices(service(external.getMetadata().getName(), SESSION_UID),
                service(internal.getMetadata().getName(), SESSION_UID));
        Mockito.doNothing().when(pool).resetPod(any(), anyInt(), anyString(), anyString());
        Mockito.doReturn(false).when(pool).needsRecreation(any(), anyInt());
        pool.releaseInstance(session(SESSION_UID, "1"), appDefinition, "cid");
        verify(sidecarManager).restartPrewarmedSidecarPods(any(), Mockito.eq(1), anyString());
    }

    private Deployment ideDeployment(int id, boolean current) {
        ObjectMetaBuilder meta = new ObjectMetaBuilder()
                .withName(TheiaCloudDeploymentUtil.getDeploymentName(appDefinition, id))
                .withOwnerReferences(new OwnerReferenceBuilder().withName(APP_DEFINITION)
                        .withUid(appDefinition.getMetadata().getUid()).build());
        if (current) {
            meta.addToLabels(PrewarmedResourcePool.APPDEFINITION_GENERATION_LABEL, "1");
        }
        return new DeploymentBuilder().withMetadata(meta.build()).withNewSpec().withNewTemplate().withNewMetadata()
                .addToLabels(org.eclipse.theia.cloud.common.util.LabelsUtil.LABEL_KEY_SESSION,
                        org.eclipse.theia.cloud.common.util.LabelsUtil.LABEL_VALUE_SESSION)
                .addToLabels(org.eclipse.theia.cloud.common.util.LabelsUtil.LABEL_KEY_THEIACLOUD,
                        org.eclipse.theia.cloud.common.util.LabelsUtil.LABEL_VALUE_THEIACLOUD)
                .endMetadata().withNewSpec().endSpec().endTemplate().endSpec().build();
    }

    private void reconcileWithDeployments(Deployment... existing) {
        when(namespacedDeployments.list()).thenReturn(new DeploymentList(null, List.of(existing), null, null));
        when(namespacedDeployments.resource(any())).thenReturn(deploymentResource);
        when(deploymentResource.delete()).thenReturn(List.of());
        pool.reconcile(appDefinition, 1, "cid");
    }

    @Test
    void reconcile_removesTheSidecarsOfAnInstanceThatIsScaledAway() {
        reconcileWithDeployments(ideDeployment(1, true), ideDeployment(2, true));

        verify(sidecarManager).deletePrewarmedSidecars(any(), Mockito.eq(2), anyString());
        verify(sidecarManager, never()).deletePrewarmedSidecars(any(), Mockito.eq(1), anyString());
    }

    @Test
    void reconcile_replacesTheSidecarsOfAnOutdatedInstance() {
        reconcileWithDeployments(ideDeployment(1, false));

        InOrder order = Mockito.inOrder(sidecarManager, resourceFactory);
        order.verify(sidecarManager).deletePrewarmedSidecars(any(), Mockito.eq(1), anyString());
        order.verify(sidecarManager).createPrewarmedSidecars(any(), Mockito.eq(1), any(), any(), anyString());
        order.verify(resourceFactory).createDeploymentForEagerInstance(any(), Mockito.eq(1), any(), any(), anyString());
    }

    @Test
    void resetOrphanedClaims_keepsAnInstanceOfALiveSession() {
        listServices(service(external.getMetadata().getName(), SESSION_UID),
                service(internal.getMetadata().getName(), SESSION_UID));
        when(client.sessions().list()).thenReturn(List.of(session(SESSION_UID, "1")));

        pool.resetOrphanedClaims(appDefinition, "cid");

        verify(pool, never()).resetPod(any(), anyInt(), anyString(), anyString());
    }

    @Test
    void resetOrphanedClaims_marksLiveClaimsThatPredateTheLabel() {
        when(client.sessions().list()).thenReturn(List.of(session(SESSION_UID, "1")));

        pool.resetOrphanedClaims(appDefinition, "cid");

        Service marked = applyLastEdit(external.getMetadata().getName(), external);
        assertEquals(SESSION_UID, marked.getMetadata().getLabels().get(PrewarmedResourcePool.CLAIM_LABEL));
    }

    @Test
    void reconcileInstance_neverTouchesAnInstanceThatIsClaimed() {
        listServices(service(external.getMetadata().getName(), SESSION_UID),
                service(internal.getMetadata().getName(), SESSION_UID));

        pool.reconcileInstance(appDefinition, 1, "cid");

        verify(pool, never()).createInstanceResources(any(), anyInt(), anyString());
        assertTrue(serviceResources.isEmpty());
    }

    @Test
    void reconcile_doesNotReplaceTheServicesOfAClaimedInstance() {
        // outdated (no generation label) and without session owner, as after the garbage collector ran
        Service claimedExternal = service(external.getMetadata().getName(), SESSION_UID);
        Service claimedInternal = service(internal.getMetadata().getName(), SESSION_UID);
        listServices(claimedExternal, claimedInternal);
        when(client.sessions().list()).thenReturn(List.of(session(SESSION_UID, "1")));
        ServiceResource<Service> resource = mock(ServiceResource.class);
        when(namespacedServices.resource(any(Service.class))).thenReturn(resource);

        pool.reconcile(appDefinition, 2, "cid");

        ArgumentCaptor<Service> touched = ArgumentCaptor.forClass(Service.class);
        verify(namespacedServices, Mockito.atLeast(0)).resource(touched.capture());
        assertTrue(touched.getAllValues().stream().noneMatch(
                service -> PrewarmedResourcePool.isClaimed(service)), "a claimed service was touched");
        verify(resource, never()).delete();
    }

    // ========== sessions of an older operator ==========

    @Test
    void isClaimed_treatsTheSessionLabelsOfAnOlderOperatorAsAClaim() {
        Service legacy = service("a", null);
        legacy.getMetadata().setLabels(Map.of("theia-cloud.io/session-uuid", SESSION_UID));

        assertTrue(PrewarmedResourcePool.isClaimed(legacy));
        assertEquals(SESSION_UID, PrewarmedResourcePool.claimUid(legacy));
    }

    private Service legacyClaim(String name, String sessionUid) {
        Service legacy = service(name, null);
        legacy.getMetadata().setLabels(new java.util.HashMap<>(Map.of("theia-cloud.io/session-uuid", sessionUid)));
        legacy.getMetadata().setOwnerReferences(List.of(new OwnerReferenceBuilder().withName(APP_DEFINITION)
                .withUid(appDefinition.getMetadata().getUid()).build()));
        return legacy;
    }

    @Test
    void resetOrphanedClaims_labelsLiveLegacyClaimsBeforeResettingOrphans() {
        Service liveExternal = legacyClaim(external.getMetadata().getName(), SESSION_UID);
        Service liveInternal = legacyClaim(internal.getMetadata().getName(), SESSION_UID);
        Service goneExternal = legacyClaim(TheiaCloudServiceUtil.getServiceName(appDefinition, 2), "deleted-session");
        Service goneInternal = legacyClaim(TheiaCloudServiceUtil.getInternalServiceName(appDefinition, 2),
                "deleted-session");
        listServices(liveExternal, liveInternal, goneExternal, goneInternal);
        when(client.sessions().list()).thenReturn(List.of(session(SESSION_UID, "1")));
        Mockito.doNothing().when(pool).resetPod(any(), anyInt(), anyString(), anyString());
        Mockito.doReturn(false).when(pool).needsRecreation(any(), anyInt());

        pool.resetOrphanedClaims(appDefinition, "cid");

        verify(pool, never()).resetPod(any(), Mockito.eq(1), anyString(), anyString());
        verify(pool).resetPod(any(), Mockito.eq(2), anyString(), anyString());
        InOrder order = Mockito.inOrder(serviceResources.get(liveExternal.getMetadata().getName()), pool);
        order.verify(serviceResources.get(liveExternal.getMetadata().getName())).edit(any(UnaryOperator.class));
        order.verify(pool).resetPod(any(), Mockito.eq(2), anyString(), anyString());
        Service labelled = applyLastEdit(liveExternal.getMetadata().getName(), liveExternal);
        assertEquals(SESSION_UID, labelled.getMetadata().getLabels().get(PrewarmedResourcePool.CLAIM_LABEL));
    }

    @Test
    void releaseInstance_isANoOpOnAnInstanceThatIsFree() {
        // a second delivery of the deletion: no claim label, no session owner
        Mockito.doNothing().when(pool).resetPod(any(), anyInt(), anyString(), anyString());

        assertTrue(pool.releaseInstance(session(SESSION_UID, "1"), appDefinition, "cid"));

        verify(pool, never()).resetPod(any(), anyInt(), anyString(), anyString());
        verify(pool, never()).needsRecreation(any(), anyInt());
        assertTrue(serviceResources.isEmpty());
    }

    // ========== creation order ==========

    @Test
    void createInstanceResources_createsTheServicesLast() throws Exception {
        K8sResourceFactory factory = mock(K8sResourceFactory.class);
        setField(pool, "resourceFactory", factory);

        pool.createInstanceResources(appDefinition, 1, "cid");

        InOrder order = Mockito.inOrder(factory);
        order.verify(factory).createDeploymentForEagerInstance(any(), anyInt(), any(), any(), anyString());
        order.verify(factory).createServiceForEagerInstance(any(), anyInt(), any(), anyString());
        order.verify(factory).createInternalServiceForEagerInstance(any(), anyInt(), any(), anyString());
    }

    // ========== deleting pods ==========

    @Test
    void deletePod_deletesEveryPodOfTheDeployment() {
        Deployment deployment = new DeploymentBuilder().withNewSpec().withNewSelector().addToMatchLabels("app", "x")
                .endSelector().endSpec().build();
        when(deploymentResource.get()).thenReturn(deployment);
        MixedOperation<Pod, PodList, PodResource> pods = mock(MixedOperation.class);
        NonNamespaceOperation<Pod, PodList, PodResource> namespacedPods = mock(NonNamespaceOperation.class);
        FilterWatchListDeletable<Pod, PodList, PodResource> selected = mock(FilterWatchListDeletable.class);
        Pod first = new PodBuilder().withNewMetadata().withName("x-1").endMetadata().build();
        Pod second = new PodBuilder().withNewMetadata().withName("x-2").endMetadata().build();
        when(client.pods()).thenReturn(pods);
        when(pods.inNamespace(any())).thenReturn(namespacedPods);
        when(namespacedPods.withLabels(Map.of("app", "x"))).thenReturn(selected);
        when(selected.list()).thenReturn(new PodListBuilder().withItems(first, second).build());
        PodResource firstResource = mock(PodResource.class);
        PodResource secondResource = mock(PodResource.class);
        when(pods.withName("x-1")).thenReturn(firstResource);
        when(pods.withName("x-2")).thenReturn(secondResource);

        pool.deletePod("deployment", "cid");

        verify(firstResource).delete();
        verify(secondResource).delete();
    }

    @Test
    void isClaimed_readsTheLabel() {
        assertTrue(PrewarmedResourcePool.isClaimed(service("a", "uid")));
        assertFalse(PrewarmedResourcePool.isClaimed(service("a", null)));
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
