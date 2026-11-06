/********************************************************************************
 * Copyright (C) 2026 Technical University of Munich and others.
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
        assertEquals("ws-java-17-latest-alice-example-org-session", WorkspaceUtil.getSessionName(USER, APP, false));
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

        assertTrue(result.matches("ws-java-17-latest-alice-example-org-[0-9a-f]{10}-session"), result);
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
            assertTrue(name.matches("ws-[a-z0-9-]+-[0-9a-f]{10}-session"), name);
            assertEquals(name, NamingUtil.asValidName(name), "already a valid Kubernetes name");
        }
    }
}
