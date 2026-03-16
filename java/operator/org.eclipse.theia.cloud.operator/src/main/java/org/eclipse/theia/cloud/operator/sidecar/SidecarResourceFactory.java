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

    /**
     * Deletes all pods matching a sidecar deployment's label selector, causing
     * the Deployment controller to recreate them. The Deployment and Service are kept.
     */
    public void deletePodsForDeployment(String deploymentName, String correlationId) {
        LOGGER.info(formatLogMessage(correlationId, "[Sidecar] Deleting pods for deployment " + deploymentName));

        try {
            client.kubernetes().pods().inNamespace(client.namespace())
                .withLabel("app", deploymentName)
                .delete();
        } catch (Exception e) {
            LOGGER.warn(formatLogMessage(correlationId,
                "[Sidecar] Failed to delete pods for deployment " + deploymentName), e);
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
