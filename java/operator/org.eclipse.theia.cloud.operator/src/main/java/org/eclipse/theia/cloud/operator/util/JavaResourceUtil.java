/********************************************************************************
 * Copyright (C) 2022 EclipseSource, Lockular, Ericsson, STMicroelectronics and 
 * others.
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

import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatLogMessage;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class JavaResourceUtil {

    private static final String TEMPLATES = "/templates";
    private static final Logger LOGGER = LogManager.getLogger(JavaResourceUtil.class);

    private JavaResourceUtil() {
    }

    public static String readResourceAndReplacePlaceholders(String resourceName, Map<String, String> replacements,
            String correlationId) throws IOException, URISyntaxException {
        try (InputStream inputStream = getInputStream(resourceName, correlationId)) {
            String template = new BufferedReader(new InputStreamReader(inputStream)).lines().parallel()
                    .collect(Collectors.joining("\n"));
            // Never log template contents or replacement values: they include the session secret.
            LOGGER.trace(formatLogMessage(correlationId, "Replacing placeholders in " + resourceName));
            template = replacePlaceholders(template, replacements);
            LOGGER.trace(formatLogMessage(correlationId, "Replacement finished for " + resourceName));
            return template;
        }
    }

    /**
     * Replaces all placeholders in one pass, so a value that contains the name of another placeholder is never
     * rewritten. The longest key wins when one key is a prefix of another.
     */
    static String replacePlaceholders(String template, Map<String, String> replacements) {
        if (replacements.isEmpty()) {
            return template;
        }
        String alternation = replacements.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote).collect(Collectors.joining("|"));
        Matcher matcher = Pattern.compile(alternation).matcher(template);
        return matcher.replaceAll(match -> {
            String value = replacements.get(match.group());
            return Matcher.quoteReplacement(value != null ? value : "");
        });
    }

    protected static InputStream getInputStream(String resourceName, String correlationId)
            throws FileNotFoundException {
        /* check if template is overridden */
        File file = Paths.get(TEMPLATES, resourceName).toFile();
        if (file.exists()) {
            LOGGER.info(
                    formatLogMessage(correlationId, "Updating custom template read from " + file.getAbsolutePath()));
            return new FileInputStream(file);
        }

        LOGGER.trace(formatLogMessage(correlationId, "Updating template read with classloader from " + resourceName));
        return JavaResourceUtil.class.getResourceAsStream(resourceName);
    }

}
