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
package org.eclipse.theia.cloud.operator.sidecar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinitionSpec;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionSpec;
import org.eclipse.theia.cloud.common.util.LabelsUtil;
import org.eclipse.theia.cloud.operator.TheiaCloudOperatorArguments;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyList;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.V1NetworkAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NetworkAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import picocli.CommandLine;

/**
 * Sidecar pods run next to untrusted user code and may mount the workspace, so they get the same isolation labels and
 * container restrictions as the IDE pod, no service account token and a policy that connects them only to their own IDE
 * pod.
 */
class SidecarHardeningTests {

    private static final SidecarConfig CONFIG = new SidecarConfig("lsp", "img:latest", 5000, List.of(), "500m", "1Gi",
            "100m", "256Mi", true);

    private NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments;
    private NonNamespaceOperation<NetworkPolicy, NetworkPolicyList, Resource<NetworkPolicy>> policies;
    private SidecarResourceFactory factory;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        NamespacedKubernetesClient kubernetes = mock(NamespacedKubernetesClient.class);
        AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
        MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> allDeployments = mock(
                MixedOperation.class);
        deployments = mock(NonNamespaceOperation.class);
        when(kubernetes.apps()).thenReturn(apps);
        when(apps.deployments()).thenReturn(allDeployments);
        when(allDeployments.inNamespace("theiacloud")).thenReturn(deployments);
        NetworkAPIGroupDSL network = mock(NetworkAPIGroupDSL.class);
        V1NetworkAPIGroupDSL v1 = mock(V1NetworkAPIGroupDSL.class);
        MixedOperation<NetworkPolicy, NetworkPolicyList, Resource<NetworkPolicy>> allPolicies = mock(
                MixedOperation.class);
        policies = mock(NonNamespaceOperation.class);
        when(kubernetes.network()).thenReturn(network);
        when(network.v1()).thenReturn(v1);
        when(v1.networkPolicies()).thenReturn(allPolicies);
        when(allPolicies.inNamespace("theiacloud")).thenReturn(policies);
        when(policies.resource(org.mockito.ArgumentMatchers.any(NetworkPolicy.class)))
                .thenReturn(mock(Resource.class));
        TheiaCloudClient client = mock(TheiaCloudClient.class);
        when(client.namespace()).thenReturn("theiacloud");
        when(client.kubernetes()).thenReturn(kubernetes);
        TheiaCloudOperatorArguments arguments = new TheiaCloudOperatorArguments();
        new CommandLine(arguments).parseArgs("--instancesHost", "example.org");
        factory = new SidecarResourceFactory();
        setField(factory, "client", client);
        setField(factory, "arguments", arguments);
    }

    private Deployment createLazyDeployment() throws Exception {
        factory.createDeployment(session(), CONFIG, Optional.empty(), appDefinition().getSpec(), "test");
        ArgumentCaptor<Deployment> created = ArgumentCaptor.forClass(Deployment.class);
        verify(deployments).resource(created.capture());
        return created.getValue();
    }

    @Test
    void lazySidecarPodCarriesTheSessionLabelsOfItsSession() throws Exception {
        Map<String, String> labels = createLazyDeployment().getSpec().getTemplate().getMetadata().getLabels();

        assertEquals(LabelsUtil.LABEL_VALUE_SESSION, labels.get(LabelsUtil.LABEL_KEY_SESSION));
        assertEquals(LabelsUtil.LABEL_VALUE_THEIACLOUD, labels.get(LabelsUtil.LABEL_KEY_THEIACLOUD));
        assertEquals("8c2a4a6e-6f0e-4d1b-8f1d-2b9d1f3a7c01", labels.get(LabelsUtil.LABEL_KEY_SESSION_UUID));
        assertEquals("lsp", labels.get("theia-cloud.io/sidecar"));
    }

    @Test
    void sidecarPodHasNoServiceAccountTokenAndTheRestrictionsOfTheIdeContainer() throws Exception {
        var podSpec = createLazyDeployment().getSpec().getTemplate().getSpec();

        assertEquals(false, podSpec.getAutomountServiceAccountToken());
        assertEquals(101L, podSpec.getSecurityContext().getFsGroup());
        var security = podSpec.getContainers().get(0).getSecurityContext();
        assertEquals(101L, security.getRunAsUser());
        assertEquals(101L, security.getRunAsGroup());
        assertEquals(false, security.getAllowPrivilegeEscalation());
        assertEquals(List.of("ALL"), security.getCapabilities().getDrop());
        assertEquals("RuntimeDefault", security.getSeccompProfile().getType());
    }

    @Test
    void prewarmedSidecarPodIsSelectedByTheSessionPolicies() throws Exception {
        factory.createPrewarmedDeployment(appDefinition(), 1, CONFIG, Optional.empty(), Map.of(), "test");
        ArgumentCaptor<Deployment> created = ArgumentCaptor.forClass(Deployment.class);
        verify(deployments).resource(created.capture());

        Map<String, String> labels = created.getValue().getSpec().getTemplate().getMetadata().getLabels();
        assertEquals(LabelsUtil.LABEL_VALUE_SESSION, labels.get(LabelsUtil.LABEL_KEY_SESSION));
        assertEquals(LabelsUtil.LABEL_VALUE_THEIACLOUD, labels.get(LabelsUtil.LABEL_KEY_THEIACLOUD));
        assertEquals(false, created.getValue().getSpec().getTemplate().getSpec().getAutomountServiceAccountToken());
    }

    @Test
    void networkPolicyAllowsTheSidecarPortOnlyWithinTheSelectedPods() {
        LabelSelector own = new LabelSelectorBuilder()
                .addToMatchLabels(LabelsUtil.LABEL_KEY_SESSION_UUID, "8c2a4a6e-6f0e-4d1b-8f1d-2b9d1f3a7c01").build();

        assertTrue(factory.createSidecarNetworkPolicy("np", null, own, List.of(CONFIG), "test"));

        ArgumentCaptor<NetworkPolicy> created = ArgumentCaptor.forClass(NetworkPolicy.class);
        verify(policies).resource(created.capture());
        var spec = created.getValue().getSpec();
        assertEquals(own, spec.getPodSelector());
        assertEquals(List.of("Ingress", "Egress"), spec.getPolicyTypes());
        assertEquals(1, spec.getIngress().size());
        assertEquals(own, spec.getIngress().get(0).getFrom().get(0).getPodSelector());
        assertEquals(1, spec.getIngress().get(0).getFrom().size());
        assertEquals(5000, spec.getIngress().get(0).getPorts().get(0).getPort().getIntVal());
        assertEquals(1, spec.getEgress().size());
        assertEquals(own, spec.getEgress().get(0).getTo().get(0).getPodSelector());
        assertEquals(1, spec.getEgress().get(0).getTo().size());
        assertFalse(spec.getEgress().get(0).getPorts().isEmpty());
    }

    private static AppDefinition appDefinition() throws Exception {
        AppDefinition appDefinition = new AppDefinition();
        AppDefinitionSpec spec = new AppDefinitionSpec();
        setField(spec, "uid", 101);
        setField(spec, "name", "java-17");
        appDefinition.setSpec(spec);
        appDefinition.setMetadata(new ObjectMetaBuilder().withName("java-17")
                .withUid("d3d0bb51-1c8f-4ba8-9c6b-0d5f0c8b7ad1").build());
        return appDefinition;
    }

    private static Session session() {
        Session session = new Session();
        session.setSpec(new SessionSpec("session", "java-17", "user"));
        session.setMetadata(new ObjectMetaBuilder().withName("session")
                .withUid("8c2a4a6e-6f0e-4d1b-8f1d-2b9d1f3a7c01").build());
        return session;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
