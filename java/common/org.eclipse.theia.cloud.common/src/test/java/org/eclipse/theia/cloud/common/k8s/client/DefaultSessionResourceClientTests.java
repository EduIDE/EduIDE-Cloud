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
package org.eclipse.theia.cloud.common.k8s.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionSpec;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionStatus;
import org.eclipse.theia.cloud.common.util.TheiaCloudError;
import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.Watcher.Action;

class DefaultSessionResourceClientTests {

    private static final String NAME = "session-name";

    @Test
    @SuppressWarnings("unchecked")
    void launch_sessionReplacedAfterConcurrentTimeout_returnsObservedErrorAndDoesNotRecreate() throws Exception {
        SessionSpec spec = new SessionSpec(NAME, "app", "user");
        Session pending = session(spec, new SessionStatus());
        Session timedOut = session(spec, new SessionStatus());
        timedOut.getStatus().setError(TheiaCloudError.SESSION_LAUNCH_TIMEOUT);

        DefaultSessionResourceClient client = spy(new DefaultSessionResourceClient(mock(NamespacedKubernetesClient.class)));
        // First lookup finds the pending session, a later one would find a fresh replacement without URL
        doReturn(Optional.of(pending), Optional.of(session(spec, new SessionStatus()))).when(client).get(NAME);
        doReturn(List.of()).when(client).delete(anyString(), anyString());
        // Another request times out and sets the error, which this watch sees
        doAnswer(invocation -> {
            invocation.<BiFunction<Action, Session, Boolean>> getArgument(0).apply(Action.MODIFIED, timedOut);
            return null;
        }).when(client).watchUntil(any(), anyLong(), any());

        Session result = client.launch("corr", spec, 1, TimeUnit.SECONDS);

        assertEquals(TheiaCloudError.SESSION_LAUNCH_TIMEOUT.asString(), result.getNonNullStatus().getError());
        verify(client, never()).create(anyString(), any());
    }

    private Session session(SessionSpec spec, SessionStatus status) {
        Session session = new Session();
        session.setSpec(spec);
        session.setStatus(status);
        return session;
    }
}
