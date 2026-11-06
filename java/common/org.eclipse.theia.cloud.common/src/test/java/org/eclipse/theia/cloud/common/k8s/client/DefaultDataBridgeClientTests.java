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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinitionSpec;
import org.eclipse.theia.cloud.common.k8s.resource.session.DataInjectionResponse;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionSpec;
import org.eclipse.theia.cloud.common.util.DataBridgeUtil;
import org.junit.jupiter.api.Test;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

class DefaultDataBridgeClientTests {

    private static final String SESSION = "session-1";
    private static final String APP_DEF = "app";

    @Test
    void injectData_sendsSessionSecretAsBearerToken() {
        AtomicReference<Request> sent = new AtomicReference<>();
        DefaultDataBridgeClient client = clientCapturing(sent);

        Optional<DataInjectionResponse> response = client.injectData(session("s3cr3t"), Map.of("GIT_USER", "ada"),
                "corr");

        assertTrue(response.get().isSuccess());
        assertEquals("Bearer s3cr3t", sent.get().header("Authorization"));
        assertEquals("http://10.0.0.1:16281/data", sent.get().url().toString());
    }

    @Test
    void injectData_withoutSessionSecret_sendsNoAuthorizationHeader() {
        AtomicReference<Request> sent = new AtomicReference<>();
        DefaultDataBridgeClient client = clientCapturing(sent);

        client.injectData(session(null), Map.of("GIT_USER", "ada"), "corr");

        assertNull(sent.get().header("Authorization"));
    }

    private static Session session(String secret) {
        SessionSpec spec = new SessionSpec(SESSION, APP_DEF, "user");
        spec.setSessionSecret(secret);
        Session session = new Session();
        session.setSpec(spec);
        return session;
    }

    private static DefaultDataBridgeClient clientCapturing(AtomicReference<Request> sent) {
        AppDefinitionSpec appDefSpec = mock(AppDefinitionSpec.class);
        when(appDefSpec.getOptions()).thenReturn(Map.of(DataBridgeUtil.DATA_BRIDGE_ENABLED_OPTION, "true"));
        AppDefinition appDef = new AppDefinition();
        appDef.setSpec(appDefSpec);
        AppDefinitionResourceClient appDefs = mock(AppDefinitionResourceClient.class);
        when(appDefs.get(APP_DEF)).thenReturn(Optional.of(appDef));

        TheiaCloudClient theiaCloudClient = mock(TheiaCloudClient.class);
        when(theiaCloudClient.getClusterIPFromSessionName(SESSION)).thenReturn(Optional.of("10.0.0.1"));
        when(theiaCloudClient.appDefinitions()).thenReturn(appDefs);

        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            sent.set(chain.request());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                    .message("OK").body(ResponseBody.create("{}", MediaType.parse("application/json"))).build();
        }).build();
        return new DefaultDataBridgeClient(theiaCloudClient, http);
    }
}
