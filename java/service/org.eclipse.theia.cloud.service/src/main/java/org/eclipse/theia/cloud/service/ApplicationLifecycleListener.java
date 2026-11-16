/********************************************************************************
 * Copyright (C) 2022 EclipseSource and others.
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

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.jboss.logging.Logger;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.sentry.Sentry;

@ApplicationScoped
public class ApplicationLifecycleListener {
    protected Logger logger;

    @Inject
    ApplicationProperties applicationProperties;

    protected void onStart(@Observes StartupEvent event) {
        logger = Logger.getLogger(getClass());
        Sentry.configureScope(scope -> {
            scope.setTag("component", "service");
        });
        // The bean is created lazily. Reading it here makes a missing service auth token stop the start.
        applicationProperties.getServiceAuthToken();
        logConfiguration();
    }

    /** Logs where configuration comes from, never the values: they hold credentials. */
    private void logConfiguration() {
        for (ConfigSource configSource : ConfigProvider.getConfig().getConfigSources()) {
            logger.info(configSource.getName() + " (" + configSource.getOrdinal() + ")");
        }
    }

}
