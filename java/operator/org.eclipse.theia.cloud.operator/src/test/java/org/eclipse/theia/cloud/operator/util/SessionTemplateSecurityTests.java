/********************************************************************************
 * Copyright (C) 2026 TUM and others.
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
package org.eclipse.theia.cloud.operator.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.eclipse.theia.cloud.operator.TheiaCloudOperatorArguments;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import picocli.CommandLine;

/**
 * oauth2-proxy matches a skip-auth route as an unanchored regular expression against the whole path, so any route that
 * is not anchored lets requests skip the login.
 */
class SessionTemplateSecurityTests {

    private static final Pattern SKIP_AUTH_ROUTE = Pattern.compile("--skip-auth-route=(\\S+)");

    @ParameterizedTest
    @ValueSource(strings = { "src/main/resources/templateDeployment.yaml",
            "../../../demo/dockerfiles/demo-operator-custom-template/templateDeployment.yaml" })
    void skipAuthRoutesAreAnchored(String template) throws IOException {
        Path path = Paths.get(template);
        assumeTrue(Files.exists(path), template + " is not part of this checkout");

        Matcher matcher = SKIP_AUTH_ROUTE.matcher(Files.readString(path));
        while (matcher.find()) {
            String route = matcher.group(1);
            assertTrue(route.startsWith("^") && route.endsWith("$"), "unanchored skip-auth route: " + route);
        }
    }

    @Test
    void operatorTemplateHasNoSkipAuthRoute() throws IOException {
        assertFalse(Files.readString(Paths.get("src/main/resources/templateDeployment.yaml")).contains("--skip-auth-route"));
    }

    @Test
    void oAuth2ProxyVersionDefaultIsPinned() {
        TheiaCloudOperatorArguments arguments = new TheiaCloudOperatorArguments();
        new CommandLine(arguments).parseArgs("--instancesHost", "example.org");

        assertEquals("v7.12.0", arguments.getOAuth2ProxyVersion());
    }

    @Test
    void templateReplacementDoesNotLogItsContentOrValues() throws Exception {
        List<String> messages = new ArrayList<>();
        AbstractAppender appender = new AbstractAppender("capture", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        Logger logger = (Logger) LogManager.getLogger(JavaResourceUtil.class);
        Level previous = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(Level.TRACE);
        try {
            String result = JavaResourceUtil.readResourceAndReplacePlaceholders("/templateDeployment.yaml",
                    Map.of("placeholder-env-session-secret", "value-that-must-not-be-logged"), "corr");

            assertTrue(result.contains("value-that-must-not-be-logged"));
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previous);
        }

        assertFalse(messages.isEmpty());
        assertTrue(messages.stream().noneMatch(m -> m.contains("value-that-must-not-be-logged")), messages.toString());
        assertTrue(messages.stream().noneMatch(m -> m.contains("THEIACLOUD_SESSION_SECRET")), messages.toString());
    }

    @Test
    void replacementValuesAreNotScannedForOtherPlaceholders() {
        String result = JavaResourceUtil.replacePlaceholders("user: placeholder-a, other: placeholder-b",
                new java.util.LinkedHashMap<>(Map.of("placeholder-a", "x-placeholder-b-$1\\y", "placeholder-b", "B")));

        assertEquals("user: x-placeholder-b-$1\\y, other: B", result);
    }

    @Test
    void longestPlaceholderWins() {
        assertEquals("1 2", JavaResourceUtil.replacePlaceholders("placeholder placeholder-x",
                Map.of("placeholder", "1", "placeholder-x", "2")));
    }
}
