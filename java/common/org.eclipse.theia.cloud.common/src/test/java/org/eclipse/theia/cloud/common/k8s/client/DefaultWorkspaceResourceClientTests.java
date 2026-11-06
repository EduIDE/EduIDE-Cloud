/********************************************************************************
 * Copyright (C) 2026 EclipseSource and others.
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
package org.eclipse.theia.cloud.common.k8s.client;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.eclipse.theia.cloud.common.k8s.resource.workspace.Workspace;
import org.eclipse.theia.cloud.common.k8s.resource.workspace.WorkspaceSpec;
import org.eclipse.theia.cloud.common.k8s.resource.workspace.WorkspaceStatus;
import org.eclipse.theia.cloud.common.util.TheiaCloudError;
import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.client.NamespacedKubernetesClient;

class DefaultWorkspaceResourceClientTests {

    private static final String NAME = "ws-name";
    private static final String OWNER = "owner";
    private static final String OTHER_USER = "other";

    @Test
    void launch_erroneousWorkspaceOfOtherUser_returnsItWithoutDeleting() {
        Workspace existing = workspace(new WorkspaceStatus());
        existing.getStatus().setError(TheiaCloudError.WORKSPACE_LAUNCH_TIMEOUT);
        DefaultWorkspaceResourceClient client = clientWith(existing);

        Workspace result = client.launch("corr", new WorkspaceSpec(NAME, "label", "app", OTHER_USER), 1,
                TimeUnit.SECONDS);

        assertSame(existing, result);
        verify(client, never()).delete(anyString(), anyString());
    }

    @Test
    void launch_provisioningWorkspaceOfOtherUser_returnsItWithoutWaiting() throws Exception {
        Workspace existing = workspace(new WorkspaceStatus());
        DefaultWorkspaceResourceClient client = clientWith(existing);

        Workspace result = client.launch("corr", new WorkspaceSpec(NAME, "label", "app", OTHER_USER), 1,
                TimeUnit.SECONDS);

        assertSame(existing, result);
        verify(client, never()).watchUntil(any(), anyLong(), any());
        verify(client, never()).updateStatus(anyString(), any(), any());
    }

    @Test
    void launch_erroneousWorkspaceOfOwner_stillDeletesIt() {
        Workspace existing = workspace(new WorkspaceStatus());
        existing.getStatus().setError(TheiaCloudError.WORKSPACE_LAUNCH_TIMEOUT);
        DefaultWorkspaceResourceClient client = clientWith(existing);

        client.launch("corr", new WorkspaceSpec(NAME, "label", "app", OWNER), 1, TimeUnit.SECONDS);

        verify(client).delete("corr", NAME);
    }

    private Workspace workspace(WorkspaceStatus status) {
        Workspace workspace = new Workspace();
        workspace.setSpec(new WorkspaceSpec(NAME, "label", "app", OWNER));
        workspace.setStatus(status);
        return workspace;
    }

    private DefaultWorkspaceResourceClient clientWith(Workspace existing) {
        DefaultWorkspaceResourceClient client = spy(
                new DefaultWorkspaceResourceClient(mock(NamespacedKubernetesClient.class)));
        doReturn(Optional.of(existing)).when(client).get(NAME);
        doReturn(List.of()).when(client).delete(anyString(), anyString());
        return client;
    }
}
