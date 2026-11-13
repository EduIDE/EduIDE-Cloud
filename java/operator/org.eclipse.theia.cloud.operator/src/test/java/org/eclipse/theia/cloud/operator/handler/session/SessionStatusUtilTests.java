/********************************************************************************
 * Copyright (C) 2026 EclipseSource and others.
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.function.Consumer;

import org.eclipse.theia.cloud.common.k8s.client.SessionResourceClient;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.OperatorStatus;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionStatus;
import org.eclipse.theia.cloud.common.util.TheiaCloudError;
import org.junit.jupiter.api.Test;

class SessionStatusUtilTests {

    @Test
    void markError_setsErrorTheServiceWaitsFor() {
        SessionStatus status = new SessionStatus();

        SessionStatusUtil.markError(clientUpdating(status), new Session(), "corr", "App Definition not found.");

        assertEquals(OperatorStatus.ERROR, status.getOperatorStatus());
        assertTrue(status.hasError());
        TheiaCloudError error = TheiaCloudError.fromString(status.getError());
        assertEquals(TheiaCloudError.SESSION_LAUNCH_FAILED, error);
        assertEquals("App Definition not found.", error.getReason());
    }

    @Test
    void markError_keepsMoreSpecificErrorAlreadySet() {
        SessionStatus status = new SessionStatus();
        status.setError(TheiaCloudError.SESSION_USER_LIMIT_REACHED);

        SessionStatusUtil.markError(clientUpdating(status), new Session(), "corr", "Max sessions reached.");

        assertEquals(OperatorStatus.ERROR, status.getOperatorStatus());
        assertEquals(TheiaCloudError.SESSION_USER_LIMIT_REACHED.asString(), status.getError());
    }

    @SuppressWarnings("unchecked")
    private TheiaCloudClient clientUpdating(SessionStatus status) {
        SessionResourceClient sessions = mock(SessionResourceClient.class);
        doAnswer(invocation -> {
            ((Consumer<SessionStatus>) invocation.getArgument(2)).accept(status);
            return null;
        }).when(sessions).updateStatus(anyString(), any(), any());
        TheiaCloudClient client = mock(TheiaCloudClient.class);
        when(client.sessions()).thenReturn(sessions);
        return client;
    }
}
