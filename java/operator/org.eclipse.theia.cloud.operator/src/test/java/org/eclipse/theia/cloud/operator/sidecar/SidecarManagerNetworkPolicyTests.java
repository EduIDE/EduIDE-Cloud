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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinitionSpec;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.SidecarSpec;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;

class SidecarManagerNetworkPolicyTests {

    private SidecarResourceFactory factory;
    private SidecarManager manager;
    private AppDefinition appDefinition;
    private Session session;

    @BeforeEach
    void setUp() throws Exception {
        factory = mock(SidecarResourceFactory.class);
        manager = new SidecarManager();
        setField(manager, "factory", factory);

        SidecarSpec sidecar = new SidecarSpec();
        setField(sidecar, "name", "lsp");
        setField(sidecar, "image", "img:latest");
        setField(sidecar, "port", 5000);
        AppDefinitionSpec spec = new AppDefinitionSpec();
        setField(spec, "name", "java-17");
        setField(spec, "sidecars", List.of(sidecar));
        appDefinition = new AppDefinition();
        appDefinition.setSpec(spec);
        appDefinition.setMetadata(new ObjectMetaBuilder().withName("java-17")
                .withUid("d3d0bb51-1c8f-4ba8-9c6b-0d5f0c8b7ad1").build());
        session = new Session();
        session.setSpec(new SessionSpec("session", "java-17", "user"));
        session.setMetadata(new ObjectMetaBuilder().withName("session")
                .withUid("8c2a4a6e-6f0e-4d1b-8f1d-2b9d1f3a7c01").build());
    }

    @Test
    void createSidecars_createsThePolicyOfTheSession() {
        when(factory.createService(any(), any(), anyString())).thenReturn(Optional.of(new Service()));
        when(factory.createDeployment(any(), any(), any(), any(), anyString()))
                .thenReturn(Optional.of(new Deployment()));
        when(factory.createSidecarNetworkPolicy(anyString(), any(), any(), any(), anyString())).thenReturn(true);

        assertTrue(manager.createSidecars(session, appDefinition, Optional.empty(), "test"));

        ArgumentCaptor<LabelSelector> selector = ArgumentCaptor.forClass(LabelSelector.class);
        verify(factory).createSidecarNetworkPolicy(
                org.mockito.ArgumentMatchers.eq(SidecarResourceFactory.getLazyNetworkPolicyName(session)), any(),
                selector.capture(), any(), anyString());
        assertEquals(Map.of("theia-cloud.io/session-uuid", "8c2a4a6e-6f0e-4d1b-8f1d-2b9d1f3a7c01"),
                selector.getValue().getMatchLabels());
    }

    @Test
    void createSidecars_failsAndRollsBackWhenThePolicyCannotBeCreated() {
        when(factory.createService(any(), any(), anyString())).thenReturn(Optional.of(new Service()));
        when(factory.createDeployment(any(), any(), any(), any(), anyString()))
                .thenReturn(Optional.of(new Deployment()));
        when(factory.createSidecarNetworkPolicy(anyString(), any(), any(), any(), anyString())).thenReturn(false);

        assertFalse(manager.createSidecars(session, appDefinition, Optional.empty(), "test"));

        verify(factory).deleteResources(session, "test");
    }

    @Test
    void deleteSidecars_deletesThePolicy() {
        manager.deleteSidecars(session, "test");

        verify(factory).deleteNetworkPolicy(SidecarResourceFactory.getLazyNetworkPolicyName(session), "test");
        verify(factory).deleteResources(session, "test");
    }

    @Test
    void createPrewarmedSidecars_selectsTheIdePodAndItsSidecarsByAppLabel() {
        when(factory.createPrewarmedService(any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), anyString()))
                .thenReturn(Optional.of(new Service()));
        when(factory.createPrewarmedDeployment(any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(),
                anyString())).thenReturn(Optional.of(new Deployment()));
        when(factory.createSidecarNetworkPolicy(anyString(), any(), any(), any(), anyString())).thenReturn(true);

        assertTrue(manager.createPrewarmedSidecars(appDefinition, 2, Optional.empty(), Map.of(), "test"));

        ArgumentCaptor<LabelSelector> selector = ArgumentCaptor.forClass(LabelSelector.class);
        verify(factory).createSidecarNetworkPolicy(
                org.mockito.ArgumentMatchers.eq(SidecarResourceFactory.getPrewarmedNetworkPolicyName(appDefinition, 2)),
                any(), selector.capture(), any(), anyString());
        var expression = selector.getValue().getMatchExpressions().get(0);
        assertEquals("app", expression.getKey());
        assertEquals("In", expression.getOperator());
        assertTrue(expression.getValues().contains("java-17-2"), expression.getValues().toString());
        assertEquals(2, expression.getValues().size());
    }

    @Test
    void sidecarDeploymentOfAnOlderOperatorIsOutdated() {
        Deployment old = new DeploymentBuilder().withNewSpec().withNewTemplate().withNewMetadata()
                .addToLabels("app", "x").endMetadata().withNewSpec().endSpec().endTemplate().endSpec().build();
        Deployment current = new DeploymentBuilder().withNewSpec().withNewTemplate().withNewMetadata()
                .addToLabels("app.kubernetes.io/component", "session").endMetadata().withNewSpec()
                .withAutomountServiceAccountToken(false).endSpec().endTemplate().endSpec().build();

        assertTrue(SidecarManager.isOutdatedSidecarDeployment(old));
        assertFalse(SidecarManager.isOutdatedSidecarDeployment(current));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
