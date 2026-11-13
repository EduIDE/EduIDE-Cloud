/********************************************************************************
 * Copyright (C) 2026 EduIDE and others.
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
package org.eclipse.theia.cloud.operator;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.eclipse.theia.cloud.common.k8s.client.AppDefinitionResourceClient;
import org.eclipse.theia.cloud.common.k8s.client.SessionResourceClient;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.client.WorkspaceResourceClient;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinitionSpec;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionSpec;
import org.eclipse.theia.cloud.common.k8s.resource.workspace.Workspace;
import org.eclipse.theia.cloud.operator.metrics.SessionEndMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;

class BasicTheiaCloudOperatorTests {

    private static final int LIFETIME_MINUTES = 30;

    private SessionResourceClient sessions;
    private WorkspaceResourceClient workspaces;
    private BasicTheiaCloudOperator operator;

    @BeforeEach
    void setUp() throws Exception {
        TheiaCloudClient resourceClient = mock(TheiaCloudClient.class);
        sessions = mock(SessionResourceClient.class);
        workspaces = mock(WorkspaceResourceClient.class);
        AppDefinitionResourceClient appDefinitions = mock(AppDefinitionResourceClient.class);
        AppDefinition appDefinition = mock(AppDefinition.class);
        AppDefinitionSpec spec = mock(AppDefinitionSpec.class);
        when(resourceClient.sessions()).thenReturn(sessions);
        when(resourceClient.workspaces()).thenReturn(workspaces);
        when(resourceClient.appDefinitions()).thenReturn(appDefinitions);
        when(appDefinitions.get("java-17")).thenReturn(Optional.of(appDefinition));
        when(appDefinition.getSpec()).thenReturn(spec);
        when(spec.getTimeout()).thenReturn(LIFETIME_MINUTES);

        operator = new BasicTheiaCloudOperator();
        Field field = BasicTheiaCloudOperator.class.getDeclaredField("resourceClient");
        field.setAccessible(true);
        field.set(operator, resourceClient);
    }

    @Test
    void stopTimedOutSessions_pastLifetime_recordsReasonAndStops() {
        when(sessions.list()).thenReturn(List.of(session("old", "ws-old", LIFETIME_MINUTES + 5)));

        operator.stopTimedOutSessions();

        verify(workspaces).recordSessionEnd(anyString(), eq("ws-old"), eq(Workspace.SESSION_END_REASON_LIFETIME));
        verify(sessions).delete(anyString(), eq("old"));
        assertTrue(SessionEndMetrics.scrape().contains("{reason=\"lifetime\",app_definition=\"java-17\"}"));
    }

    @Test
    void stopTimedOutSessions_withinLifetime_leavesSessionAlone() {
        when(sessions.list()).thenReturn(List.of(session("young", "ws-young", LIFETIME_MINUTES - 5)));

        operator.stopTimedOutSessions();

        verify(workspaces, never()).recordSessionEnd(anyString(), anyString(), anyString());
        verify(sessions, never()).delete(anyString(), anyString());
    }

    private static Session session(String name, String workspace, int ageMinutes) {
        Session session = new Session();
        session.setMetadata(new ObjectMetaBuilder().withName(name)
                .withCreationTimestamp(Instant.now().minus(ageMinutes, ChronoUnit.MINUTES).toString()).build());
        session.setSpec(new SessionSpec(name, "java-17", "student", workspace));
        return session;
    }
}
