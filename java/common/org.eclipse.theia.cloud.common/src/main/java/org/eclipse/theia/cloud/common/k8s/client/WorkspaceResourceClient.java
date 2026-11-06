/********************************************************************************
 * Copyright (C) 2022-2023 EclipseSource and others.
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

import java.time.Instant;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.theia.cloud.common.k8s.resource.workspace.Workspace;
import org.eclipse.theia.cloud.common.k8s.resource.workspace.WorkspaceSpec;
import org.eclipse.theia.cloud.common.k8s.resource.workspace.WorkspaceSpecResourceList;
import org.eclipse.theia.cloud.common.k8s.resource.workspace.WorkspaceStatus;

public interface WorkspaceResourceClient
        extends CustomResourceClient<WorkspaceSpec, WorkspaceStatus, Workspace, WorkspaceSpecResourceList> {

    Workspace launch(String correlationId, WorkspaceSpec spec, long timeout, TimeUnit unit);

    default Workspace launch(String correlationId, WorkspaceSpec spec) {
        return launch(correlationId, spec, 1, TimeUnit.MINUTES);
    }

    /**
     * Stamps the workspace with why its session ended. Best effort: a failure here must never keep the operator
     * from stopping the session, so it only logs.
     */
    default void recordSessionEnd(String correlationId, String workspaceName, String reason) {
        if (workspaceName == null || workspaceName.isBlank()) {
            return;
        }
        try {
            edit(correlationId, workspaceName, workspace -> {
                if (workspace.getMetadata().getAnnotations() == null) {
                    workspace.getMetadata().setAnnotations(new HashMap<>());
                }
                workspace.getMetadata().getAnnotations().put(Workspace.ANNOTATION_LAST_SESSION_END_REASON, reason);
                workspace.getMetadata().getAnnotations().put(Workspace.ANNOTATION_LAST_SESSION_END_TIME,
                        Instant.now().toString());
            });
        } catch (Exception e) {
            warn(correlationId, "Could not record why the session on workspace " + workspaceName + " ended: " + e);
        }
    }
}
