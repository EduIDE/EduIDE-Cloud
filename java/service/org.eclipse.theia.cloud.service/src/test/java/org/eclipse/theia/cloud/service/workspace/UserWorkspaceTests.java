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
package org.eclipse.theia.cloud.service.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.eclipse.theia.cloud.common.k8s.resource.workspace.Workspace;
import org.eclipse.theia.cloud.common.k8s.resource.workspace.WorkspaceSpec;
import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.api.model.ObjectMeta;

class UserWorkspaceTests {

    @Test
    void fromWorkspace_readsSessionEndAnnotations() {
        Workspace workspace = workspace();
        workspace.getMetadata()
                .setAnnotations(Map.of(Workspace.ANNOTATION_LAST_SESSION_END_REASON,
                        Workspace.SESSION_END_REASON_INACTIVITY, Workspace.ANNOTATION_LAST_SESSION_END_TIME,
                        "2026-11-06T10:00:00Z"));

        UserWorkspace userWorkspace = new UserWorkspace(workspace);

        assertEquals("ws-name", userWorkspace.name);
        assertEquals(Workspace.SESSION_END_REASON_INACTIVITY, userWorkspace.lastSessionEndReason);
        assertEquals("2026-11-06T10:00:00Z", userWorkspace.lastSessionEndTime);
    }

    @Test
    void fromWorkspace_withoutAnnotations_leavesReasonEmpty() {
        UserWorkspace userWorkspace = new UserWorkspace(workspace());

        assertNull(userWorkspace.lastSessionEndReason);
        assertNull(userWorkspace.lastSessionEndTime);
    }

    private Workspace workspace() {
        Workspace workspace = new Workspace();
        workspace.setMetadata(new ObjectMeta());
        workspace.setSpec(new WorkspaceSpec("ws-name", "label", "app", "student"));
        return workspace;
    }
}
