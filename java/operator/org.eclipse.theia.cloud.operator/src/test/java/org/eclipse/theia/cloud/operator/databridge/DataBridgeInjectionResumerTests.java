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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.theia.cloud.common.k8s.client.AppDefinitionResourceClient;
import org.eclipse.theia.cloud.common.k8s.client.SessionResourceClient;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.OperatorStatus;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinitionSpec;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionSpec;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionStatus;
import org.eclipse.theia.cloud.common.util.DataBridgeUtil;
import org.eclipse.theia.cloud.operator.util.SessionEnvCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;

class DataBridgeInjectionResumerTests {

    private final AsyncDataInjector injector = mock(AsyncDataInjector.class);
    private final SessionEnvCollector collector = mock(SessionEnvCollector.class);
    private final SessionResourceClient sessions = mock(SessionResourceClient.class);
    private final AppDefinitionResourceClient appDefinitions = mock(AppDefinitionResourceClient.class);
    private final DataBridgeInjectionResumer resumer = new DataBridgeInjectionResumer();

    @BeforeEach
    void wire() throws Exception {
        TheiaCloudClient client = mock(TheiaCloudClient.class);
        when(client.sessions()).thenReturn(sessions);
        when(client.appDefinitions()).thenReturn(appDefinitions);
        set("client", client);
        set("sessionEnvCollector", collector);
        set("asyncDataInjector", injector);
        when(collector.collect(any(), anyString())).thenReturn(Map.of("ARTEMIS_URL", "https://artemis.example"));
    }

    private AppDefinition app(String name, boolean bridge) throws Exception {
        AppDefinitionSpec spec = new AppDefinitionSpec();
        field(spec, "name", name);
        if (bridge) {
            field(spec, "options", Map.of(DataBridgeUtil.DATA_BRIDGE_ENABLED_OPTION, "true"));
        }
        AppDefinition appDefinition = new AppDefinition();
        appDefinition.setSpec(spec);
        when(appDefinitions.get(name)).thenReturn(Optional.of(appDefinition));
        return appDefinition;
    }

    private Session session(String name, String appName, String operatorStatus) {
        Session session = new Session();
        session.setSpec(new SessionSpec(name, appName, "user"));
        session.setMetadata(new ObjectMetaBuilder().withName(name).withUid("uid-" + name).build());
        SessionStatus status = new SessionStatus();
        status.setOperatorStatus(operatorStatus);
        session.setStatus(status);
        return session;
    }

    @Test
    void resume_schedulesAnInjectionForEveryHandledSessionOfABridgeApp() throws Exception {
        app("bridge-app", true);
        app("plain-app", false);
        Session handled = session("a", "bridge-app", OperatorStatus.HANDLED);
        Session starting = session("b", "bridge-app", OperatorStatus.HANDLING);
        Session plain = session("c", "plain-app", OperatorStatus.HANDLED);
        when(sessions.list()).thenReturn(List.of(handled, starting, plain));

        resumer.resume("cid");

        verify(injector).scheduleInjection(null, handled, Map.of("ARTEMIS_URL", "https://artemis.example"), "cid");
        verify(injector, never()).scheduleInjection(any(), org.mockito.ArgumentMatchers.eq(starting), any(), anyString());
        verify(injector, never()).scheduleInjection(any(), org.mockito.ArgumentMatchers.eq(plain), any(), anyString());
    }

    @Test
    void resume_survivesAFailingSessionList() {
        when(sessions.list()).thenThrow(new RuntimeException("api down"));

        resumer.resume("cid");

        verify(injector, never()).scheduleInjection(any(), any(), any(), anyString());
    }

    private void set(String name, Object value) throws Exception {
        field(resumer, name, value);
    }

    private static void field(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
