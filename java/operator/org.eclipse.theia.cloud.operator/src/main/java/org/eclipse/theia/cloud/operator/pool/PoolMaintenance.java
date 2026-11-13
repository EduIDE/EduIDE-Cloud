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

import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatLogMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.util.TheiaCloudError;
import org.eclipse.theia.cloud.operator.TheiaCloudOperatorArguments;
import org.eclipse.theia.cloud.operator.metrics.PoolMetrics;
import org.eclipse.theia.cloud.operator.util.K8sUtil;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentCondition;

/**
 * Runs on the leader next to the watches. Every pass it
 * <ul>
 * <li>finds sessions whose pod can not start because the namespace quota or the nodes are full, and while there are
 * any, lets free pool instances make room instead of refilling them. Sessions that still wait after
 * {@link #STARVED_TIMEOUT} with no free instance left to give up get {@link TheiaCloudError#CLUSTER_CAPACITY_EXHAUSTED},
 * which the service hands to the user and then deletes the session;</li>
 * <li>reconciles every pool with targetIdle, which also brings back instances someone deleted by hand;</li>
 * <li>updates the pool gauges and the ready time histogram.</li>
 * </ul>
 * Claims and releases ask for an extra pass of their pool with {@link #requestReconcile(String)} so refills do not
 * wait for the next tick.
 */
@Singleton
public class PoolMaintenance {

    private static final Logger LOGGER = LogManager.getLogger(PoolMaintenance.class);

    static final long INTERVAL_SECONDS = 30;
    // Longer than a normal image pull plus scheduling, shorter than the landing page's 60 s request timeout.
    static final Duration STARVED_TIMEOUT = Duration.ofSeconds(45);

    private static final String SESSION_KIND = "Session";
    private static final String CORRELATION_PREFIX = "pool-maintenance-";

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pool-maintenance");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> pendingReconciles = ConcurrentHashMap.newKeySet();
    private final Map<String, Instant> starvedSince = new HashMap<>();
    private final Set<String> observedReady = new HashSet<>();
    private final Instant startedAt = Instant.now();

    @Inject
    private TheiaCloudClient client;

    @Inject
    private PrewarmedResourcePool pool;

    @Inject
    private TheiaCloudOperatorArguments arguments;

    public void start() {
        executor.scheduleWithFixedDelay(this::runSafely, INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** Reconciles one pool soon, outside the caller's thread. Requests for the same pool coalesce. */
    public void requestReconcile(String appDefinitionName) {
        if (!arguments.isEagerStart() || !pendingReconciles.add(appDefinitionName)) {
            return;
        }
        executor.execute(() -> {
            pendingReconciles.remove(appDefinitionName);
            String correlationId = CORRELATION_PREFIX + appDefinitionName;
            try {
                client.appDefinitions().get(appDefinitionName).ifPresent(appDef -> {
                    pool.reconcileIdle(appDef, correlationId);
                    updateGauges(appDef);
                });
            } catch (RuntimeException e) {
                LOGGER.error(formatLogMessage(correlationId, "Reconciling pool " + appDefinitionName + " failed"), e);
            }
        });
    }

    private void runSafely() {
        try {
            run();
        } catch (RuntimeException e) {
            // A failed pass must not cancel the schedule.
            LOGGER.error(formatLogMessage(CORRELATION_PREFIX + "tick", "Pool maintenance pass failed"), e);
        }
    }

    void run() {
        String correlationId = CORRELATION_PREFIX + Instant.now().getEpochSecond();
        List<Deployment> starved = findStarvedDeployments();
        boolean pressure = !starved.isEmpty();
        if (pressure != pool.isCapacityPressure()) {
            LOGGER.info(formatLogMessage(correlationId, pressure
                    ? starved.size() + " session(s) wait for cluster resources, free pool instances make room"
                    : "No session waits for cluster resources any more, pools refill"));
        }
        pool.setCapacityPressure(pressure);

        long freeInstances = 0;
        Set<String> appDefNames = new HashSet<>();
        if (arguments.isEagerStart()) {
            for (AppDefinition appDef : client.appDefinitions().list()) {
                appDefNames.add(appDef.getMetadata().getName());
                pool.reconcileIdle(appDef, correlationId);
                long[] counts = updateGauges(appDef);
                if (appDef.getSpec().getTargetIdle() != null) {
                    // Only targetIdle pools give up free instances under pressure.
                    freeInstances += counts[0] + counts[1];
                }
                observeReadyTimes(appDef);
            }
            PoolMetrics.retainPools(appDefNames);
        }

        rejectLongStarvedSessions(starved, freeInstances, correlationId);
    }

    private long[] updateGauges(AppDefinition appDef) {
        long[] counts = pool.countInstances(appDef);
        PoolMetrics.setPoolState(appDef.getMetadata().getName(), counts[0], counts[1], counts[2],
                appDef.getSpec().getTargetIdle());
        return counts;
    }

    private void observeReadyTimes(AppDefinition appDef) {
        List<Deployment> deployments = K8sUtil.getExistingDeployments(client.kubernetes(), client.namespace(),
                appDef.getMetadata().getName(), appDef.getMetadata().getUid());
        for (Deployment deployment : deployments) {
            String uid = deployment.getMetadata().getUid();
            if (uid == null || observedReady.contains(uid)) {
                continue;
            }
            Optional<Duration> readyAfter = readyAfter(deployment, startedAt);
            if (readyAfter.isPresent()) {
                observedReady.add(uid);
                PoolMetrics.recordReady(appDef.getMetadata().getName(), readyAfter.get().toMillis() / 1000.0);
            }
        }
        if (observedReady.size() > 10_000) {
            // Deleted instances never come back, so forgetting them is safe.
            observedReady.clear();
        }
    }

    /**
     * Time from creating a deployment until it became available. Deployments created before the operator started are
     * skipped, their ready time would mix in whatever happened while no operator ran.
     */
    static Optional<Duration> readyAfter(Deployment deployment, Instant notBefore) {
        if (!PrewarmedResourcePool.isReady(deployment) || deployment.getMetadata().getCreationTimestamp() == null
                || deployment.getStatus().getConditions() == null) {
            return Optional.empty();
        }
        Instant created = Instant.parse(deployment.getMetadata().getCreationTimestamp());
        if (created.isBefore(notBefore)) {
            return Optional.empty();
        }
        return deployment.getStatus().getConditions().stream()
                .filter(c -> "Available".equals(c.getType()) && "True".equals(c.getStatus())
                        && c.getLastTransitionTime() != null)
                .findFirst().map(c -> Duration.between(created, Instant.parse(c.getLastTransitionTime())))
                .filter(d -> !d.isNegative());
    }

    private List<Deployment> findStarvedDeployments() {
        List<Deployment> deployments = client.kubernetes().apps().deployments().inNamespace(client.namespace()).list()
                .getItems();
        List<Pod> unschedulable = client.kubernetes().pods().inNamespace(client.namespace())
                .withField("status.phase", "Pending").list().getItems().stream().filter(PoolMaintenance::isUnschedulable)
                .collect(Collectors.toList());
        List<Deployment> starved = deployments.stream().filter(d -> isStarved(d, unschedulable))
                .collect(Collectors.toList());
        Set<String> names = starved.stream().map(d -> d.getMetadata().getName()).collect(Collectors.toSet());
        starvedSince.keySet().retainAll(names);
        Instant now = Instant.now();
        names.forEach(name -> starvedSince.putIfAbsent(name, now));
        return starved;
    }

    /**
     * A session's deployment starves when it has no ready pod and either the quota refused to create one or the
     * scheduler found no node for it. Free pool instances do not count, they belong to no session.
     */
    static boolean isStarved(Deployment deployment, List<Pod> unschedulablePods) {
        if (sessionOwner(deployment).isEmpty() || PrewarmedResourcePool.isReady(deployment)) {
            return false;
        }
        List<DeploymentCondition> conditions = deployment.getStatus() == null ? null
                : deployment.getStatus().getConditions();
        boolean quotaExceeded = conditions != null && conditions.stream()
                .anyMatch(c -> "ReplicaFailure".equals(c.getType()) && "True".equals(c.getStatus())
                        && c.getMessage() != null && c.getMessage().contains("exceeded quota"));
        String podPrefix = deployment.getMetadata().getName() + "-";
        boolean noNode = unschedulablePods.stream().anyMatch(p -> p.getMetadata().getName().startsWith(podPrefix));
        return quotaExceeded || noNode;
    }

    static boolean isUnschedulable(Pod pod) {
        return pod.getStatus() != null && pod.getStatus().getConditions() != null
                && pod.getStatus().getConditions().stream().anyMatch(c -> "PodScheduled".equals(c.getType())
                        && "False".equals(c.getStatus()) && "Unschedulable".equals(c.getReason()));
    }

    static Optional<String> sessionOwner(Deployment deployment) {
        List<OwnerReference> owners = deployment.getMetadata().getOwnerReferences();
        if (owners == null) {
            return Optional.empty();
        }
        return owners.stream().filter(o -> SESSION_KIND.equals(o.getKind())).map(OwnerReference::getName).findFirst();
    }

    private void rejectLongStarvedSessions(List<Deployment> starved, long freeInstances, String correlationId) {
        if (freeInstances > 0) {
            // Deleting free instances is still underway, the waiting sessions get their resources soon.
            return;
        }
        Instant deadline = Instant.now().minus(STARVED_TIMEOUT);
        for (Deployment deployment : starved) {
            Instant since = starvedSince.get(deployment.getMetadata().getName());
            if (since == null || since.isAfter(deadline)) {
                continue;
            }
            sessionOwner(deployment).flatMap(name -> client.sessions().get(name)).ifPresent(session -> {
                if (session.getStatus() != null && session.getStatus().hasError()) {
                    return;
                }
                LOGGER.info(formatLogMessage(correlationId, "Session " + session.getMetadata().getName()
                        + " found no cluster resources for " + STARVED_TIMEOUT.toSeconds() + " s, giving up"));
                PoolMetrics.recordRejected(session.getSpec().getAppDefinition(), PoolMetrics.REJECT_CAPACITY);
                client.sessions().updateStatus(correlationId, session,
                        status -> status.setError(TheiaCloudError.CLUSTER_CAPACITY_EXHAUSTED));
            });
            starvedSince.remove(deployment.getMetadata().getName());
        }
    }
}
