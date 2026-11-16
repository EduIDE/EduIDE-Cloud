/********************************************************************************
 * Copyright (C) 2026 EduIDE and others.
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
package org.eclipse.theia.cloud.operator.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class SessionEndMetricsTests {

    @BeforeEach
    void setUp() {
        SessionEndMetrics.reset();
    }

    @Test
    void scrape_countsByReasonAndAppDefinition() {
        SessionEndMetrics.recordSessionEnd("inactivity", "java-17");
        SessionEndMetrics.recordSessionEnd("inactivity", "java-17");
        SessionEndMetrics.recordSessionEnd("lifetime", "c-lang");

        String scrape = SessionEndMetrics.scrape();

        assertTrue(scrape.contains("# TYPE theiacloud_sessions_ended_total counter\n"));
        assertTrue(scrape.contains(
                "theiacloud_sessions_ended_total{reason=\"inactivity\",app_definition=\"java-17\"} 2\n"));
        assertTrue(
                scrape.contains("theiacloud_sessions_ended_total{reason=\"lifetime\",app_definition=\"c-lang\"} 1\n"));
    }

    @Test
    void serve_answersOnMetricsPath() throws Exception {
        SessionEndMetrics.recordSessionEnd("lifetime", "java-17");
        HttpServer server = SessionEndMetrics.serve(0);
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/metrics")).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("reason=\"lifetime\",app_definition=\"java-17\"} 1"));
        } finally {
            server.stop(0);
        }
    }
}
