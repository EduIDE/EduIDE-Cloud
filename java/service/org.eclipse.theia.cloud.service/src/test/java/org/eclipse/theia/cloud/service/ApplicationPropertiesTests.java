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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link org.eclipse.theia.cloud.service.ApplicationProperties}
 */
class ApplicationPropertiesTests {

    private static final String THEIACLOUD_USE_KEYCLOAK = "theia.cloud.use.keycloak";

    @Test
    void isUseKeycloak_propertyTrue_returnTrue() {
        System.setProperty(THEIACLOUD_USE_KEYCLOAK, "true");
        ApplicationProperties fixture = new ApplicationProperties();
        assertTrue(fixture.isUseKeycloak());
    }

    @Test
    void isUseKeycloak_propertyNotSet_returnTrue() {
        System.clearProperty(THEIACLOUD_USE_KEYCLOAK);
        ApplicationProperties fixture = new ApplicationProperties();
        assertTrue(fixture.isUseKeycloak());
    }

    @Test
    void isUseKeycloak_propertyFalse_returnFalse() {
        System.setProperty(THEIACLOUD_USE_KEYCLOAK, "false");
        ApplicationProperties fixture = new ApplicationProperties();
        assertFalse(fixture.isUseKeycloak());
    }

    @Test
    void isUseKeycloak_propertySetToSomeValue_returnTrue() {
        System.setProperty(THEIACLOUD_USE_KEYCLOAK, "asdasd");
        ApplicationProperties fixture = new ApplicationProperties();
        assertTrue(fixture.isUseKeycloak());
    }

    @Test
    void getAdminGroupName_propertyNotSet_returnDefault() {
        System.clearProperty("theia.cloud.auth.admin.group");
        ApplicationProperties fixture = new ApplicationProperties();
        assertEquals("theia-cloud/admin", fixture.getAdminGroupName());
    }

    @Test
    void getAdminGroupName_propertySet_returnValue() {
        System.setProperty("theia.cloud.auth.admin.group", "test-admin-group");
        ApplicationProperties fixture = new ApplicationProperties();
        assertEquals("test-admin-group", fixture.getAdminGroupName());
    }

    @Test
    void isAdminApiEnabled_notSet_returnFalse() {
        System.clearProperty("theia.cloud.admin.api.enabled");
        System.setProperty("theia.cloud.service.auth.token", "auth-token");
        ApplicationProperties fixture = new ApplicationProperties(env -> null);
        assertFalse(fixture.isAdminApiEnabled());
    }

    @Test
    void isAdminApiEnabled_envTrue_returnTrue() {
        System.clearProperty("theia.cloud.admin.api.enabled");
        System.setProperty("theia.cloud.service.auth.token", "auth-token");
        ApplicationProperties fixture = new ApplicationProperties(
                env -> "THEIA_CLOUD_ADMIN_API_ENABLED".equals(env) ? "true" : null);
        assertTrue(fixture.isAdminApiEnabled());
    }

    @Test
    void getServiceAuthToken_newPropertySet_returnValue() {
        System.clearProperty("theia.cloud.app.id");
        System.setProperty("theia.cloud.service.auth.token", "new-auth-token");
        ApplicationProperties fixture = new ApplicationProperties();
        assertEquals("new-auth-token", fixture.getServiceAuthToken());
    }

    @Test
    void getServiceAuthToken_onlyOldPropertySet_returnValue() {
        System.clearProperty("theia.cloud.service.auth.token");
        System.setProperty("theia.cloud.app.id", "old-app-id");
        ApplicationProperties fixture = new ApplicationProperties(env -> null);
        assertEquals("old-app-id", fixture.getServiceAuthToken());
    }

    @Test
    void getServiceAuthToken_bothPropertiesSet_newPropertyTakesPrecedence() {
        System.setProperty("theia.cloud.service.auth.token", "new-auth-token");
        System.setProperty("theia.cloud.app.id", "old-app-id");
        ApplicationProperties fixture = new ApplicationProperties(env -> null);
        assertEquals("new-auth-token", fixture.getServiceAuthToken());
    }

    @Test
    void getServiceAuthToken_noPropertiesSet_failsInsteadOfUsingDefault() {
        System.clearProperty("theia.cloud.service.auth.token");
        System.clearProperty("theia.cloud.app.id");
        assertThrows(IllegalStateException.class, () -> new ApplicationProperties(name -> null));
    }

    @Test
    void getServiceAuthToken_blankProperty_failsInsteadOfUsingEmptyToken() {
        System.setProperty("theia.cloud.service.auth.token", " ");
        System.clearProperty("theia.cloud.app.id");
        assertThrows(IllegalStateException.class, () -> new ApplicationProperties(name -> null));
    }

    @Test
    void getServiceAuthToken_onlyEnvironmentSet_returnValue() {
        System.clearProperty("theia.cloud.service.auth.token");
        ApplicationProperties fixture = new ApplicationProperties(
                name -> "THEIA_CLOUD_SERVICE_AUTH_TOKEN".equals(name) ? "env-token" : null);
        assertEquals("env-token", fixture.getServiceAuthToken());
    }

    @Test
    void getAdminApiToken_onlyEnvironmentSet_returnValue() {
        System.setProperty("theia.cloud.service.auth.token", "t");
        System.clearProperty("theia.cloud.admin.api.token");
        ApplicationProperties fixture = new ApplicationProperties(
                name -> "THEIA_CLOUD_ADMIN_API_TOKEN".equals(name) ? "admin-env" : null);
        assertEquals("admin-env", fixture.getAdminApiToken());
    }

    @Test
    void getSessionsPerUser_notSetOrInvalid_noLimit() {
        System.setProperty("theia.cloud.service.auth.token", "t");
        System.clearProperty("theia.cloud.sessions.per.user");
        assertEquals(0, new ApplicationProperties().getSessionsPerUser());
        System.setProperty("theia.cloud.sessions.per.user", "many");
        assertEquals(0, new ApplicationProperties().getSessionsPerUser());
        System.clearProperty("theia.cloud.sessions.per.user");
    }

    @Test
    void getSessionsPerUser_set_returnValue() {
        System.setProperty("theia.cloud.service.auth.token", "t");
        System.setProperty("theia.cloud.sessions.per.user", "2");
        assertEquals(2, new ApplicationProperties().getSessionsPerUser());
        System.clearProperty("theia.cloud.sessions.per.user");
    }

    @Test
    void getAppId_deprecatedMethod_returnsSameAsNewMethod() {
        System.setProperty("theia.cloud.service.auth.token", "test-token");
        ApplicationProperties fixture = new ApplicationProperties();
        assertEquals(fixture.getServiceAuthToken(), fixture.getAppId());
    }

    @Test
    void adminApiToken_emptyByDefault() {
        System.clearProperty("theia.cloud.admin.api.token");
        ApplicationProperties fixture = new ApplicationProperties();
        assertTrue(fixture.getAdminApiToken().isEmpty());
    }

    @Test
    void adminApiToken_trimWhitespace() {
        System.setProperty("theia.cloud.admin.api.token", " token-a ");
        ApplicationProperties fixture = new ApplicationProperties();
        assertEquals("token-a", fixture.getAdminApiToken());
    }
}
