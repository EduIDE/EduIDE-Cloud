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

import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.OperatorStatus;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.util.DataBridgeUtil;
import org.eclipse.theia.cloud.operator.util.SessionEnvCollector;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Injections are scheduled in memory, so one that was still pending when the operator stopped is lost, and a session
 * that is already handled is not processed again. After a start, this schedules an injection for every handled
 * session of an app with the data bridge. The bridge replaces what it stores, so repeating one is harmless.
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

    public void resume(String correlationId) {
        try {
            int resumed = 0;
            for (Session session : client.sessions().list()) {
                if (!OperatorStatus.HANDLED.equals(session.getNonNullStatus().getOperatorStatus())
                        || session.getMetadata().getDeletionTimestamp() != null) {
                    continue;
                }
                Optional<AppDefinition> appDef = client.appDefinitions().get(session.getSpec().getAppDefinition());
                if (appDef.isEmpty() || !DataBridgeUtil.isDataBridgeEnabled(appDef.get().getSpec())) {
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
