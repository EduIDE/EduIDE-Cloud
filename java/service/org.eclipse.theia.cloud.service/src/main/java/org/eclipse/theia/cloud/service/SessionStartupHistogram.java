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
package org.eclipse.theia.cloud.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Tag;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Session startup time as a Prometheus histogram, so start times can be aggregated over any time window. The existing
 * timer only exports quantiles over a sliding window. MicroProfile Metrics 4 has no bucketed histogram, so the buckets
 * are cumulative gauges with an {@code le} tag, which is all {@code histogram_quantile} needs. Prometheus sees
 * {@code application_eduide_session_startup_seconds_bucket}, {@code _sum} and {@code _count}.
 */
@ApplicationScoped
public class SessionStartupHistogram {

    static final String NAME = "eduide.session.startup.seconds";
    static final double[] BUCKETS = { 1, 2, 5, 10, 20, 30, 45, 60, 90, 120, 180, 300 };

    @Inject
    MetricRegistry metricRegistry;

    private final Map<String, Series> series = new ConcurrentHashMap<>();

    public void observe(String appDefinition, String namespace, boolean success, double seconds) {
        String outcome = success ? "success" : "error";
        series.computeIfAbsent(appDefinition + '\0' + namespace + '\0' + outcome,
                key -> register(appDefinition, namespace, outcome)).observe(seconds);
    }

    private Series register(String appDefinition, String namespace, String outcome) {
        Series s = new Series();
        Tag[] tags = { new Tag("app_definition", appDefinition), new Tag("namespace", namespace),
                new Tag("outcome", outcome) };
        for (int i = 0; i < BUCKETS.length; i++) {
            LongAdder bucket = s.buckets[i];
            metricRegistry.gauge(NAME + ".bucket", bucket::sum, withLe(tags, formatBound(BUCKETS[i])));
        }
        metricRegistry.gauge(NAME + ".bucket", s.count::sum, withLe(tags, "+Inf"));
        metricRegistry.gauge(NAME + ".sum", s.sum::sum, tags);
        metricRegistry.gauge(NAME + ".count", s.count::sum, tags);
        return s;
    }

    private static Tag[] withLe(Tag[] tags, String le) {
        Tag[] result = new Tag[tags.length + 1];
        System.arraycopy(tags, 0, result, 0, tags.length);
        result[tags.length] = new Tag("le", le);
        return result;
    }

    static String formatBound(double bound) {
        return bound == Math.rint(bound) ? String.valueOf((long) bound) : String.valueOf(bound);
    }

    static final class Series {
        final LongAdder[] buckets = new LongAdder[BUCKETS.length];
        final LongAdder count = new LongAdder();
        final DoubleAdder sum = new DoubleAdder();

        Series() {
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = new LongAdder();
            }
        }

        void observe(double seconds) {
            for (int i = 0; i < BUCKETS.length; i++) {
                if (seconds <= BUCKETS[i]) {
                    buckets[i].increment();
                }
            }
            count.increment();
            sum.add(seconds);
        }
    }
}
