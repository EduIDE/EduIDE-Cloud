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
package org.eclipse.theia.cloud.operator.databridge;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

import org.eclipse.theia.cloud.common.k8s.client.DataBridgeClient;
import org.eclipse.theia.cloud.common.k8s.client.SessionResourceClient;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.session.DataInjectionResponse;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.fabric8.kubernetes.api.model.ObjectMeta;

class AsyncDataInjectorTests {

    private static final String NAME = "session-a";
    private static final Map<String, String> ENV = Map.of("GIT_URI", "https://example.org/repo.git");

    private final DataBridgeClient bridge = Mockito.mock(DataBridgeClient.class);
    private final SessionResourceClient sessions = Mockito.mock(SessionResourceClient.class);
    private final DataBridgeTokenManager tokens = Mockito.mock(DataBridgeTokenManager.class);
    private final AsyncDataInjector injector = new AsyncDataInjector();

    @BeforeEach
    void wire() throws Exception {
        TheiaCloudClient client = Mockito.mock(TheiaCloudClient.class);
        when(client.sessions()).thenReturn(sessions);
        set("dataBridgeClient", bridge);
        set("client", client);
        set("dataBridgeTokens", tokens);
        when(tokens.readForSession(any(Session.class), anyString())).thenReturn(Optional.of("instance-token"));
    }

    @AfterEach
    void stop() {
        injector.shutdown();
    }

    @Test
    void injectsTheEnvironmentSessionSecretAndInstanceTokenOnceHealthy() {
        Session session = session("uid-1");
        DataInjectionResponse success = success();
        when(sessions.get(NAME)).thenReturn(Optional.of(session));
        when(bridge.healthCheck(eq(NAME), anyString())).thenReturn(false, true);
        when(bridge.injectData(any(Session.class), any(), any(), anyString())).thenReturn(Optional.of(success));

        injector.scheduleInjection(null, session, ENV, "cid");

        verify(bridge, timeout(5000)).injectData(session,
                Map.of("GIT_URI", "https://example.org/repo.git", AsyncDataInjector.SESSION_SECRET_ENV, "secret"),
                "instance-token", "cid");
    }

    @Test
    void neverInjectsIntoAnInstanceWhoseSessionEnded() {
        Session session = session("uid-1");
        when(sessions.get(NAME)).thenReturn(Optional.empty());
        when(bridge.healthCheck(eq(NAME), anyString())).thenReturn(true);

        injector.scheduleInjection(null, session, ENV, "cid");

        verify(bridge, after(500).never()).injectData(any(Session.class), any(), any(), anyString());
    }

    @Test
    void neverInjectsIntoASessionRecreatedUnderTheSameName() {
        Session session = session("uid-1");
        Session recreated = session("uid-2");
        when(sessions.get(NAME)).thenReturn(Optional.of(recreated));
        when(bridge.healthCheck(eq(NAME), anyString())).thenReturn(true);

        injector.scheduleInjection(null, session, ENV, "cid");

        verify(bridge, after(500).never()).injectData(any(Session.class), any(), any(), anyString());
    }

    @Test
    void retriesAFailedInjection() {
        Session session = session("uid-1");
        DataInjectionResponse success = success();
        when(sessions.get(NAME)).thenReturn(Optional.of(session));
        when(bridge.healthCheck(eq(NAME), anyString())).thenReturn(true);
        when(bridge.injectData(any(Session.class), any(), any(), anyString())).thenReturn(Optional.empty(),
                Optional.of(success));

        injector.scheduleInjection(null, session, ENV, "cid");

        verify(bridge, timeout(5000).times(2)).injectData(any(Session.class), any(), any(), anyString());
    }

    @Test
    void keepsPollingWhileTheSessionLookupFails() {
        Session session = session("uid-1");
        DataInjectionResponse success = success();
        when(sessions.get(NAME)).thenThrow(new RuntimeException("api down")).thenReturn(Optional.of(session));
        when(bridge.healthCheck(eq(NAME), anyString())).thenReturn(true);
        when(bridge.injectData(any(Session.class), any(), any(), anyString())).thenReturn(Optional.of(success));

        injector.scheduleInjection(null, session, ENV, "cid");

        verify(bridge, timeout(5000).times(1)).injectData(any(Session.class), any(), any(), anyString());
        verify(sessions, times(2)).get(NAME);
    }

    private DataInjectionResponse success() {
        DataInjectionResponse response = Mockito.mock(DataInjectionResponse.class);
        when(response.isSuccess()).thenReturn(true);
        return response;
    }

    private Session session(String uid) {
        SessionSpec spec = Mockito.mock(SessionSpec.class);
        when(spec.getName()).thenReturn(NAME);
        when(spec.getSessionSecret()).thenReturn("secret");
        ObjectMeta metadata = new ObjectMeta();
        metadata.setUid(uid);
        Session session = Mockito.mock(Session.class);
        when(session.getSpec()).thenReturn(spec);
        when(session.getMetadata()).thenReturn(metadata);
        return session;
    }

    private void set(String field, Object value) throws Exception {
        Field f = AsyncDataInjector.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(injector, value);
    }
}
