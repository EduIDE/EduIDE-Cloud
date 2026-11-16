/********************************************************************************
 * Copyright (C) 2026 EclipseSource and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.theia.cloud.operator.handler.ws;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.eclipse.theia.cloud.common.k8s.client.ResourceClient;
import org.eclipse.theia.cloud.common.k8s.client.SessionResourceClient;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionSpec;
import org.eclipse.theia.cloud.common.k8s.resource.workspace.Workspace;
import org.eclipse.theia.cloud.common.k8s.resource.workspace.WorkspaceSpec;
import org.eclipse.theia.cloud.common.util.WorkspaceUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClientException;

@SuppressWarnings({ "rawtypes", "unchecked" })
class LazyWorkspaceHandlerTests {

    private static final String CORRELATION_ID = "corr";

    private TheiaCloudClient client;
    private ResourceClient pvClient;
    private ResourceClient pvcClient;
    private SessionResourceClient sessionClient;
    private LazyWorkspaceHandler handler;
    private Workspace workspace;
    private String storageName;

    @BeforeEach
    void setUp() {
        client = mock(TheiaCloudClient.class);
        pvClient = mock(ResourceClient.class);
        pvcClient = mock(ResourceClient.class);
        sessionClient = mock(SessionResourceClient.class);
        doReturn(pvClient).when(client).persistentVolumesClient();
        doReturn(pvcClient).when(client).persistentVolumeClaimsClient();
        doReturn(sessionClient).when(client).sessions();

        workspace = new Workspace();
        workspace.setMetadata(new ObjectMeta());
        workspace.getMetadata().setName("ws-name");
        workspace.getMetadata().setUid("0b8bd7a2-5c3e-4b6e-9c53-7d1f3f0a2c11");
        workspace.setSpec(new WorkspaceSpec("ws-name", "label", "app", "user"));
        storageName = WorkspaceUtil.getStorageName(workspace);

        handler = new LazyWorkspaceHandler();
        handler.client = client;
    }

    @Test
    void workspaceDeleted_noPersistentVolume_doesNotDeleteIt() {
        doReturn(false).when(pvClient).has(storageName);

        assertTrue(handler.workspaceDeleted(workspace, CORRELATION_ID));

        verify(pvClient, never()).delete(anyString(), anyString());
        verify(pvcClient).delete(CORRELATION_ID, storageName);
    }

    @Test
    void workspaceDeleted_persistentVolumeExists_deletesIt() {
        doReturn(true).when(pvClient).has(storageName);

        assertTrue(handler.workspaceDeleted(workspace, CORRELATION_ID));

        verify(pvClient).delete(CORRELATION_ID, storageName);
    }

    @Test
    void workspaceDeleted_persistentVolumeDeleteForbidden_doesNotPropagate() {
        doReturn(true).when(pvClient).has(storageName);
        doThrow(new KubernetesClientException("forbidden", 403, null)).when(pvClient).delete(CORRELATION_ID,
                storageName);

        assertTrue(handler.workspaceDeleted(workspace, CORRELATION_ID));

        verify(pvcClient).delete(CORRELATION_ID, storageName);
    }

    private static Session sessionOf(String workspace, String user) {
        Session session = new Session();
        session.setSpec(new SessionSpec("ws-name-session", "app", user, workspace));
        return session;
    }

    @Test
    void workspaceDeleted_deletesTheSessionOfThisWorkspace() {
        doReturn(Optional.of(sessionOf("ws-name", "user"))).when(sessionClient).get("ws-name-session");

        handler.workspaceDeleted(workspace, CORRELATION_ID);

        verify(sessionClient).delete(CORRELATION_ID, "ws-name-session");
    }

    @Test
    void workspaceDeleted_keepsASessionOfAnotherUser() {
        doReturn(Optional.of(sessionOf(null, "someone-else"))).when(sessionClient).get("ws-name-session");

        handler.workspaceDeleted(workspace, CORRELATION_ID);

        verify(sessionClient, never()).delete(anyString(), anyString());
        verify(pvcClient).delete(CORRELATION_ID, storageName);
    }

    @Test
    void workspaceDeleted_keepsASessionOfTheSameWorkspaceNameOfAnotherUser() {
        doReturn(Optional.of(sessionOf("ws-name", "someone-else"))).when(sessionClient).get("ws-name-session");

        handler.workspaceDeleted(workspace, CORRELATION_ID);

        verify(sessionClient, never()).delete(anyString(), anyString());
    }

    @Test
    void workspaceDeleted_keepsASessionOfAnotherWorkspaceOfTheSameName() {
        doReturn(Optional.of(sessionOf("other", "user"))).when(sessionClient).get("ws-name-session");

        handler.workspaceDeleted(workspace, CORRELATION_ID);

        verify(sessionClient, never()).delete(anyString(), anyString());
    }
}
