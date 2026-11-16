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
package org.eclipse.theia.cloud.common.util;

import static org.eclipse.theia.cloud.common.util.NamingUtil.asValidName;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

import org.eclipse.theia.cloud.common.k8s.resource.workspace.Workspace;

public final class WorkspaceUtil {
    private static final String SESSION_SUFFIX = "-session";
    private static final String WORKSPACE_PREFIX = "ws-";
    /** Prefix of ephemeral session names. Users cannot create workspaces with this prefix. */
    private static final String EPHEMERAL_PREFIX = "eph-";
    private static final int EPHEMERAL_HASH_LENGTH = 16;
    private static final int WORKSPACE_NAME_LIMIT = NamingUtil.VALID_NAME_LIMIT - SESSION_SUFFIX.length();

    private WorkspaceUtil() {
        // util
    }

    public static String generateUniqueWorkspaceName(String user, String appDefinitionName) {
        return asValidName((WORKSPACE_PREFIX + Instant.now().toEpochMilli() + getWorkspaceDescription(appDefinitionName)
                + "-" + user).toLowerCase(), WORKSPACE_NAME_LIMIT);
    }

    /**
     * Name of the ephemeral session of a user. Emails that differ only in characters that are replaced by '-' or in
     * case would collide in the readable part, so a hash of the exact user is appended.
     */
    public static String generateNonUniqueWorkspaceName(String user, String appDefinitionName) {
        String readable = asValidName(
                (EPHEMERAL_PREFIX + getWorkspaceDescription(appDefinitionName) + "-" + user).toLowerCase(),
                WORKSPACE_NAME_LIMIT - EPHEMERAL_HASH_LENGTH - 1);
        return readable + "-" + hash(user);
    }

    /**
     * The name ephemeral sessions had before they got the reserved prefix and the hash. Sessions that were started
     * under it keep running, and a relaunch has to find them to not count as a second session.
     */
    public static String getLegacyEphemeralSessionName(String user, String appDefinitionName) {
        return getSessionName(asValidName(
                (WORKSPACE_PREFIX + getWorkspaceDescription(appDefinitionName) + "-" + user).toLowerCase(),
                WORKSPACE_NAME_LIMIT));
    }

    public static boolean isReservedWorkspaceName(String workspaceName) {
        return workspaceName != null && workspaceName.toLowerCase().startsWith(EPHEMERAL_PREFIX);
    }

    private static String hash(String user) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(user).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, EPHEMERAL_HASH_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String getSessionName(String workspaceName) {
        return workspaceName + SESSION_SUFFIX;
    }

    public static String getSessionName(String user, String appDefinitionName, boolean unique) {
        String workspaceName = unique ? generateUniqueWorkspaceName(user, getWorkspaceDescription(appDefinitionName))
                : generateNonUniqueWorkspaceName(user, getWorkspaceDescription(appDefinitionName));
        return getSessionName(workspaceName);
    }

    public static String getStorageName(Workspace workspace) {
        return NamingUtil.createName(workspace);
    }

    public static String generateWorkspaceLabel(String user, String appDefinitionName) {
        return getWorkspaceDescription(appDefinitionName) + " of " + user;
    }

    protected static String getWorkspaceDescription(String appDefinitionName) {
        return (appDefinitionName == null || appDefinitionName.isBlank()) ? "Workspace" : appDefinitionName;
    }

}
