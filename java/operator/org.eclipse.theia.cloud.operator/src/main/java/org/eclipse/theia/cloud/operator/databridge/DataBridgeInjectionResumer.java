/********************************************************************************
 * Copyright (C) 2026 EclipseSource and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.theia.cloud.operator.databridge;

import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatLogMessage;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.OperatorStatus;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.util.DataBridgeUtil;
import org.eclipse.theia.cloud.operator.util.SessionEnvCollector;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Injections are scheduled in memory, so one that was still pending when the operator stopped is lost, and a session
 * that is already handled is not processed again. After a start, this schedules an injection for every handled
 * session of an app with the data bridge. The bridge replaces what it stores, so repeating one is harmless.
 * <p>
 * A replaced pod (eviction, node drain, manual delete) starts with an empty bridge as well, so
 * {@link #reinjectReplacedPods} does the same for every session whose pod changed since the last look.
 */
@Singleton
public class DataBridgeInjectionResumer {

    private static final Logger LOGGER = LogManager.getLogger(DataBridgeInjectionResumer.class);

    @Inject
    private TheiaCloudClient client;

    @Inject
    private SessionEnvCollector sessionEnvCollector;

    @Inject
    private AsyncDataInjector asyncDataInjector;

    /** Session UID to the UID of the pod it ran on when we last looked. Not persisted: a start runs {@link #resume}. */
    private final Map<String, String> seenPods = new ConcurrentHashMap<>();

    private boolean isBridgeSession(Session session) {
        return isBridgeSession(session, new HashMap<>());
    }

    private boolean isBridgeSession(Session session, Map<String, Boolean> bridgeApps) {
        if (!OperatorStatus.HANDLED.equals(session.getNonNullStatus().getOperatorStatus())
                || session.getMetadata().getDeletionTimestamp() != null) {
            return false;
        }
        return bridgeApps.computeIfAbsent(session.getSpec().getAppDefinition(), name -> {
            Optional<AppDefinition> appDef = client.appDefinitions().get(name);
            return appDef.isPresent() && DataBridgeUtil.isDataBridgeEnabled(appDef.get().getSpec());
        });
    }

    /**
     * Schedules a new injection for every session whose pod is not the one seen on the previous call. A session seen
     * for the first time is only recorded: its launch or {@link #resume} already schedules the injection.
     */
    public void reinjectReplacedPods(String correlationId) {
        try {
            Set<String> live = new HashSet<>();
            Map<String, Boolean> bridgeApps = new HashMap<>();
            for (Session session : client.sessions().list()) {
                try {
                    if (!isBridgeSession(session, bridgeApps)) {
                        continue;
                    }
                    String sessionUid = session.getMetadata().getUid();
                    live.add(sessionUid);
                    Optional<String> podUid = currentPodUid(session);
                    if (podUid.isEmpty()) {
                        continue;
                    }
                    String previous = seenPods.put(sessionUid, podUid.get());
                    if (previous != null && !previous.equals(podUid.get())) {
                        LOGGER.info(formatLogMessage(correlationId, "Pod of session " + session.getSpec().getName()
                                + " was replaced, scheduling data bridge injection"));
                        asyncDataInjector.scheduleInjection(null, session,
                                sessionEnvCollector.collect(session, correlationId), correlationId);
                    }
                } catch (RuntimeException e) {
                    LOGGER.warn(formatLogMessage(correlationId,
                            "Could not check the pod of session " + session.getSpec().getName()), e);
                    // Keep the old record, so the next run still sees the change.
                    if (session.getMetadata().getUid() != null) {
                        live.add(session.getMetadata().getUid());
                    }
                }
            }
            seenPods.keySet().retainAll(live);
        } catch (RuntimeException e) {
            LOGGER.warn(formatLogMessage(correlationId, "Could not check session pods for data bridge injection"), e);
        }
    }

    /** The newest pod behind the session's Service that is not terminating. Eager and lazy sessions both have one. */
    private Optional<String> currentPodUid(Session session) {
        Optional<Service> service = client.getServiceFromSession(session);
        if (service.isEmpty() || service.get().getSpec().getSelector() == null
                || service.get().getSpec().getSelector().isEmpty()) {
            return Optional.empty();
        }
        return client.kubernetes().pods().inNamespace(client.namespace()).withLabels(service.get().getSpec().getSelector())
                .list().getItems().stream().filter(pod -> pod.getMetadata().getDeletionTimestamp() == null)
                .max(Comparator.comparing((Pod pod) -> pod.getMetadata().getCreationTimestamp()))
                .map(pod -> pod.getMetadata().getUid());
    }

    public void resume(String correlationId) {
        try {
            int resumed = 0;
            for (Session session : client.sessions().list()) {
                if (!isBridgeSession(session)) {
                    continue;
                }
                asyncDataInjector.scheduleInjection(null, session, sessionEnvCollector.collect(session, correlationId),
                        correlationId);
                resumed++;
            }
            LOGGER.info(formatLogMessage(correlationId, "Resumed " + resumed + " data bridge injection(s)"));
        } catch (RuntimeException e) {
            LOGGER.warn(formatLogMessage(correlationId, "Could not resume data bridge injections"), e);
        }
    }
}
