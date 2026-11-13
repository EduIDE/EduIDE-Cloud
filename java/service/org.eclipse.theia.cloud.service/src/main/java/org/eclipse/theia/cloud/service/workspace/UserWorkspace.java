/********************************************************************************
 * Copyright (C) 2022 EclipseSource and others.
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

import static org.eclipse.theia.cloud.common.util.NamingUtil.asValidName;
import static org.eclipse.theia.cloud.common.util.WorkspaceUtil.generateUniqueWorkspaceName;
import static org.eclipse.theia.cloud.common.util.WorkspaceUtil.generateWorkspaceLabel;

import java.util.Map;
import java.util.Optional;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.theia.cloud.common.k8s.resource.workspace.Workspace;
import org.eclipse.theia.cloud.common.k8s.resource.workspace.WorkspaceSpec;

@Schema(name = "UserWorkspace", description = "Description of a user workspace")
public class UserWorkspace {

    @Schema(description = "The name of the workspace", required = true)
    public String name;

    @Schema(description = "The label of the workspace", required = true)
    public String label;

    @Schema(description = "The app this workspace was used with.", required = false)
    public String appDefinition;

    @Schema(description = "The user identification, usually the email address.", required = true)
    public String user;

    @Schema(description = "Whether the workspace is in use at the moment.", required = true)
    public boolean active;

    @Schema(description = "Why the operator last stopped a session on this workspace: 'inactivity' or 'lifetime'. "
            + "Sessions stopped any other way leave it unchanged, so compare lastSessionEndTime with the start of "
            + "the session in question.", required = false)
    public String lastSessionEndReason;

    @Schema(description = "When the operator last stopped a session on this workspace, as an ISO-8601 instant.", required = false)
    public String lastSessionEndTime;

    public UserWorkspace() {
    }

    public UserWorkspace(String appDefinition, String user) {
        this(appDefinition, user, null);
    }

    public UserWorkspace(String appDefinition, String user, String label) {
        this(appDefinition, user, null, label);
    }

    public UserWorkspace(String appDefinition, String user, String name, String label) {
        this.name = Optional.ofNullable(asValidName(name))
                .orElseGet(() -> generateUniqueWorkspaceName(user, appDefinition));
        this.label = Optional.ofNullable(label).orElseGet(() -> generateWorkspaceLabel(user, appDefinition));
        this.appDefinition = appDefinition;
        this.user = user;
    }

    public UserWorkspace(WorkspaceSpec data) {
        this(data.getAppDefinition(), data.getUser(), data.getName(), data.getLabel());
    }

    public UserWorkspace(Workspace workspace) {
        this(workspace.getSpec());
        Map<String, String> annotations = workspace.getMetadata() == null ? null
                : workspace.getMetadata().getAnnotations();
        if (annotations != null) {
            this.lastSessionEndReason = annotations.get(Workspace.ANNOTATION_LAST_SESSION_END_REASON);
            this.lastSessionEndTime = annotations.get(Workspace.ANNOTATION_LAST_SESSION_END_TIME);
        }
    }

    @Schema(hidden = true)
    public void setActive(boolean active) {
        this.active = active;
    }

    @Schema(hidden = true)
    public boolean isActive() {
        return active;
    }

    @Override
    public String toString() {
        return "UserWorkspace [name=" + name + ", label=" + label + ", appDefinition=" + appDefinition + ", user="
                + user + ", active=" + active + ", lastSessionEndReason=" + lastSessionEndReason + "]";
    }

}
