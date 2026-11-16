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

import java.util.function.Function;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Injectable bean providing access to the service's configurable application properties.
 */
@ApplicationScoped
public class ApplicationProperties {

    private static final String THEIACLOUD_SERVICE_AUTH_TOKEN = "theia.cloud.service.auth.token";
    private static final String THEIACLOUD_APP_ID = "theia.cloud.app.id"; // Deprecated - use
                                                                          // THEIACLOUD_SERVICE_AUTH_TOKEN
    private static final String THEIACLOUD_USE_KEYCLOAK = "theia.cloud.use.keycloak";
    private static final String THEIACLOUD_ADMIN_GROUP_NAME = "theia.cloud.auth.admin.group";
    private static final String THEIACLOUD_ADMIN_API_TOKEN = "theia.cloud.admin.api.token";
    private static final String THEIACLOUD_ADMIN_API_ENABLED = "theia.cloud.admin.api.enabled";

    private static final String THEIACLOUD_SESSIONS_PER_USER = "theia.cloud.sessions.per.user";

    private static final String DEFAULT_ADMIN_GROUP_NAME = "theia-cloud/admin";

    private final Logger logger;

    private final boolean useKeycloak;
    private final String serviceAuthToken;
    private final String adminGroupName;
    private final String adminApiToken;
    private final boolean adminApiEnabled;
    private final int sessionsPerUser;

    private final Function<String, String> environment;

    public ApplicationProperties() {
        this(System::getenv);
    }

    ApplicationProperties(Function<String, String> environment) {
        this.environment = environment;
        logger = Logger.getLogger(getClass());
        serviceAuthToken = getServiceAuthTokenWithFallback();
        adminGroupName = System.getProperty(THEIACLOUD_ADMIN_GROUP_NAME, DEFAULT_ADMIN_GROUP_NAME);
        adminApiToken = getAdminApiTokenFromConfig();
        // Off unless explicitly enabled: nothing calls the admin API today.
        adminApiEnabled = "true".equalsIgnoreCase(read(THEIACLOUD_ADMIN_API_ENABLED));
        sessionsPerUser = parseSessionsPerUser();
        // Only disable keycloak if the value was explicitly set to exactly "false".
        useKeycloak = !"false".equals(System.getProperty(THEIACLOUD_USE_KEYCLOAK));
        if (!useKeycloak) {
            logger.warn("Keycloak integration was disabled. Anonymous requests are allowed!");
        }
    }

    /**
     * @return the configured service auth token
     */
    public String getServiceAuthToken() {
        return serviceAuthToken;
    }

    /**
     * @deprecated Use {@link #getServiceAuthToken()} instead. This method is maintained for backwards compatibility.
     * @return the configured service auth token
     */
    @Deprecated(since = "1.2.0", forRemoval = true)
    public String getAppId() {
        return serviceAuthToken;
    }

    /**
     * @return true if the service uses keycloak for authn and authz or false if anonymous users are allowed.
     */
    public boolean isUseKeycloak() {
        return useKeycloak;
    }

    /**
     * @return the maximum number of active sessions per user, or 0 if there is no limit
     */
    public int getSessionsPerUser() {
        return sessionsPerUser;
    }

    /**
     * @return the group name that identifies admin users in the MicroProfile JWT token's groups claim
     */
    public String getAdminGroupName() {
        return adminGroupName;
    }

    /**
     * @return configured admin API token or an empty string if not configured.
     */
    public String getAdminApiToken() {
        return adminApiToken;
    }

    /**
     * @return true if the admin API token protected endpoints are enabled. Defaults to false.
     */
    public boolean isAdminApiEnabled() {
        return adminApiEnabled;
    }

    /**
     * Get the service auth token with fallback to deprecated app id property. Logs a deprecation warning if the old
     * property is used.
     */
    private String getServiceAuthTokenWithFallback() {
        String serviceAuthToken = read(THEIACLOUD_SERVICE_AUTH_TOKEN);
        if (serviceAuthToken != null) {
            return serviceAuthToken;
        }

        String appId = read(THEIACLOUD_APP_ID);
        if (appId != null) {
            logger.warn("Using deprecated property '" + THEIACLOUD_APP_ID + "'. " + "Please migrate to '"
                    + THEIACLOUD_SERVICE_AUTH_TOKEN + "' in your configuration.");
            return appId;
        }

        throw new IllegalStateException("No service auth token configured. Set '" + THEIACLOUD_SERVICE_AUTH_TOKEN
                + "' (or the environment variable " + toEnvName(THEIACLOUD_SERVICE_AUTH_TOKEN) + ").");
    }

    private String getAdminApiTokenFromConfig() {
        String token = read(THEIACLOUD_ADMIN_API_TOKEN);
        return token == null ? "" : token.trim();
    }

    private int parseSessionsPerUser() {
        String value = read(THEIACLOUD_SESSIONS_PER_USER);
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            logger.warn("Ignoring invalid value for '" + THEIACLOUD_SESSIONS_PER_USER + "'. No session limit applies.");
            return 0;
        }
    }

    /**
     * Reads a property from the JVM system properties and falls back to the environment variable of the same name
     * (upper case, dots as underscores). Secrets use the environment so they stay off the process command line. Blank
     * values count as not set.
     */
    private String read(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = environment.apply(toEnvName(property));
        }
        return value == null || value.isBlank() ? null : value;
    }

    private static String toEnvName(String property) {
        return property.toUpperCase().replace('.', '_');
    }
}
