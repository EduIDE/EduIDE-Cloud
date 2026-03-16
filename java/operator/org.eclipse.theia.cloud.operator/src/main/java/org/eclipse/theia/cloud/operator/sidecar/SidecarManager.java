/********************************************************************************
 * Copyright (C) 2025 EclipseSource and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.theia.cloud.operator.sidecar;

import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatLogMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinitionSpec;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.tracing.Tracing;
import org.eclipse.theia.cloud.operator.util.TheiaCloudPersistentVolumeUtil;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.sentry.ISpan;
import io.sentry.SpanStatus;

@Singleton
public class SidecarManager {

    private static final Logger LOGGER = LogManager.getLogger(SidecarManager.class);

    @Inject
    private SidecarResourceFactory factory;

    /**
     * Returns true if the given AppDefinition has sidecar configuration.
     */
    public boolean hasSidecars(AppDefinition appDef) {
        return SidecarConfig.hasSidecars(appDef);
    }

    /**
     * Returns the list of sidecar configurations for the given AppDefinition.
     */
    public List<SidecarConfig> getSidecarConfigs(AppDefinition appDef) {
        return SidecarConfig.getSidecarConfigs(appDef);
    }

    /**
     * Creates all sidecar resources (Service + Deployment per sidecar) for a lazy session.
     * On failure of any sidecar, rolls back all previously created sidecars for this session.
     *
     * @return true if all sidecars were created (or none were needed), false on any failure
     */
    public boolean createSidecars(
            Session session,
            AppDefinition appDef,
            Optional<String> pvcName,
            String correlationId) {

        List<SidecarConfig> configs = getSidecarConfigs(appDef);
        if (configs.isEmpty()) {
            LOGGER.debug(formatLogMessage(correlationId, "[Sidecar] No sidecars configured for " + appDef.getSpec().getName()));
            return true;
        }

        ISpan span = Tracing.childSpan("sidecar.create_all", "Create all sidecars");
        span.setData("session", session.getMetadata().getName());
        span.setData("sidecar_count", configs.size());

        LOGGER.info(formatLogMessage(correlationId,
            "[Sidecar] Creating " + configs.size() + " sidecar(s) for session " + session.getMetadata().getName()));

        List<SidecarConfig> created = new ArrayList<>();
        try {
            for (SidecarConfig config : configs) {
                ISpan sidecarSpan = Tracing.childSpan(span, "sidecar.create", "Create sidecar " + config.name());
                sidecarSpan.setData("sidecar_name", config.name());
                sidecarSpan.setData("image", config.image());

                boolean serviceOk = factory.createService(session, config, correlationId).isPresent();
                if (!serviceOk) {
                    sidecarSpan.setTag("outcome", "service_failed");
                    Tracing.finish(sidecarSpan, SpanStatus.INTERNAL_ERROR);
                    throw new RuntimeException("Failed to create sidecar service for " + config.name());
                }

                boolean deploymentOk = factory.createDeployment(session, config, pvcName, appDef.getSpec(), correlationId).isPresent();
                if (!deploymentOk) {
                    sidecarSpan.setTag("outcome", "deployment_failed");
                    Tracing.finish(sidecarSpan, SpanStatus.INTERNAL_ERROR);
                    throw new RuntimeException("Failed to create sidecar deployment for " + config.name());
                }

                Tracing.finishSuccess(sidecarSpan);
                created.add(config);
            }

            Tracing.finishSuccess(span);
            LOGGER.info(formatLogMessage(correlationId, "[Sidecar] Successfully created all sidecars"));
            return true;

        } catch (Exception e) {
            LOGGER.error(formatLogMessage(correlationId,
                "[Sidecar] Error creating sidecars, rolling back " + created.size() + " created sidecar(s)"), e);

            // Rollback: delete all resources created so far
            try {
                factory.deleteResources(session, correlationId);
            } catch (Exception rollbackEx) {
                LOGGER.error(formatLogMessage(correlationId, "[Sidecar] Rollback failed"), rollbackEx);
            }

            Tracing.finishError(span, e);
            return false;
        }
    }

    /**
     * Injects sidecar environment variables into a Theia deployment that is being created.
     * Adds per-sidecar HOST/PORT env vars plus a SIDECAR_CONFIG JSON array.
     *
     * Called at Theia deployment creation time (before the deployment is submitted to K8s),
     * so the Theia pod starts with env vars already set — no restart needed.
     */
    public void injectSidecarEnvVars(
            Deployment theiaDeployment,
            AppDefinition appDef,
            String correlationId) {

        List<SidecarConfig> configs = getSidecarConfigs(appDef);
        if (configs.isEmpty()) {
            LOGGER.debug(formatLogMessage(correlationId, "[Sidecar] No sidecars configured, skipping env var injection"));
            return;
        }

        ISpan span = Tracing.childSpan("sidecar.inject_env", "Inject sidecar env vars");
        span.setData("sidecar_count", configs.size());

        try {
            AppDefinitionSpec appDefSpec = appDef.getSpec();
            PodSpec podSpec = theiaDeployment.getSpec().getTemplate().getSpec();
            Container container = TheiaCloudPersistentVolumeUtil.getTheiaContainer(podSpec, appDefSpec);

            if (container == null) {
                LOGGER.error(formatLogMessage(correlationId, "[Sidecar] Could not find Theia container in deployment"));
                span.setTag("outcome", "container_not_found");
                Tracing.finish(span, SpanStatus.NOT_FOUND);
                return;
            }

            List<EnvVar> envVars = container.getEnv();
            if (envVars == null) {
                envVars = new ArrayList<>();
                container.setEnv(envVars);
            }

            // Build SIDECAR_CONFIG JSON and per-sidecar env vars
            StringBuilder configJson = new StringBuilder("[");
            for (int i = 0; i < configs.size(); i++) {
                SidecarConfig config = configs.get(i);
                // Service name is deterministic: <session-deployment-name>-<sidecar-name>
                // But at this point we use the same naming the factory uses
                String serviceName = SidecarResourceFactory.getLazyResourceName(theiaDeployment.getMetadata().getName(), config);

                envVars.add(new EnvVarBuilder()
                    .withName(config.hostEnvVar())
                    .withValue(serviceName)
                    .build());
                envVars.add(new EnvVarBuilder()
                    .withName(config.portEnvVar())
                    .withValue(String.valueOf(config.containerPort()))
                    .build());

                if (i > 0) {
                    configJson.append(",");
                }
                configJson.append("{\"name\":\"").append(config.name()).append("\"");
                configJson.append(",\"host\":\"").append(serviceName).append("\"");
                configJson.append(",\"port\":").append(config.containerPort());
                if (config.languages() != null && !config.languages().isEmpty()) {
                    configJson.append(",\"languages\":[");
                    for (int j = 0; j < config.languages().size(); j++) {
                        if (j > 0) configJson.append(",");
                        configJson.append("\"").append(config.languages().get(j)).append("\"");
                    }
                    configJson.append("]");
                }
                configJson.append("}");
            }
            configJson.append("]");

            envVars.add(new EnvVarBuilder()
                .withName("SIDECAR_CONFIG")
                .withValue(configJson.toString())
                .build());

            Tracing.finishSuccess(span);
            LOGGER.info(formatLogMessage(correlationId,
                "[Sidecar] Injected env vars for " + configs.size() + " sidecar(s)"));

        } catch (Exception e) {
            LOGGER.error(formatLogMessage(correlationId, "[Sidecar] Error injecting env vars"), e);
            Tracing.finishError(span, e);
        }
    }

    /**
     * Injects sidecar env vars for a prewarmed (eager) deployment.
     * Service names are deterministic from appDef + instanceId + sidecar name.
     */
    public void injectPrewarmedSidecarEnvVars(
            Deployment theiaDeployment,
            AppDefinition appDef,
            int instanceId,
            String correlationId) {

        List<SidecarConfig> configs = getSidecarConfigs(appDef);
        if (configs.isEmpty()) {
            return;
        }

        ISpan span = Tracing.childSpan("sidecar.inject_prewarmed_env", "Inject prewarmed sidecar env vars");

        try {
            AppDefinitionSpec appDefSpec = appDef.getSpec();
            PodSpec podSpec = theiaDeployment.getSpec().getTemplate().getSpec();
            Container container = TheiaCloudPersistentVolumeUtil.getTheiaContainer(podSpec, appDefSpec);

            if (container == null) {
                LOGGER.error(formatLogMessage(correlationId, "[Sidecar] Could not find Theia container in prewarmed deployment"));
                Tracing.finish(span, SpanStatus.NOT_FOUND);
                return;
            }

            List<EnvVar> envVars = container.getEnv();
            if (envVars == null) {
                envVars = new ArrayList<>();
                container.setEnv(envVars);
            }

            StringBuilder configJson = new StringBuilder("[");
            for (int i = 0; i < configs.size(); i++) {
                SidecarConfig config = configs.get(i);
                String serviceName = SidecarResourceFactory.getPrewarmedResourceName(appDef, instanceId, config);

                envVars.add(new EnvVarBuilder()
                    .withName(config.hostEnvVar())
                    .withValue(serviceName)
                    .build());
                envVars.add(new EnvVarBuilder()
                    .withName(config.portEnvVar())
                    .withValue(String.valueOf(config.containerPort()))
                    .build());

                if (i > 0) configJson.append(",");
                configJson.append("{\"name\":\"").append(config.name()).append("\"");
                configJson.append(",\"host\":\"").append(serviceName).append("\"");
                configJson.append(",\"port\":").append(config.containerPort());
                if (config.languages() != null && !config.languages().isEmpty()) {
                    configJson.append(",\"languages\":[");
                    for (int j = 0; j < config.languages().size(); j++) {
                        if (j > 0) configJson.append(",");
                        configJson.append("\"").append(config.languages().get(j)).append("\"");
                    }
                    configJson.append("]");
                }
                configJson.append("}");
            }
            configJson.append("]");

            envVars.add(new EnvVarBuilder()
                .withName("SIDECAR_CONFIG")
                .withValue(configJson.toString())
                .build());

            Tracing.finishSuccess(span);

        } catch (Exception e) {
            LOGGER.error(formatLogMessage(correlationId, "[Sidecar] Error injecting prewarmed env vars"), e);
            Tracing.finishError(span, e);
        }
    }

    /**
     * Deletes all sidecar resources for a lazy session.
     */
    public void deleteSidecars(Session session, String correlationId) {
        factory.deleteResources(session, correlationId);
    }

    /**
     * Deletes all prewarmed sidecar resources for a specific instance of an AppDefinition.
     */
    public void deletePrewarmedSidecars(AppDefinition appDef, int instanceId, String correlationId) {
        List<SidecarConfig> configs = getSidecarConfigs(appDef);
        for (SidecarConfig config : configs) {
            factory.deletePrewarmedResources(appDef, instanceId, config, correlationId);
        }
    }

    /**
     * Creates prewarmed sidecar resources (Service + Deployment) for a single pool instance.
     * Called during pool creation / reconciliation.
     */
    public boolean createPrewarmedSidecars(
            AppDefinition appDef,
            int instanceId,
            Optional<String> pvcName,
            Map<String, String> additionalLabels,
            String correlationId) {

        List<SidecarConfig> configs = getSidecarConfigs(appDef);
        if (configs.isEmpty()) {
            return true;
        }

        ISpan span = Tracing.childSpan("sidecar.create_prewarmed", "Create prewarmed sidecars");
        span.setData("instance_id", instanceId);
        span.setData("sidecar_count", configs.size());

        boolean success = true;
        for (SidecarConfig config : configs) {
            success &= factory.createPrewarmedService(appDef, instanceId, config, additionalLabels, correlationId).isPresent();
            success &= factory.createPrewarmedDeployment(appDef, instanceId, config, pvcName, additionalLabels, correlationId).isPresent();
        }

        span.setTag("outcome", success ? "success" : "failure");
        Tracing.finish(span, success ? SpanStatus.OK : SpanStatus.INTERNAL_ERROR);
        return success;
    }

    /**
     * Ensures prewarmed sidecar capacity exists for the given number of instances.
     */
    public boolean ensurePrewarmedSidecarCapacity(
            AppDefinition appDef,
            int minInstances,
            Map<String, String> labels,
            String correlationId) {

        List<SidecarConfig> configs = getSidecarConfigs(appDef);
        if (configs.isEmpty()) {
            return true;
        }

        boolean success = true;
        for (int instance = 1; instance <= minInstances; instance++) {
            for (SidecarConfig config : configs) {
                String deploymentName = SidecarResourceFactory.getPrewarmedResourceName(appDef, instance, config);
                if (factory.getClient().kubernetes().apps().deployments().withName(deploymentName).get() == null) {
                    Optional<String> pvcName = factory.lookupExistingPvc(appDef, instance);
                    success &= factory.createPrewarmedService(appDef, instance, config, labels, correlationId).isPresent();
                    success &= factory.createPrewarmedDeployment(appDef, instance, config, pvcName, labels, correlationId).isPresent();
                }
            }
        }
        return success;
    }

    /**
     * Reconciles prewarmed sidecar resources for the given target count.
     */
    public boolean reconcilePrewarmedSidecars(
            AppDefinition appDef,
            int targetInstances,
            Map<String, String> labels,
            String correlationId) {
        // Same logic as ensureCapacity — create any missing
        return ensurePrewarmedSidecarCapacity(appDef, targetInstances, labels, correlationId);
    }
}
