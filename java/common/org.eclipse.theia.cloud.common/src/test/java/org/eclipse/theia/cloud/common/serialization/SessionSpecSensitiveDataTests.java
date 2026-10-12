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
package org.eclipse.theia.cloud.common.serialization;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.theia.cloud.common.k8s.resource.session.SessionSpec;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * Verifies that the session bearer token is redacted when a {@link SessionSpec} is serialized by an
 * ObjectMapper that has the sensitive-data modifier registered (as the service's REST layer does),
 * so the token is never returned to API clients or written to logs.
 */
class SessionSpecSensitiveDataTests {

    private ObjectMapper mapperWithModifier() {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.setSerializerModifier(new SensitiveDataBeanSerializerModifier());
        mapper.registerModule(module);
        return mapper;
    }

    @Test
    void sessionSecret_isRedactedOnSerialization() throws Exception {
        SessionSpec spec = new SessionSpec("ws-demo-session", "demo-app", "user@example.com");
        spec.setSessionSecret("super-secret-bearer-token");

        String json = mapperWithModifier().writeValueAsString(spec);

        assertFalse(json.contains("super-secret-bearer-token"), "raw session secret must not be serialized");
        assertTrue(json.contains(SensitiveDataSerializer.REDACTED_STRING), "session secret must be redacted");
    }
}
