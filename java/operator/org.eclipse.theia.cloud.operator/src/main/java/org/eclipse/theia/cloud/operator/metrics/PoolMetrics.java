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

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Warm pool metrics in the Prometheus text format: how sessions got their IDE, how many pool instances exist in which
 * state, and how long a new instance takes to become ready. The gauges are only filled on the leader, standbys report
 * no samples.
 */
public final class PoolMetrics {

    public static final String INSTANCES = "eduide_pool_instances";
    public static final String TARGET_IDLE = "eduide_pool_target_idle";
    public static final String CLAIMS = "eduide_session_claims_total";
    public static final String REJECTED = "eduide_session_rejected_total";
    public static final String EVICTED = "eduide_pool_instances_evicted_total";
    public static final String READY_SECONDS = "eduide_pool_instance_ready_seconds";

    public static final String CLAIM_WARM_READY = "warm_ready";
    public static final String CLAIM_WARM_STARTING = "warm_starting";
    public static final String CLAIM_COLD = "cold";

    public static final String REJECT_CAPACITY = "capacity";
    public static final String REJECT_MAX_INSTANCES = "max_instances";

    public static final String STATE_IDLE_READY = "idle_ready";
    public static final String STATE_IDLE_STARTING = "idle_starting";
    public static final String STATE_CLAIMED = "claimed";

    static final double[] READY_BUCKETS = { 5, 10, 20, 30, 45, 60, 90, 120, 180, 300 };

    // Keys join label values with \0. All values are fixed strings or Kubernetes names, so no escaping is needed.
    private static final Map<String, LongAdder> CLAIMS_BY_KEY = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> REJECTED_BY_KEY = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> EVICTED_BY_KEY = new ConcurrentHashMap<>();
    private static final Map<String, Long> INSTANCES_BY_KEY = new ConcurrentHashMap<>();
    private static final Map<String, Long> TARGET_IDLE_BY_KEY = new ConcurrentHashMap<>();
    private static final Map<String, Histogram> READY_BY_KEY = new ConcurrentHashMap<>();

    private PoolMetrics() {
    }

    public static void recordClaim(String appDefinition, String result) {
        CLAIMS_BY_KEY.computeIfAbsent(appDefinition + '\0' + result, key -> new LongAdder()).increment();
    }

    public static void recordRejected(String appDefinition, String reason) {
        REJECTED_BY_KEY.computeIfAbsent(appDefinition + '\0' + reason, key -> new LongAdder()).increment();
    }

    public static void recordEvicted(String appDefinition) {
        EVICTED_BY_KEY.computeIfAbsent(appDefinition, key -> new LongAdder()).increment();
    }

    public static void recordReady(String appDefinition, double seconds) {
        READY_BY_KEY.computeIfAbsent(appDefinition, key -> new Histogram()).observe(seconds);
    }

    /** Replaces the instance counts of one app definition. A null targetIdle removes that gauge. */
    public static void setPoolState(String appDefinition, long idleReady, long idleStarting, long claimed,
            Integer targetIdle) {
        INSTANCES_BY_KEY.put(appDefinition + '\0' + STATE_IDLE_READY, idleReady);
        INSTANCES_BY_KEY.put(appDefinition + '\0' + STATE_IDLE_STARTING, idleStarting);
        INSTANCES_BY_KEY.put(appDefinition + '\0' + STATE_CLAIMED, claimed);
        if (targetIdle == null) {
            TARGET_IDLE_BY_KEY.remove(appDefinition);
        } else {
            TARGET_IDLE_BY_KEY.put(appDefinition, targetIdle.longValue());
        }
    }

    /** Drops the gauges of app definitions that no longer exist. */
    public static void retainPools(Set<String> appDefinitions) {
        INSTANCES_BY_KEY.keySet().removeIf(key -> !appDefinitions.contains(key.split("\0", 2)[0]));
        TARGET_IDLE_BY_KEY.keySet().removeIf(key -> !appDefinitions.contains(key));
    }

    public static String scrape() {
        StringBuilder out = new StringBuilder();
        header(out, INSTANCES, "gauge", "Warm pool instances by state.");
        new TreeMap<>(INSTANCES_BY_KEY).forEach((key, value) -> {
            String[] labels = key.split("\0", 2);
            sample(out, INSTANCES, "app_definition=\"" + labels[0] + "\",state=\"" + labels[1] + "\"", value);
        });
        header(out, TARGET_IDLE, "gauge", "Free warm pool instances the operator keeps ready.");
        new TreeMap<>(TARGET_IDLE_BY_KEY).forEach(
                (appDef, value) -> sample(out, TARGET_IDLE, "app_definition=\"" + appDef + "\"", value));
        header(out, CLAIMS, "counter", "Started sessions by how they got their IDE.");
        new TreeMap<>(CLAIMS_BY_KEY).forEach((key, count) -> {
            String[] labels = key.split("\0", 2);
            sample(out, CLAIMS, "app_definition=\"" + labels[0] + "\",result=\"" + labels[1] + "\"", count.sum());
        });
        header(out, REJECTED, "counter", "Sessions the operator refused to start.");
        new TreeMap<>(REJECTED_BY_KEY).forEach((key, count) -> {
            String[] labels = key.split("\0", 2);
            sample(out, REJECTED, "app_definition=\"" + labels[0] + "\",reason=\"" + labels[1] + "\"", count.sum());
        });
        header(out, EVICTED, "counter", "Free warm pool instances deleted to make room for sessions.");
        new TreeMap<>(EVICTED_BY_KEY)
                .forEach((appDef, count) -> sample(out, EVICTED, "app_definition=\"" + appDef + "\"", count.sum()));
        header(out, READY_SECONDS, "histogram", "Time from creating a warm pool instance until it is ready.");
        new TreeMap<>(READY_BY_KEY).forEach((appDef, histogram) -> histogram.write(out, appDef));
        return out.toString();
    }

    private static void header(StringBuilder out, String metric, String type, String help) {
        out.append("# HELP ").append(metric).append(' ').append(help).append('\n');
        out.append("# TYPE ").append(metric).append(' ').append(type).append('\n');
    }

    private static void sample(StringBuilder out, String metric, String labels, Number value) {
        out.append(metric).append('{').append(labels).append("} ").append(value).append('\n');
    }

    private static final class Histogram {
        private final long[] counts = new long[READY_BUCKETS.length];
        private long count;
        private double sum;

        synchronized void observe(double value) {
            for (int i = 0; i < READY_BUCKETS.length; i++) {
                if (value <= READY_BUCKETS[i]) {
                    counts[i]++;
                }
            }
            count++;
            sum += value;
        }

        synchronized void write(StringBuilder out, String appDef) {
            String label = "app_definition=\"" + appDef + "\"";
            for (int i = 0; i < READY_BUCKETS.length; i++) {
                sample(out, READY_SECONDS + "_bucket", label + ",le=\"" + formatBound(READY_BUCKETS[i]) + "\"",
                        counts[i]);
            }
            sample(out, READY_SECONDS + "_bucket", label + ",le=\"+Inf\"", count);
            sample(out, READY_SECONDS + "_sum", label, sum);
            sample(out, READY_SECONDS + "_count", label, count);
        }

        private static String formatBound(double bound) {
            return bound == Math.rint(bound) ? String.valueOf((long) bound) : String.valueOf(bound);
        }
    }

    // For tests: the metrics are process-wide.
    static void reset() {
        CLAIMS_BY_KEY.clear();
        REJECTED_BY_KEY.clear();
        EVICTED_BY_KEY.clear();
        INSTANCES_BY_KEY.clear();
        TARGET_IDLE_BY_KEY.clear();
        READY_BY_KEY.clear();
    }
}
