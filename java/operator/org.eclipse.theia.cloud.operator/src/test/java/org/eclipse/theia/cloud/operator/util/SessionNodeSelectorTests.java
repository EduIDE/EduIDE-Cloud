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
package org.eclipse.theia.cloud.operator.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinitionSpec;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionSpec;
import org.eclipse.theia.cloud.operator.TheiaCloudOperatorArguments;
import org.eclipse.theia.cloud.operator.bandwidth.BandwidthLimiter;
import org.eclipse.theia.cloud.operator.databridge.DataBridgeTokenManager;
import org.eclipse.theia.cloud.operator.replacements.DeploymentTemplateReplacements;
import org.eclipse.theia.cloud.operator.sidecar.SidecarConfig;
import org.eclipse.theia.cloud.operator.sidecar.SidecarManager;
import org.eclipse.theia.cloud.operator.sidecar.SidecarResourceFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import picocli.CommandLine;

class SessionNodeSelectorTests {

    private static final String POOL = "aet.cit.tum.de/pool";

    // ========== Parsing ==========

    @Test
    void absentOptionMeansNoSelector() {
        assertTrue(arguments().getSessionNodeSelector().isEmpty());
    }

    @Test
    void emptyOptionMeansNoSelector() {
        assertTrue(arguments("--sessionNodeSelector", "").getSessionNodeSelector().isEmpty());
    }

    @Test
    void parsesASinglePair() {
        assertEquals(Map.of(POOL, "pve"), arguments("--sessionNodeSelector", POOL + "=pve").getSessionNodeSelector());
    }

    @Test
    void parsesMultiplePairs() {
        assertEquals(Map.of("a", "b", "c", "d"), arguments("--sessionNodeSelector", "a=b, c=d").getSessionNodeSelector());
    }

    @ParameterizedTest
    @ValueSource(strings = { "pve", "=pve", "a=b,c", "a=b,", " =b" })
    void rejectsMalformedPairsAtStartup(String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> arguments("--sessionNodeSelector", value));
        assertTrue(e.getMessage().contains("--sessionNodeSelector"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = { "pool==pve", "po ol=pve", "pool=p ve", "-pool=pve", "pool=pve-", "Example.com/pool=pve",
            "a/b/c=d", "/pool=pve" })
    void rejectsInvalidLabelsAtStartup(String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> arguments("--sessionNodeSelector", value));
        assertTrue(e.getMessage().contains("--sessionNodeSelector"), e.getMessage());
    }

    @Test
    void rejectsLabelsOverTheLengthLimits() {
        String name64 = "a".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> arguments("--sessionNodeSelector", name64 + "=pve"));
        assertThrows(IllegalArgumentException.class, () -> arguments("--sessionNodeSelector", "pool=" + name64));
        String prefix254 = "a".repeat(254);
        assertThrows(IllegalArgumentException.class,
                () -> arguments("--sessionNodeSelector", prefix254 + "/pool=pve"));
    }

    @Test
    void acceptsEmptyValuesAndPrefixedKeys() {
        assertEquals(Map.of(POOL, "", "kubernetes.io/arch", "amd64", "tier_1", "a.b-c"),
                arguments("--sessionNodeSelector", POOL + "=,kubernetes.io/arch=amd64,tier_1=a.b-c")
                        .getSessionNodeSelector());
    }

    // ========== Session deployments ==========

    @Test
    void lazySessionDeploymentIsPinned() throws Exception {
        Deployment deployment = createLazy(arguments("--sessionNodeSelector", POOL + "=pve"));
        assertEquals(Map.of(POOL, "pve"), deployment.getSpec().getTemplate().getSpec().getNodeSelector());
    }

    @Test
    void eagerInstanceDeploymentIsPinned() throws Exception {
        Deployment deployment = createEager(arguments("--sessionNodeSelector", POOL + "=pve"));
        assertEquals(Map.of(POOL, "pve"), deployment.getSpec().getTemplate().getSpec().getNodeSelector());
    }

    @Test
    void noSelectorWhenTheOptionIsEmpty() throws Exception {
        assertTrue(createLazy(arguments()).getSpec().getTemplate().getSpec().getNodeSelector().isEmpty());
        assertTrue(createEager(arguments()).getSpec().getTemplate().getSpec().getNodeSelector().isEmpty());
    }

    @Test
    void mergesWithASelectorTheTemplateCarries() {
        Deployment deployment = new DeploymentBuilder().withNewSpec().withNewTemplate().withNewSpec()
                .addToNodeSelector("kubernetes.io/arch", "amd64").addToNodeSelector(POOL, "other").endSpec()
                .endTemplate().endSpec().build();

        K8sResourceFactory.addNodeSelector(deployment, Map.of(POOL, "pve"));

        assertEquals(Map.of("kubernetes.io/arch", "amd64", POOL, "pve"),
                deployment.getSpec().getTemplate().getSpec().getNodeSelector());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sidecarDeploymentIsPinned() throws Exception {
        TheiaCloudClient client = mock(TheiaCloudClient.class);
        NamespacedKubernetesClient kubernetes = mock(NamespacedKubernetesClient.class);
        AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
        MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments = mock(
                MixedOperation.class);
        NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> inNamespace = mock(
                NonNamespaceOperation.class);
        when(client.namespace()).thenReturn("theiacloud");
        when(client.kubernetes()).thenReturn(kubernetes);
        when(kubernetes.apps()).thenReturn(apps);
        when(apps.deployments()).thenReturn(deployments);
        when(deployments.inNamespace("theiacloud")).thenReturn(inNamespace);
        SidecarResourceFactory factory = new SidecarResourceFactory();
        setField(factory, "client", client);
        setField(factory, "arguments", arguments("--sessionNodeSelector", POOL + "=pve"));

        factory.createDeployment(session(), new SidecarConfig("lsp", "img:latest", 5000, List.of(), "500m", "1Gi",
                "100m", "256Mi", false), Optional.empty(), appDefinition().getSpec(), "test");

        ArgumentCaptor<Deployment> created = ArgumentCaptor.forClass(Deployment.class);
        verify(inNamespace).resource(created.capture());
        assertEquals(Map.of(POOL, "pve"), created.getValue().getSpec().getTemplate().getSpec().getNodeSelector());
    }

    // ========== Helpers ==========

    private static TheiaCloudOperatorArguments arguments(String... selector) {
        TheiaCloudOperatorArguments arguments = new TheiaCloudOperatorArguments();
        String[] args = new String[selector.length + 2];
        args[0] = "--instancesHost";
        args[1] = "example.org";
        System.arraycopy(selector, 0, args, 2, selector.length);
        new CommandLine(arguments).setTrimQuotes(true).parseArgs(args);
        arguments.validate();
        return arguments;
    }

    private Deployment createLazy(TheiaCloudOperatorArguments arguments) throws Exception {
        return captureCreatedDeployment(arguments,
                factory -> factory.createDeploymentForLazySession(session(), appDefinition(), Optional.empty(),
                        Map.of(), null, "test"));
    }

    private Deployment createEager(TheiaCloudOperatorArguments arguments) throws Exception {
        return captureCreatedDeployment(arguments,
                factory -> factory.createDeploymentForEagerInstance(appDefinition(), 1, Map.of(), "test"));
    }

    /**
     * Applies the post-processing the factory hands to K8sUtil to a stand-in for the parsed template, so the test sees
     * exactly what happens to the pod spec without a cluster.
     */
    @SuppressWarnings("unchecked")
    private Deployment captureCreatedDeployment(TheiaCloudOperatorArguments arguments,
            Consumer<K8sResourceFactory> create) throws Exception {
        K8sResourceFactory factory = new K8sResourceFactory();
        TheiaCloudClient client = mock(TheiaCloudClient.class);
        when(client.namespace()).thenReturn("theiacloud");
        setField(factory, "client", client);
        setField(factory, "arguments", arguments);
        setField(factory, "bandwidthLimiter", mock(BandwidthLimiter.class));
        setField(factory, "deploymentReplacements", mock(DeploymentTemplateReplacements.class));
        setField(factory, "sidecarManager", mock(SidecarManager.class));
        setField(factory, "dataBridgeTokens", mock(DataBridgeTokenManager.class));

        ArgumentCaptor<Consumer<Deployment>> postProcessor = ArgumentCaptor.forClass(Consumer.class);
        try (MockedStatic<K8sUtil> k8sUtil = mockStatic(K8sUtil.class)) {
            create.accept(factory);
            k8sUtil.verify(() -> K8sUtil.loadAndCreateDeploymentWithOwnerReference(any(), any(), any(), any(), any(),
                    any(), any(), any(), anyInt(), any(), postProcessor.capture()));
        }
        Deployment deployment = new DeploymentBuilder().withNewMetadata().withName("session").endMetadata()
                .withNewSpec().withNewTemplate().withNewMetadata().endMetadata().withNewSpec().addNewContainer()
                .withName("theia").endContainer().endSpec().endTemplate().endSpec().build();
        postProcessor.getValue().accept(deployment);
        return deployment;
    }

    private static AppDefinition appDefinition() {
        AppDefinition appDefinition = new AppDefinition();
        appDefinition.setSpec(new AppDefinitionSpec());
        appDefinition.setMetadata(new ObjectMetaBuilder().withName("java-17").withUid("d3d0bb51-1c8f-4ba8-9c6b-0d5f0c8b7ad1").withGeneration(3L)
                .build());
        return appDefinition;
    }

    private static Session session() {
        Session session = new Session();
        session.setSpec(new SessionSpec("session", "java-17", "user"));
        session.setMetadata(new ObjectMetaBuilder().withName("session").withUid("8c2a4a6e-6f0e-4d1b-8f1d-2b9d1f3a7c01").build());
        return session;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
