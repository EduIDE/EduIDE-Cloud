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

/**
 * Unit tests for {@link WorkspaceUtil}.
 */
class WorkspaceUtilTests {

    private static final String USER = "alice@example.org";
    private static final String APP = "java-17-latest";
    private static final String REPO_A = "https://github.com/octocat/hello-world";
    private static final String REPO_B = "https://github.com/octocat/spoon-knife";

    @Test
    void getSessionName_NonUnique() {
        String result = WorkspaceUtil.getSessionName(USER, APP, false);

        assertTrue(result.matches("eph-java-17-latest-alice-example-org-[0-9a-f]{16}-session"), result);
    }

    @Test
    void getSessionName_NullOrBlankDiscriminatorKeepsTheNonUniqueName() {
        String nonUnique = WorkspaceUtil.getSessionName(USER, APP, false);

        assertEquals(nonUnique, WorkspaceUtil.getSessionName(USER, APP, (String) null));
        assertEquals(nonUnique, WorkspaceUtil.getSessionName(USER, APP, ""));
        assertEquals(nonUnique, WorkspaceUtil.getSessionName(USER, APP, "  "));
    }

    @Test
    void getSessionName_DiscriminatorAddsAHash() {
        String result = WorkspaceUtil.getSessionName(USER, APP, REPO_A);

        assertTrue(result.matches("eph-java-17-latest-alice-example-org-[0-9a-f]{16}-session"), result);
    }

    @Test
    void getSessionName_DiscriminatorKeepsUsersApartThatOnlyDifferInPunctuationOrCase() {
        assertNotEquals(WorkspaceUtil.getSessionName("a.b@example.org", APP, REPO_A),
                WorkspaceUtil.getSessionName("a_b@example.org", APP, REPO_A));
        assertNotEquals(WorkspaceUtil.getSessionName("a.b@example.org", APP, REPO_A),
                WorkspaceUtil.getSessionName("A.B@example.org", APP, REPO_A));
    }

    @Test
    void getSessionName_DiscriminatorKeepsTheReservedPrefix() {
        assertTrue(WorkspaceUtil.isReservedWorkspaceName(WorkspaceUtil.getSessionName(USER, APP, REPO_A)));
    }

    @Test
    void getSessionName_SameDiscriminatorSameName() {
        assertEquals(WorkspaceUtil.getSessionName(USER, APP, REPO_A), WorkspaceUtil.getSessionName(USER, APP, REPO_A));
    }

    @Test
    void getSessionName_DifferentDiscriminatorsDifferentNames() {
        assertNotEquals(WorkspaceUtil.getSessionName(USER, APP, REPO_A),
                WorkspaceUtil.getSessionName(USER, APP, REPO_B));
    }

    @Test
    void getSessionName_HashSurvivesTruncation() {
        String longUser = "someone.with.a.really.long.address@students.example.university.org";

        String a = WorkspaceUtil.getSessionName(longUser, APP, REPO_A);
        String b = WorkspaceUtil.getSessionName(longUser, APP, REPO_B);

        assertNotEquals(a, b);
        for (String name : new String[] { a, b }) {
            assertTrue(name.length() <= NamingUtil.VALID_NAME_LIMIT, name);
            assertTrue(name.matches("eph-[a-z0-9-]+-[0-9a-f]{16}-session"), name);
            // DNS-1123 label: lowercase alphanumerics and '-', starting and ending with an alphanumeric
            assertTrue(name.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?"), name);
            assertEquals(name, NamingUtil.asValidName(name), "already a valid Kubernetes name");
        }
    }

    @Test
    void getSessionName_UsersStayApartWhenTheirNamesAreTruncated() {
        // The readable part is cut before the user name, so only the hash tells these users apart.
        String longApp = "a".repeat(NamingUtil.VALID_NAME_LIMIT - 24);

        assertNotEquals(WorkspaceUtil.getSessionName("alice", longApp, REPO_A),
                WorkspaceUtil.getSessionName("anna", longApp, REPO_A));
    }

    @Test
    void getSessionName_AppDefinitionsStayApartWhenTruncated() {
        String prefix = "b".repeat(NamingUtil.VALID_NAME_LIMIT);

        assertNotEquals(WorkspaceUtil.getSessionName(USER, prefix + "-java", REPO_A),
                WorkspaceUtil.getSessionName(USER, prefix + "-python", REPO_A));
    }

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

    @Test
    void legacyEphemeralSessionNameIsTheOldFormat() {
        assertEquals("ws-ide-alice-example-org-session",
                WorkspaceUtil.getLegacyEphemeralSessionName("alice@example.org", "ide"));
    }
}
