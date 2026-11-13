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

import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatLogMessage;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.sun.net.httpserver.HttpServer;

/**
 * Counts the sessions the operator stopped, by reason and app definition, and serves them in the Prometheus text
 * format. One counter does not justify a metrics library.
 */
public final class SessionEndMetrics {

    public static final String METRIC = "theiacloud_sessions_ended_total";

    private static final Logger LOGGER = LogManager.getLogger(SessionEndMetrics.class);

    // Key is "reason\0appDefinition". Both are fixed strings or Kubernetes names, so no label escaping is needed.
    private static final Map<String, LongAdder> ENDED = new ConcurrentHashMap<>();

    private SessionEndMetrics() {
    }

    public static void recordSessionEnd(String reason, String appDefinition) {
        ENDED.computeIfAbsent(reason + '\0' + appDefinition, key -> new LongAdder()).increment();
    }

    public static String scrape() {
        StringBuilder out = new StringBuilder();
        out.append("# HELP ").append(METRIC).append(" Sessions the operator stopped, by reason.\n");
        out.append("# TYPE ").append(METRIC).append(" counter\n");
        new TreeMap<>(ENDED).forEach((key, count) -> {
            String[] labels = key.split("\0", 2);
            out.append(METRIC).append("{reason=\"").append(labels[0]).append("\",app_definition=\"")
                    .append(labels[1]).append("\"} ").append(count.sum()).append('\n');
        });
        return out.toString();
    }

    /** Serves GET /metrics on the given port, in a daemon thread. */
    public static HttpServer serve(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/metrics", exchange -> {
            byte[] body = (scrape() + PoolMetrics.scrape()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream stream = exchange.getResponseBody()) {
                stream.write(body);
            }
        });
        server.start();
        LOGGER.info(formatLogMessage("metrics", "Serving operator metrics on port " + port + " at /metrics"));
        return server;
    }

    // For tests: the counters are process-wide.
    static void reset() {
        ENDED.clear();
    }
}
