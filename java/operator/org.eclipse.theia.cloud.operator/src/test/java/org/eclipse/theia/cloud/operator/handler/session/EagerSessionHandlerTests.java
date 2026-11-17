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
package org.eclipse.theia.cloud.operator.handler.session;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;

import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionSpec;
import org.eclipse.theia.cloud.operator.pool.PrewarmedResourcePool;
import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;

class EagerSessionHandlerTests {

    @Test
    void trySessionAdded_releasesTheSessionFromTheOrphanCheckWhenItFails() throws Exception {
        PrewarmedResourcePool pool = mock(PrewarmedResourcePool.class);
        EagerSessionHandler handler = new EagerSessionHandler();
        Field field = EagerSessionHandler.class.getDeclaredField("pool");
        field.setAccessible(true);
        field.set(handler, pool);
        Session session = new Session();
        session.setSpec(new SessionSpec("session", "app", "user@example.org"));
        session.setMetadata(new ObjectMetaBuilder().withName("session").withUid("uid").build());

        // no client is injected, so the handler fails at once
        assertThrows(RuntimeException.class, () -> handler.trySessionAdded(session, "cid", null));

        verify(pool).sessionSetupFinished(session);
    }
}
