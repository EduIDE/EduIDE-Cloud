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
package org.eclipse.theia.cloud.operator.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.eclipse.theia.cloud.operator.pool.PrewarmedResourcePool.IdlePlan;
import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;

class TargetIdlePoolTests {

    @Test
    void wantedIdle_keepsTargetIdleFree() {
        assertEquals(5, PrewarmedResourcePool.wantedIdle(10, 5, 0, 1000, false));
    }

    @Test
    void wantedIdle_fillsUpToMinInstancesInTotal() {
        assertEquals(3, PrewarmedResourcePool.wantedIdle(1, 2, 4, 1000, false));
    }

    @Test
    void wantedIdle_neverGrowsBeyondMaxInstances() {
        assertEquals(2, PrewarmedResourcePool.wantedIdle(8, 5, 0, 10, false));
        assertEquals(0, PrewarmedResourcePool.wantedIdle(12, 5, 0, 10, false));
    }

    @Test
    void wantedIdle_givesEverythingUpUnderCapacityPressure() {
        assertEquals(0, PrewarmedResourcePool.wantedIdle(0, 5, 3, 1000, true));
    }

    @Test
    void planIdle_createsMissingInstancesWithLowestFreeIds() {
        // 1 and 3 are claimed, 2 is free, 5 is broken: 4 is the lowest id nothing uses.
        IdlePlan plan = PrewarmedResourcePool.planIdle(Set.of(1, 2, 3, 5), List.of(2), List.of(5), 3);

        assertEquals(List.of(5), plan.toDelete);
        assertEquals(List.of(4, 6), plan.toCreate);
    }

    @Test
    void planIdle_deletesSurplusFromTheEndOfThePreferenceOrder() {
        IdlePlan plan = PrewarmedResourcePool.planIdle(Set.of(1, 2, 3, 4), List.of(3, 1, 4, 2), List.of(), 2);

        assertEquals(List.of(4, 2), plan.toDelete);
        assertEquals(List.of(), plan.toCreate);
    }

    @Test
    void planIdle_capsCreationsPerPass() {
        IdlePlan plan = PrewarmedResourcePool.planIdle(Set.of(), List.of(), List.of(), 50);

        assertEquals(PrewarmedResourcePool.MAX_CREATIONS_PER_PASS, plan.toCreate.size());
        assertEquals(1, plan.toCreate.get(0));
    }

    @Test
    void isReady_needsAReadyReplica() {
        assertFalse(PrewarmedResourcePool.isReady(null));
        assertFalse(PrewarmedResourcePool.isReady(deployment("d", null, null)));
        assertTrue(PrewarmedResourcePool.isReady(deployment("d", null, 1)));
    }

    @Test
    void isStarved_whenQuotaRefusesThePod() {
        Deployment d = deployment("session-a", "s-a", 0);
        d.getStatus().getConditions().add(new io.fabric8.kubernetes.api.model.apps.DeploymentConditionBuilder()
                .withType("ReplicaFailure").withStatus("True")
                .withMessage("pods \"x\" is forbidden: exceeded quota: default, requested: requests.cpu=600m")
                .build());

        assertTrue(PoolMaintenance.isStarved(d, List.of()));
    }

    @Test
    void isStarved_whenNoNodeFitsThePod() {
        Deployment d = deployment("session-a", "s-a", 0);

        assertTrue(PoolMaintenance.isStarved(d, List.of(unschedulablePod("session-a-5d8f-abcde"))));
        assertFalse(PoolMaintenance.isStarved(d, List.of(unschedulablePod("session-b-5d8f-abcde"))));
    }

    @Test
    void isStarved_ignoresFreePoolInstancesAndReadySessions() {
        assertFalse(PoolMaintenance.isStarved(deployment("instance-1", null, 0),
                List.of(unschedulablePod("instance-1-5d8f-abcde"))));
        assertFalse(PoolMaintenance.isStarved(deployment("session-a", "s-a", 1),
                List.of(unschedulablePod("session-a-5d8f-abcde"))));
    }

    @Test
    void readyAfter_measuresCreationToAvailable() {
        Deployment d = deployment("instance-1", null, 1);
        d.getMetadata().setCreationTimestamp("2026-11-13T10:00:00Z");
        d.getStatus().getConditions().add(new io.fabric8.kubernetes.api.model.apps.DeploymentConditionBuilder()
                .withType("Available").withStatus("True").withLastTransitionTime("2026-11-13T10:00:42Z").build());

        assertEquals(Duration.ofSeconds(42),
                PoolMaintenance.readyAfter(d, Instant.parse("2026-11-13T09:00:00Z")).orElseThrow());
        assertTrue(PoolMaintenance.readyAfter(d, Instant.parse("2026-11-13T11:00:00Z")).isEmpty());
    }

    private static Deployment deployment(String name, String sessionOwner, Integer readyReplicas) {
        ObjectMetaBuilder meta = new ObjectMetaBuilder().withName(name).withUid(name + "-uid");
        if (sessionOwner != null) {
            meta.withOwnerReferences(new OwnerReferenceBuilder().withKind("Session").withName(sessionOwner).build());
        }
        return new DeploymentBuilder().withMetadata(meta.build()).withNewStatus().withReadyReplicas(readyReplicas)
                .withConditions(new java.util.ArrayList<>()).endStatus().build();
    }

    private static Pod unschedulablePod(String name) {
        return new PodBuilder().withNewMetadata().withName(name).endMetadata().withNewStatus().withPhase("Pending")
                .addNewCondition().withType("PodScheduled").withStatus("False").withReason("Unschedulable")
                .endCondition().endStatus().build();
    }
}
