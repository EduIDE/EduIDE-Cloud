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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PoolMetricsTests {

    @BeforeEach
    void setUp() {
        PoolMetrics.reset();
    }

    @Test
    void scrape_countsClaimsRejectionsAndEvictions() {
        PoolMetrics.recordClaim("java-17", PoolMetrics.CLAIM_WARM_READY);
        PoolMetrics.recordClaim("java-17", PoolMetrics.CLAIM_WARM_READY);
        PoolMetrics.recordClaim("java-17", PoolMetrics.CLAIM_COLD);
        PoolMetrics.recordRejected("python", PoolMetrics.REJECT_CAPACITY);
        PoolMetrics.recordEvicted("java-25");

        String scrape = PoolMetrics.scrape();

        assertTrue(scrape.contains("eduide_session_claims_total{app_definition=\"java-17\",result=\"warm_ready\"} 2\n"));
        assertTrue(scrape.contains("eduide_session_claims_total{app_definition=\"java-17\",result=\"cold\"} 1\n"));
        assertTrue(scrape.contains("eduide_session_rejected_total{app_definition=\"python\",reason=\"capacity\"} 1\n"));
        assertTrue(scrape.contains("eduide_pool_instances_evicted_total{app_definition=\"java-25\"} 1\n"));
    }

    @Test
    void scrape_reportsPoolStateAndDropsRemovedPools() {
        PoolMetrics.setPoolState("java-17", 3, 1, 7, 4);
        PoolMetrics.setPoolState("c", 0, 0, 0, null);

        String scrape = PoolMetrics.scrape();
        assertTrue(scrape.contains("eduide_pool_instances{app_definition=\"java-17\",state=\"idle_ready\"} 3\n"));
        assertTrue(scrape.contains("eduide_pool_instances{app_definition=\"java-17\",state=\"claimed\"} 7\n"));
        assertTrue(scrape.contains("eduide_pool_target_idle{app_definition=\"java-17\"} 4\n"));
        assertFalse(scrape.contains("eduide_pool_target_idle{app_definition=\"c\"}"));

        PoolMetrics.retainPools(Set.of("c"));
        assertFalse(PoolMetrics.scrape().contains("app_definition=\"java-17\",state="));
    }

    @Test
    void scrape_writesCumulativeHistogramBuckets() {
        PoolMetrics.recordReady("java-17", 8);
        PoolMetrics.recordReady("java-17", 50);

        String scrape = PoolMetrics.scrape();

        assertTrue(scrape.contains("# TYPE eduide_pool_instance_ready_seconds histogram\n"));
        assertTrue(scrape.contains("eduide_pool_instance_ready_seconds_bucket{app_definition=\"java-17\",le=\"5\"} 0\n"));
        assertTrue(scrape.contains("eduide_pool_instance_ready_seconds_bucket{app_definition=\"java-17\",le=\"10\"} 1\n"));
        assertTrue(scrape.contains("eduide_pool_instance_ready_seconds_bucket{app_definition=\"java-17\",le=\"60\"} 2\n"));
        assertTrue(scrape.contains("eduide_pool_instance_ready_seconds_bucket{app_definition=\"java-17\",le=\"+Inf\"} 2\n"));
        assertTrue(scrape.contains("eduide_pool_instance_ready_seconds_sum{app_definition=\"java-17\"} 58.0\n"));
        assertTrue(scrape.contains("eduide_pool_instance_ready_seconds_count{app_definition=\"java-17\"} 2\n"));
    }
}
