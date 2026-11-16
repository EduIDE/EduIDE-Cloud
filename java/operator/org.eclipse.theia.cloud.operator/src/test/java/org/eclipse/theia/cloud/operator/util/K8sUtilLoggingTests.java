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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;

/**
 * The rendered Deployment of a session carries env values such as VCS and Artemis tokens. They must not reach the
 * operator log at any level.
 */
class K8sUtilLoggingTests {

    private static final String SECRET = "secret-token-value-that-must-not-be-logged";

    private static final String YAML = String.join("\n", "apiVersion: apps/v1", "kind: Deployment", "metadata:",
            "  name: my-deployment", "spec:", "  template:", "    metadata:", "      name: pod", "    spec:",
            "      containers:", "      - name: ide", "        image: img", "        env:", "        - name: GIT_URI",
            "          value: https://user:" + SECRET + "@git.example.com/repo.git", "        - name: ARTEMIS_TOKEN",
            "          value: " + SECRET);

    @Test
    @SuppressWarnings("unchecked")
    void creatingADeploymentDoesNotLogEnvValues() throws Exception {
        LoggerContext context = LoggerContext.getContext(false);
        List<String> messages = new ArrayList<>();
        AbstractAppender appender = new AbstractAppender("k8sutil-capture", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        LoggerConfig loggerConfig = context.getConfiguration().getLoggerConfig(K8sUtil.class.getName());
        Level oldLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.ALL, null);
        loggerConfig.setLevel(Level.ALL);
        context.updateLoggers();
        try {
            Deployment deployment = io.fabric8.kubernetes.client.utils.Serialization
                    .unmarshal(YAML, Deployment.class);
            RollableScalableResource<Deployment> resource = mock(RollableScalableResource.class);
            when(resource.item()).thenReturn(deployment);
            NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> items = mock(
                    NonNamespaceOperation.class);
            when(items.load(any(InputStream.class))).thenAnswer(i -> resource);
            when(items.resource(any(Deployment.class))).thenReturn(mock(RollableScalableResource.class));
            MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments = mock(
                    MixedOperation.class);
            when(deployments.inNamespace("ns")).thenReturn(items);
            AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
            when(apps.deployments()).thenReturn(deployments);
            NamespacedKubernetesClient client = mock(NamespacedKubernetesClient.class);
            when(client.apps()).thenReturn(apps);

            K8sUtil.loadAndCreateDeploymentWithOwnerReference(client, "ns", "cor", YAML, "v1", "Session", "owner",
                    "uid", 0, Map.of(), d -> {
                    });

            assertTrue(messages.stream().anyMatch(m -> m.contains("my-deployment")), messages.toString());
            assertFalse(messages.stream().anyMatch(m -> m.contains(SECRET)), messages.toString());
        } finally {
            loggerConfig.removeAppender("k8sutil-capture");
            loggerConfig.setLevel(oldLevel);
            context.updateLoggers();
            appender.stop();
        }
    }
}
