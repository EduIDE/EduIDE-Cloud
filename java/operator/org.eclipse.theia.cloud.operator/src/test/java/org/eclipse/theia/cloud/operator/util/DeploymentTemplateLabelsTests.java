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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.theia.cloud.common.util.LabelsUtil;
import org.eclipse.theia.cloud.operator.handler.AddedHandlerUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * NetworkPolicies select session pods by these labels. Claiming a prewarmed pod only labels its Services, so the labels
 * have to be on the pod template or prewarmed pods run outside the isolation.
 */
class DeploymentTemplateLabelsTests {

    @ParameterizedTest
    @ValueSource(strings = { AddedHandlerUtil.TEMPLATE_DEPLOYMENT_YAML,
            AddedHandlerUtil.TEMPLATE_DEPLOYMENT_WITHOUT_AOUTH2_PROXY_YAML })
    void podTemplateCarriesTheLabelsNetworkPoliciesSelectOn(String template) throws IOException {
        try (InputStream stream = AddedHandlerUtil.class.getResourceAsStream(template)) {
            assertNotNull(stream, template);
            String yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            Matcher podMetadata = Pattern.compile("\\n  template:\\n(.*?)\\n    spec:", Pattern.DOTALL).matcher(yaml);
            assertTrue(podMetadata.find(), template);
            String metadata = podMetadata.group(1);
            assertTrue(metadata.contains(LabelsUtil.LABEL_KEY_SESSION + ": " + LabelsUtil.LABEL_VALUE_SESSION), metadata);
            assertTrue(metadata.contains(LabelsUtil.LABEL_KEY_THEIACLOUD + ": " + LabelsUtil.LABEL_VALUE_THEIACLOUD),
                    metadata);
        }
    }

    @Test
    void templateWithoutProxyListensOnThePodIp() throws IOException {
        try (InputStream stream = AddedHandlerUtil.class
                .getResourceAsStream(AddedHandlerUtil.TEMPLATE_DEPLOYMENT_WITHOUT_AOUTH2_PROXY_YAML)) {
            assertNotNull(stream);
            assertTrue(new String(stream.readAllBytes(), StandardCharsets.UTF_8).contains("--hostname=0.0.0.0"));
        }
    }
}
