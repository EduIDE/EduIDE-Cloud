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
package org.eclipse.theia.cloud.operator.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CustomEnvFilterTests {

    @Test
    void emptyAllowlist_dropsAllRefs() {
        CustomEnvFilter filter = new CustomEnvFilter(List.of(), List.of(), true);

        assertEquals(List.of(), filter.allowedSecretRefs(List.of("service-admin-api-token", "any-secret")));
        assertEquals(List.of(), filter.allowedConfigMapRefs(List.of("service-config")));
        assertEquals(List.of("service-admin-api-token", "any-secret"),
                filter.rejectedSecretRefs(List.of("service-admin-api-token", "any-secret")));
    }

    @Test
    void allowlist_keepsOnlyListedRefs() {
        CustomEnvFilter filter = new CustomEnvFilter(List.of("allowed-secret"), List.of("allowed-cm"), true);

        assertEquals(List.of("allowed-secret"),
                filter.allowedSecretRefs(List.of("allowed-secret", "sh.helm.release.v1.eduide.v6")));
        assertEquals(List.of("sh.helm.release.v1.eduide.v6"),
                filter.rejectedSecretRefs(List.of("allowed-secret", "sh.helm.release.v1.eduide.v6")));
        assertEquals(List.of("allowed-cm"), filter.allowedConfigMapRefs(List.of("allowed-cm", "operator-config")));
    }

    @Test
    void nullRefs_areHandled() {
        CustomEnvFilter filter = new CustomEnvFilter(List.of("allowed-secret"), List.of(), true);
        assertEquals(List.of(), filter.allowedSecretRefs(null));
        assertEquals(List.of(), filter.rejectedSecretRefs(null));
    }

    @Test
    void mapEnv_dropsReservedPlatformVariables() {
        CustomEnvFilter filter = new CustomEnvFilter(List.of(), List.of(), true);
        Map<String, String> requested = new java.util.LinkedHashMap<>();
        requested.put("MY_VAR", "ok");
        requested.put("THEIACLOUD_SESSION_SECRET", "forged");
        requested.put("KEYCLOAK_CLIENT_ID", "x");
        requested.put("LD_PRELOAD", "/evil.so");
        requested.put("NODE_OPTIONS", "--require /evil.js");

        Map<String, String> allowed = filter.allowedMapEnv(requested);
        assertEquals(Map.of("MY_VAR", "ok"), allowed);
        assertTrue(filter.rejectedMapKeys(requested).contains("THEIACLOUD_SESSION_SECRET"));
        assertTrue(filter.rejectedMapKeys(requested).contains("LD_PRELOAD"));
    }

    @Test
    void mapEnv_droppedEntirelyWhenDisabled() {
        CustomEnvFilter filter = new CustomEnvFilter(List.of(), List.of(), false);
        Map<String, String> requested = Map.of("MY_VAR", "ok");
        assertEquals(Map.of(), filter.allowedMapEnv(requested));
        assertEquals(List.of("MY_VAR"), filter.rejectedMapKeys(requested));
    }

    @Test
    void isReserved_coversPrefixesAndNamesCaseInsensitively() {
        assertTrue(CustomEnvFilter.isReserved("theiacloud_session_secret"));
        assertTrue(CustomEnvFilter.isReserved("ld_preload"));
        assertFalse(CustomEnvFilter.isReserved("MY_VAR"));
    }
}
