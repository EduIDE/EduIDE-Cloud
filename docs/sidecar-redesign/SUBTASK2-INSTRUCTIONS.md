# Subtask 2 — Operator Core: Sidecar Package Implementation

## Your Mission

Replace the `languageserver/` package with a new `sidecar/` package in the operator module, then update all references across 4 consumer files. Delete the old files and YAML templates when done.

**Working directory**: `/Users/nikolas/BA Workdir/EduIDE-Cloud`
**Branch**: `feature/external-ls-v2` (already checked out)

---

## PHASE 1: Create 3 new files in `sidecar/` package

Base path: `java/operator/org.eclipse.theia.cloud.operator/src/main/java/org/eclipse/theia/cloud/operator/sidecar/`

### File 1: `SidecarConfig.java`

```java
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.SidecarSpec;

/**
 * Immutable configuration for a single sidecar container.
 * Derived from either {@link SidecarSpec} (v1beta11 CRD) or legacy AppDefinition options.
 */
public record SidecarConfig(
    String name,
    String image,
    int containerPort,
    List<String> languages,
    String cpuLimit,
    String memoryLimit,
    String cpuRequest,
    String memoryRequest,
    boolean mountWorkspace
) {

    /** Default port all sidecar containers should use internally. */
    public static final int DEFAULT_PORT = 5000;

    /** Legacy AppDefinition option key for language server image. */
    public static final String OPTION_LS_IMAGE = "langserver-image";

    /**
     * Creates a SidecarConfig from a v1beta11 {@link SidecarSpec}, applying defaults for missing fields.
     */
    public static SidecarConfig fromSpec(SidecarSpec spec) {
        return new SidecarConfig(
            spec.getName(),
            spec.getImage(),
            spec.getPort() > 0 ? spec.getPort() : DEFAULT_PORT,
            spec.getLanguages() != null ? spec.getLanguages() : List.of(),
            spec.getCpuLimit() != null && !spec.getCpuLimit().isBlank() ? spec.getCpuLimit() : "500m",
            spec.getMemoryLimit() != null && !spec.getMemoryLimit().isBlank() ? spec.getMemoryLimit() : "1Gi",
            spec.getCpuRequest() != null && !spec.getCpuRequest().isBlank() ? spec.getCpuRequest() : "100m",
            spec.getMemoryRequest() != null && !spec.getMemoryRequest().isBlank() ? spec.getMemoryRequest() : "256Mi",
            spec.isMountWorkspace()
        );
    }

    /**
     * Creates a SidecarConfig from legacy AppDefinition options map.
     * Used for backward compatibility with v1beta10 AppDefinitions that have
     * {@code options["langserver-image"]} set.
     */
    public static SidecarConfig fromLegacyOptions(Map<String, String> options) {
        return new SidecarConfig(
            "langserver",
            options.get(OPTION_LS_IMAGE),
            DEFAULT_PORT,
            List.of(),
            options.getOrDefault("langserver-cpu-limit", "500m"),
            options.getOrDefault("langserver-memory-limit", "1Gi"),
            options.getOrDefault("langserver-cpu-request", "100m"),
            options.getOrDefault("langserver-memory-request", "256Mi"),
            true
        );
    }

    /**
     * Returns the environment variable name for this sidecar's host.
     * Example: sidecar name "lang-java" → "SIDECAR_LANG_JAVA_HOST"
     */
    public String hostEnvVar() {
        return "SIDECAR_" + name.toUpperCase().replace("-", "_") + "_HOST";
    }

    /**
     * Returns the environment variable name for this sidecar's port.
     * Example: sidecar name "lang-java" → "SIDECAR_LANG_JAVA_PORT"
     */
    public String portEnvVar() {
        return "SIDECAR_" + name.toUpperCase().replace("-", "_") + "_PORT";
    }

    /**
     * Checks if the given AppDefinition has any sidecar configuration
     * (either v1beta11 sidecars list or legacy options).
     */
    public static boolean hasSidecars(AppDefinition appDef) {
        if (appDef == null || appDef.getSpec() == null) {
            return false;
        }
        // Check v1beta11 sidecars list
        List<SidecarSpec> sidecars = appDef.getSpec().getSidecars();
        if (sidecars != null && !sidecars.isEmpty()) {
            return true;
        }
        // Fallback: check legacy options
        Map<String, String> options = appDef.getSpec().getOptions();
        if (options == null) {
            return false;
        }
        String lsImage = options.get(OPTION_LS_IMAGE);
        return lsImage != null && !lsImage.isBlank();
    }

    /**
     * Extracts the list of SidecarConfigs from an AppDefinition.
     * Checks spec.sidecars first; falls back to legacy options.
     */
    public static List<SidecarConfig> getSidecarConfigs(AppDefinition appDef) {
        if (appDef == null || appDef.getSpec() == null) {
            return List.of();
        }
        // v1beta11 path
        List<SidecarSpec> sidecars = appDef.getSpec().getSidecars();
        if (sidecars != null && !sidecars.isEmpty()) {
            return sidecars.stream().map(SidecarConfig::fromSpec).toList();
        }
        // Legacy path
        Map<String, String> options = appDef.getSpec().getOptions();
        if (options != null) {
            String lsImage = options.get(OPTION_LS_IMAGE);
            if (lsImage != null && !lsImage.isBlank()) {
                return List.of(fromLegacyOptions(options));
            }
        }
        return List.of();
    }
}
```

### File 2: `SidecarManager.java`

```java
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
```

### File 3: `SidecarResourceFactory.java`

```java
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinitionSpec;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.tracing.Tracing;
import org.eclipse.theia.cloud.common.util.NamingUtil;
import org.eclipse.theia.cloud.operator.util.TheiaCloudPersistentVolumeUtil;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimVolumeSource;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.sentry.ISpan;
import io.sentry.SpanStatus;

/**
 * Creates Kubernetes Service and Deployment resources for sidecar containers
 * using Fabric8 builders (no YAML templates).
 */
@Singleton
public class SidecarResourceFactory {

    private static final Logger LOGGER = LogManager.getLogger(SidecarResourceFactory.class);

    @Inject
    private TheiaCloudClient client;

    /** Exposed for SidecarManager to check existing resources / lookup PVCs. */
    public TheiaCloudClient getClient() {
        return client;
    }

    // ========== Naming ==========

    /**
     * Lazy session naming: {@code <session-name>-<sidecar-name>}
     */
    public static String getLazyResourceName(Session session, SidecarConfig config) {
        return session.getMetadata().getName() + "-" + config.name();
    }

    /**
     * Overload accepting a deployment name prefix (used during env var injection
     * when the Session object is not available, but the deployment name is).
     * The Theia deployment name for lazy sessions equals the session resource name,
     * so this returns the same result as the Session-based overload.
     */
    public static String getLazyResourceName(String sessionOrDeploymentName, SidecarConfig config) {
        return sessionOrDeploymentName + "-" + config.name();
    }

    /**
     * Prewarmed naming: {@code NamingUtil.createName(appDef, instance, sidecar-name)}
     */
    public static String getPrewarmedResourceName(AppDefinition appDef, int instance, SidecarConfig config) {
        return NamingUtil.createName(appDef, instance, config.name());
    }

    // ========== Lazy Session Resources ==========

    public Optional<Service> createService(Session session, SidecarConfig config, String correlationId) {
        String name = getLazyResourceName(session, config);
        String sessionName = session.getMetadata().getName();
        String sessionUID = session.getMetadata().getUid();

        ISpan span = Tracing.childSpan("sidecar.service.create", "Create sidecar service " + config.name());
        span.setData("service_name", name);

        LOGGER.info(formatLogMessage(correlationId, "[Sidecar] Creating service " + name));

        try {
            Service service = new ServiceBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(client.namespace())
                    .addToLabels("app", name)
                    .addToLabels("theia-cloud.io/sidecar", config.name())
                    .withOwnerReferences(createOwnerReference(Session.API, Session.KIND, sessionName, sessionUID))
                .endMetadata()
                .withNewSpec()
                    .addToSelector("app", name)
                    .addNewPort()
                        .withPort(config.containerPort())
                        .withTargetPort(new IntOrString(config.containerPort()))
                        .withProtocol("TCP")
                    .endPort()
                .endSpec()
                .build();

            Service created = client.kubernetes().services().inNamespace(client.namespace()).resource(service).create();
            Tracing.finishSuccess(span);
            LOGGER.info(formatLogMessage(correlationId, "[Sidecar] Created service " + name));
            return Optional.of(created);

        } catch (Exception e) {
            LOGGER.error(formatLogMessage(correlationId, "[Sidecar] Error creating service " + name), e);
            Tracing.finishError(span, e);
            return Optional.empty();
        }
    }

    public Optional<Deployment> createDeployment(
            Session session,
            SidecarConfig config,
            Optional<String> pvcName,
            AppDefinitionSpec appDefSpec,
            String correlationId) {

        String name = getLazyResourceName(session, config);
        String sessionName = session.getMetadata().getName();
        String sessionUID = session.getMetadata().getUid();

        ISpan span = Tracing.childSpan("sidecar.deployment.create", "Create sidecar deployment " + config.name());
        span.setData("deployment_name", name);
        span.setData("image", config.image());

        LOGGER.info(formatLogMessage(correlationId, "[Sidecar] Creating deployment " + name));

        try {
            Deployment deployment = buildDeployment(name, config,
                createOwnerReference(Session.API, Session.KIND, sessionName, sessionUID));

            if (config.mountWorkspace() && pvcName.isPresent()) {
                addVolumeClaimToDeployment(deployment, pvcName.get(), appDefSpec);
                LOGGER.info(formatLogMessage(correlationId, "[Sidecar] Mounted PVC " + pvcName.get() + " to " + name));
            }

            Deployment created = client.kubernetes().apps().deployments().inNamespace(client.namespace()).resource(deployment).create();
            Tracing.finishSuccess(span);
            LOGGER.info(formatLogMessage(correlationId, "[Sidecar] Created deployment " + name));
            return Optional.of(created);

        } catch (Exception e) {
            LOGGER.error(formatLogMessage(correlationId, "[Sidecar] Error creating deployment " + name), e);
            Tracing.finishError(span, e);
            return Optional.empty();
        }
    }

    // ========== Prewarmed Resources ==========

    public Optional<Service> createPrewarmedService(
            AppDefinition appDef,
            int instance,
            SidecarConfig config,
            Map<String, String> additionalLabels,
            String correlationId) {

        String name = getPrewarmedResourceName(appDef, instance, config);
        String appDefName = appDef.getMetadata().getName();
        String appDefUID = appDef.getMetadata().getUid();

        LOGGER.info(formatLogMessage(correlationId, "[Sidecar] Creating prewarmed service " + name));

        try {
            Service service = new ServiceBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(client.namespace())
                    .addToLabels("app", name)
                    .addToLabels("theia-cloud.io/sidecar", config.name())
                    .addToLabels(additionalLabels)
                    .withOwnerReferences(createOwnerReference(AppDefinition.API, AppDefinition.KIND, appDefName, appDefUID))
                .endMetadata()
                .withNewSpec()
                    .addToSelector("app", name)
                    .addNewPort()
                        .withPort(config.containerPort())
                        .withTargetPort(new IntOrString(config.containerPort()))
                        .withProtocol("TCP")
                    .endPort()
                .endSpec()
                .build();

            Service created = client.kubernetes().services().inNamespace(client.namespace()).resource(service).create();
            LOGGER.info(formatLogMessage(correlationId, "[Sidecar] Created prewarmed service " + name));
            return Optional.of(created);

        } catch (Exception e) {
            LOGGER.error(formatLogMessage(correlationId, "[Sidecar] Error creating prewarmed service " + name), e);
            return Optional.empty();
        }
    }

    public Optional<Deployment> createPrewarmedDeployment(
            AppDefinition appDef,
            int instance,
            SidecarConfig config,
            Optional<String> pvcName,
            Map<String, String> additionalLabels,
            String correlationId) {

        String name = getPrewarmedResourceName(appDef, instance, config);
        String appDefName = appDef.getMetadata().getName();
        String appDefUID = appDef.getMetadata().getUid();

        LOGGER.info(formatLogMessage(correlationId, "[Sidecar] Creating prewarmed deployment " + name));

        try {
            Deployment deployment = buildDeployment(name, config,
                createOwnerReference(AppDefinition.API, AppDefinition.KIND, appDefName, appDefUID));

            // Add additional labels to deployment + pod template
            if (additionalLabels != null && !additionalLabels.isEmpty()) {
                deployment.getMetadata().getLabels().putAll(additionalLabels);
                deployment.getSpec().getTemplate().getMetadata().getLabels().putAll(additionalLabels);
            }

            if (config.mountWorkspace() && pvcName.isPresent()) {
                addVolumeClaimToDeployment(deployment, pvcName.get(), appDef.getSpec());
                LOGGER.info(formatLogMessage(correlationId, "[Sidecar] Mounted PVC " + pvcName.get() + " to prewarmed " + name));
            }

            Deployment created = client.kubernetes().apps().deployments().inNamespace(client.namespace()).resource(deployment).create();
            LOGGER.info(formatLogMessage(correlationId, "[Sidecar] Created prewarmed deployment " + name));
            return Optional.of(created);

        } catch (Exception e) {
            LOGGER.error(formatLogMessage(correlationId, "[Sidecar] Error creating prewarmed deployment " + name), e);
            return Optional.empty();
        }
    }

    // ========== Deletion ==========

    /**
     * Deletes ALL sidecar resources (deployments + services) for a lazy session.
     * Matches by label {@code theia-cloud.io/sidecar}.
     */
    public void deleteResources(Session session, String correlationId) {
        String sessionName = session.getMetadata().getName();

        ISpan span = Tracing.childSpan("sidecar.delete", "Delete sidecar resources");
        span.setData("session", sessionName);

        LOGGER.info(formatLogMessage(correlationId, "[Sidecar] Deleting resources for session " + sessionName));

        try {
            // Delete deployments and services whose name starts with session name + matches sidecar label
            client.kubernetes().apps().deployments().inNamespace(client.namespace())
                .withLabel("theia-cloud.io/sidecar")
                .list().getItems().stream()
                .filter(d -> d.getMetadata().getName().startsWith(sessionName + "-"))
                .forEach(d -> {
                    try {
                        client.kubernetes().apps().deployments().inNamespace(client.namespace()).resource(d).delete();
                    } catch (Exception e) {
                        LOGGER.warn(formatLogMessage(correlationId, "[Sidecar] Failed to delete deployment " + d.getMetadata().getName()), e);
                    }
                });

            client.kubernetes().services().inNamespace(client.namespace())
                .withLabel("theia-cloud.io/sidecar")
                .list().getItems().stream()
                .filter(s -> s.getMetadata().getName().startsWith(sessionName + "-"))
                .forEach(s -> {
                    try {
                        client.kubernetes().services().inNamespace(client.namespace()).resource(s).delete();
                    } catch (Exception e) {
                        LOGGER.warn(formatLogMessage(correlationId, "[Sidecar] Failed to delete service " + s.getMetadata().getName()), e);
                    }
                });

            Tracing.finishSuccess(span);
        } catch (Exception e) {
            LOGGER.error(formatLogMessage(correlationId, "[Sidecar] Error deleting resources for " + sessionName), e);
            Tracing.finishError(span, e);
        }
    }

    /**
     * Deletes prewarmed sidecar resources for a specific sidecar of an instance.
     */
    public void deletePrewarmedResources(AppDefinition appDef, int instance, SidecarConfig config, String correlationId) {
        String name = getPrewarmedResourceName(appDef, instance, config);

        LOGGER.info(formatLogMessage(correlationId, "[Sidecar] Deleting prewarmed resources: " + name));

        try {
            client.kubernetes().apps().deployments().inNamespace(client.namespace()).withName(name).delete();
            client.kubernetes().services().inNamespace(client.namespace()).withName(name).delete();
        } catch (Exception e) {
            LOGGER.error(formatLogMessage(correlationId, "[Sidecar] Error deleting prewarmed resources: " + name), e);
        }
    }

    // ========== PVC Lookup (for SidecarManager) ==========

    /**
     * Looks up an existing PVC for a pool instance (delegates to client).
     */
    public Optional<String> lookupExistingPvc(AppDefinition appDef, int instance) {
        String pvcName = NamingUtil.createNameWithSuffix(appDef, instance, "pvc");
        if (client.persistentVolumeClaimsClient().has(pvcName)) {
            return Optional.of(pvcName);
        }
        return Optional.empty();
    }

    // ========== Private Helpers ==========

    private Deployment buildDeployment(String name, SidecarConfig config, OwnerReference ownerRef) {
        return new DeploymentBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(client.namespace())
                .addToLabels("app", name)
                .addToLabels("theia-cloud.io/sidecar", config.name())
                .withOwnerReferences(ownerRef)
            .endMetadata()
            .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                    .addToMatchLabels("app", name)
                .endSelector()
                .withNewTemplate()
                    .withNewMetadata()
                        .addToLabels("app", name)
                        .addToLabels("theia-cloud.io/sidecar", config.name())
                    .endMetadata()
                    .withNewSpec()
                        .addNewContainer()
                            .withName(config.name())
                            .withImage(config.image())
                            .addNewPort()
                                .withContainerPort(config.containerPort())
                            .endPort()
                            .withNewResources()
                                .addToLimits("cpu", new Quantity(config.cpuLimit()))
                                .addToLimits("memory", new Quantity(config.memoryLimit()))
                                .addToRequests("cpu", new Quantity(config.cpuRequest()))
                                .addToRequests("memory", new Quantity(config.memoryRequest()))
                            .endResources()
                        .endContainer()
                    .endSpec()
                .endTemplate()
            .endSpec()
            .build();
    }

    private OwnerReference createOwnerReference(String apiVersion, String kind, String name, String uid) {
        return new OwnerReferenceBuilder()
            .withApiVersion(apiVersion)
            .withKind(kind)
            .withName(name)
            .withUid(uid)
            .withBlockOwnerDeletion(true)
            .withController(false)
            .build();
    }

    private void addVolumeClaimToDeployment(Deployment deployment, String pvcName, AppDefinitionSpec appDefSpec) {
        io.fabric8.kubernetes.api.model.PodSpec podSpec = deployment.getSpec().getTemplate().getSpec();

        Volume volume = new Volume();
        volume.setName("workspace-data");
        PersistentVolumeClaimVolumeSource pvcSource = new PersistentVolumeClaimVolumeSource();
        pvcSource.setClaimName(pvcName);
        volume.setPersistentVolumeClaim(pvcSource);

        List<Volume> volumes = podSpec.getVolumes();
        if (volumes == null) {
            volumes = new ArrayList<>();
            podSpec.setVolumes(volumes);
        }
        volumes.add(volume);

        Container sidecarContainer = podSpec.getContainers().get(0);

        String mountPath = TheiaCloudPersistentVolumeUtil.getMountPath(appDefSpec);

        VolumeMount volumeMount = new VolumeMount();
        volumeMount.setName("workspace-data");
        volumeMount.setMountPath(mountPath);

        List<VolumeMount> volumeMounts = sidecarContainer.getVolumeMounts();
        if (volumeMounts == null) {
            volumeMounts = new ArrayList<>();
            sidecarContainer.setVolumeMounts(volumeMounts);
        }
        volumeMounts.add(volumeMount);

        List<EnvVar> envVars = sidecarContainer.getEnv();
        if (envVars == null) {
            envVars = new ArrayList<>();
            sidecarContainer.setEnv(envVars);
        }
        envVars.add(new EnvVarBuilder()
            .withName("WORKSPACE_PATH")
            .withValue(mountPath)
            .build());
    }
}
```

---

## PHASE 2: Update consumer files

### 2A: `AbstractTheiaCloudOperatorModule.java`

**Location**: `java/operator/org.eclipse.theia.cloud.operator/src/main/java/org/eclipse/theia/cloud/operator/di/AbstractTheiaCloudOperatorModule.java`

Replace the 3 LanguageServer imports (lines 51–53):
```java
// DELETE these 3 imports:
import org.eclipse.theia.cloud.operator.languageserver.LanguageServerManager;
import org.eclipse.theia.cloud.operator.languageserver.LanguageServerRegistry;
import org.eclipse.theia.cloud.operator.languageserver.LanguageServerResourceFactory;

// ADD these 2 imports:
import org.eclipse.theia.cloud.operator.sidecar.SidecarManager;
import org.eclipse.theia.cloud.operator.sidecar.SidecarResourceFactory;
```

Replace the 3 LanguageServer bindings (lines 91–93):
```java
// DELETE these 3 lines:
bind(LanguageServerRegistry.class).in(Singleton.class);
bind(LanguageServerResourceFactory.class).in(Singleton.class);
bind(LanguageServerManager.class).in(Singleton.class);

// ADD these 2 lines:
bind(SidecarResourceFactory.class).in(Singleton.class);
bind(SidecarManager.class).in(Singleton.class);
```

### 2B: `LazySessionHandler.java`

**Location**: `java/operator/org.eclipse.theia.cloud.operator/src/main/java/org/eclipse/theia/cloud/operator/handler/session/LazySessionHandler.java`

Replace import (line 40):
```java
// DELETE:
import org.eclipse.theia.cloud.operator.languageserver.LanguageServerManager;
// ADD:
import org.eclipse.theia.cloud.operator.sidecar.SidecarManager;
```

Replace field (lines 87–88):
```java
// DELETE:
@Inject
protected LanguageServerManager languageServerManager;
// ADD:
@Inject
protected SidecarManager sidecarManager;
```

Replace env var injection call (line 275):
```java
// OLD:
languageServerManager.injectEnvVars(deployment, session, appDef, correlationId);
// NEW:
sidecarManager.injectSidecarEnvVars(deployment, appDef, correlationId);
```

Replace language server creation (lines 280–285):
```java
// OLD:
// Language server setup is best-effort: the session remains usable even if LS creation fails.
if (!languageServerManager.createLanguageServer(session, appDef, storageName, correlationId)) {
    LOGGER.warn(formatLogMessage(correlationId,
        "Language server creation failed for session " + session.getMetadata().getName()
        + "; session will continue without language server support."));
}
// NEW:
// Sidecar setup is best-effort: the session remains usable even if sidecar creation fails.
if (!sidecarManager.createSidecars(session, appDef, storageName, correlationId)) {
    LOGGER.warn(formatLogMessage(correlationId,
        "Sidecar creation failed for session " + session.getMetadata().getName()
        + "; session will continue without sidecar support."));
}
```

Replace delete call (line 368):
```java
// OLD:
languageServerManager.deleteLanguageServer(session, correlationId);
// NEW:
sidecarManager.deleteSidecars(session, correlationId);
```

### 2C: `EagerSessionHandler.java`

**Location**: `java/operator/org.eclipse.theia.cloud.operator/src/main/java/org/eclipse/theia/cloud/operator/handler/session/EagerSessionHandler.java`

Replace imports (lines 35–36):
```java
// DELETE:
import org.eclipse.theia.cloud.operator.languageserver.LanguageServerManager;
import org.eclipse.theia.cloud.operator.languageserver.LanguageServerResourceFactory;
// ADD:
import org.eclipse.theia.cloud.operator.sidecar.SidecarManager;
```

Replace field (lines 80–81):
```java
// DELETE:
@Inject
private LanguageServerManager languageServerManager;
// ADD:
@Inject
private SidecarManager sidecarManager;
```

Replace the patchEnvVars block (lines 233–238). This entire block can be **deleted** — sidecar env vars are injected at deployment creation time in the pool, so patching is unnecessary. Replace with nothing (just remove those lines).

```java
// DELETE the entire block:
String prewarmedLsServiceName = LanguageServerResourceFactory.getPrewarmedServiceName(appDef, instance.getInstanceId());
if (!languageServerManager.patchEnvVarsIntoExistingDeployment(instance.getDeploymentName(), prewarmedLsServiceName, appDef, correlationId)) {
    LOGGER.warn(formatLogMessage(correlationId,
        "Failed to patch language server env vars into deployment " + instance.getDeploymentName()
        + "; session will continue without language server env vars."));
}
```

Replace delete call (line 351):
```java
// OLD:
languageServerManager.deletePrewarmedLanguageServer(appDef, instanceId, correlationId);
// NEW:
sidecarManager.deletePrewarmedSidecars(appDef, instanceId, correlationId);
```

### 2D: `PrewarmedResourcePool.java`

**Location**: `java/operator/org.eclipse.theia.cloud.operator/src/main/java/org/eclipse/theia/cloud/operator/pool/PrewarmedResourcePool.java`

Replace imports (lines 27–29):
```java
// DELETE:
import org.eclipse.theia.cloud.operator.languageserver.LanguageServerConfig;
import org.eclipse.theia.cloud.operator.languageserver.LanguageServerManager;
import org.eclipse.theia.cloud.operator.languageserver.LanguageServerResourceFactory;
// ADD:
import org.eclipse.theia.cloud.operator.sidecar.SidecarManager;
```

Replace fields (lines 77–80):
```java
// DELETE:
@Inject
private LanguageServerManager languageServerManager;

@Inject
private LanguageServerResourceFactory lsFactory;

// ADD:
@Inject
private SidecarManager sidecarManager;
```

Replace call at line 287 in `ensureCapacity`:
```java
// OLD:
success &= ensureLanguageServerCapacity(appDef, minInstances, labels, correlationId);
// NEW:
success &= sidecarManager.ensurePrewarmedSidecarCapacity(appDef, minInstances, labels, correlationId);
```

Replace call at line 508 in `reconcile`:
```java
// OLD:
success &= reconcileLanguageServers(appDef, targetInstances, labels, correlationId);
// NEW:
success &= sidecarManager.reconcilePrewarmedSidecars(appDef, targetInstances, labels, correlationId);
```

In `reconcileInstance`, replace `lsFactory.deletePrewarmedResources(appDef, instanceId, correlationId)` (appears twice, lines 571 and 589):
```java
// OLD (both occurrences):
lsFactory.deletePrewarmedResources(appDef, instanceId, correlationId);
// NEW (both occurrences):
sidecarManager.deletePrewarmedSidecars(appDef, instanceId, correlationId);
```

In `createInstanceResources` (line 679):
```java
// OLD:
createLanguageServerResources(appDef, instanceId, pvcName, labels, correlationId);
// NEW:
sidecarManager.createPrewarmedSidecars(appDef, instanceId, pvcName, labels, correlationId);
```

**Delete** the three private methods that are now obsolete (lines 682–729):
- `createLanguageServerResources(...)` 
- `ensureLanguageServerCapacity(...)`
- `reconcileLanguageServers(...)`

---

## PHASE 3: Delete old files

Delete these 4 Java files:
```
java/operator/org.eclipse.theia.cloud.operator/src/main/java/org/eclipse/theia/cloud/operator/languageserver/LanguageServerConfig.java
java/operator/org.eclipse.theia.cloud.operator/src/main/java/org/eclipse/theia/cloud/operator/languageserver/LanguageServerManager.java
java/operator/org.eclipse.theia.cloud.operator/src/main/java/org/eclipse/theia/cloud/operator/languageserver/LanguageServerRegistry.java
java/operator/org.eclipse.theia.cloud.operator/src/main/java/org/eclipse/theia/cloud/operator/languageserver/LanguageServerResourceFactory.java
```

Delete these 2 YAML templates:
```
java/operator/org.eclipse.theia.cloud.operator/src/main/resources/templateLanguageServerDeployment.yaml
java/operator/org.eclipse.theia.cloud.operator/src/main/resources/templateLanguageServerService.yaml
```

---

## PHASE 4: Build verification

Run these commands **in order**, each from the repo root. All must succeed:

```bash
cd java/common/maven-conf && mvn clean install -q
cd java/common/org.eclipse.theia.cloud.common && mvn clean install -q
cd java/conversion/org.eclipse.theia.cloud.conversion && mvn clean install -q
cd java/operator/org.eclipse.theia.cloud.operator && mvn clean install -q
cd java/service/org.eclipse.theia.cloud.service && mvn clean compile -q
```

If the operator build fails, fix the compilation errors before proceeding. Common issues:
- Missing imports (check every changed file)
- `client.services()` should be `client.kubernetes().services()` in some contexts
- `Tracing.childSpan(parentSpan, ...)` vs `Tracing.childSpan("...", "...")` — the 2-arg static version creates a root child, the 3-arg takes a parent span

---

## Coding Conventions Checklist

- [ ] EPL-2.0 header on all new Java files (year 2025)
- [ ] `LOGGER.info(formatLogMessage(correlationId, "[Sidecar] ..."))` for all log statements
- [ ] `Tracing.childSpan(...)` / `Tracing.finishSuccess(span)` / `Tracing.finishError(span, e)` for all spans
- [ ] `@Inject` fields, `@Singleton` on classes
- [ ] Null-check `getEnv()`, `getVolumes()`, `getVolumeMounts()` — initialize to `new ArrayList<>()` before mutation
- [ ] No `as any` / `@ts-ignore` / empty catch blocks
- [ ] No wildcard imports
