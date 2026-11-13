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
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void launch_operatorReportsError_returnsErroredSessionWithoutRecreating() throws Exception {
        Session pending = session(new SessionStatus());
        Session errored = session(new SessionStatus());
        errored.getStatus().setError(TheiaCloudError.SESSION_LAUNCH_FAILED);
        DefaultSessionResourceClient client = spy(
                new DefaultSessionResourceClient(mock(NamespacedKubernetesClient.class)));
        // first lookup finds the pending session, after the error it is already deleted
        doReturn(Optional.of(pending), Optional.empty()).when(client).get(NAME);
        doReturn(List.of()).when(client).delete(anyString(), anyString());
        doAnswer(invocation -> {
            BiFunction<Action, Session, Boolean> handler = invocation.getArgument(0);
            handler.apply(Action.MODIFIED, errored);
            return null;
        }).when(client).watchUntil(any(), anyLong(), any());

        Session result = client.launch("corr", new SessionSpec(NAME, "app", "user"), 1, TimeUnit.SECONDS);

        assertSame(errored, result);
        assertEquals(TheiaCloudError.SESSION_LAUNCH_FAILED.asString(), result.getStatus().getError());
        verify(client).delete("corr", NAME);
        verify(client, never()).create(anyString(), any(SessionSpec.class));
    }

    @Test
    void launch_operatorReportsUrl_returnsSessionWithUrl() throws Exception {
        Session pending = session(new SessionStatus());
        Session ready = session(new SessionStatus());
        ready.getStatus().setUrl("https://example.org/session");
        DefaultSessionResourceClient client = spy(
                new DefaultSessionResourceClient(mock(NamespacedKubernetesClient.class)));
        doReturn(Optional.of(pending)).when(client).get(NAME);
        doAnswer(invocation -> {
            BiFunction<Action, Session, Boolean> handler = invocation.getArgument(0);
            handler.apply(Action.MODIFIED, ready);
            return null;
        }).when(client).watchUntil(any(), anyLong(), any());

        Session result = client.launch("corr", new SessionSpec(NAME, "app", "user"), 1, TimeUnit.SECONDS);

        assertSame(ready, result);
        verify(client, never()).delete(anyString(), anyString());
    }

    private Session session(SessionStatus status) {
        Session session = new Session();
        session.setSpec(new SessionSpec(NAME, "app", "user"));
        session.setStatus(status);
        return session;
    }
}
