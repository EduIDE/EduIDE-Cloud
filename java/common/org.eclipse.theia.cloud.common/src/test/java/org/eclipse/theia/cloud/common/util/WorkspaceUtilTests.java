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
package org.eclipse.theia.cloud.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorkspaceUtilTests {

    @Test
    void ephemeralSessionNameDiffersForUsersThatOnlyDifferInPunctuationOrCase() {
        String dotted = WorkspaceUtil.getSessionName("a.b@example.org", "ide", false);
        String underscored = WorkspaceUtil.getSessionName("a_b@example.org", "ide", false);
        String upper = WorkspaceUtil.getSessionName("A.B@example.org", "ide", false);

        assertNotEquals(dotted, underscored);
        assertNotEquals(dotted, upper);
    }

    @Test
    void ephemeralSessionNameIsStable() {
        assertEquals(WorkspaceUtil.getSessionName("alice@example.org", "ide", false),
                WorkspaceUtil.getSessionName("alice@example.org", "ide", false));
    }

    @Test
    void ephemeralSessionNameKeepsTheHashWhenTheReadablePartIsCut() {
        String longUser = "a".repeat(80) + "@example.org";
        String name = WorkspaceUtil.getSessionName(longUser, "ide", false);

        assertTrue(name.length() <= NamingUtil.VALID_NAME_LIMIT, name);
        assertNotEquals(name, WorkspaceUtil.getSessionName(longUser + "x", "ide", false));
    }

    @Test
    void ephemeralSessionNameIsReservedAndWorkspaceNamesOfTheLandingPageAreNot() {
        assertTrue(WorkspaceUtil.isReservedWorkspaceName(
                WorkspaceUtil.generateNonUniqueWorkspaceName("alice@example.org", "ide")));
        assertTrue(WorkspaceUtil.isReservedWorkspaceName("EPH-anything"));
        assertFalse(WorkspaceUtil.isReservedWorkspaceName(
                WorkspaceUtil.generateUniqueWorkspaceName("alice@example.org", "ide")));
        assertFalse(WorkspaceUtil.isReservedWorkspaceName("ws-java-17-playground-alice-1a2b3c"));
        assertFalse(WorkspaceUtil.isReservedWorkspaceName(null));
    }
}
