package org.eclipse.theia.cloud.operator.pool;

import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatLogMessage;

import java.io.IOException;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionSpec;
import org.eclipse.theia.cloud.common.util.DataBridgeUtil;
import org.eclipse.theia.cloud.common.util.LabelsUtil;
import org.eclipse.theia.cloud.common.util.NamingUtil;
import org.eclipse.theia.cloud.operator.TheiaCloudOperatorArguments;
import org.eclipse.theia.cloud.operator.databridge.DataBridgeTokenManager;
import org.eclipse.theia.cloud.operator.handler.AddedHandlerUtil;
import org.eclipse.theia.cloud.operator.handler.session.EagerSessionHandler;
import org.eclipse.theia.cloud.operator.sidecar.SidecarConfig;
import org.eclipse.theia.cloud.operator.sidecar.SidecarManager;
import org.eclipse.theia.cloud.operator.replacements.DefaultPersistentVolumeTemplateReplacements;
import org.eclipse.theia.cloud.operator.util.JavaResourceUtil;
import org.eclipse.theia.cloud.operator.util.K8sResourceFactory;
import org.eclipse.theia.cloud.operator.util.K8sUtil;
import org.eclipse.theia.cloud.operator.util.OwnershipManager;
import org.eclipse.theia.cloud.operator.util.OwnershipManager.OwnerContext;
import org.eclipse.theia.cloud.operator.util.ResourceLifecycleManager;
import org.eclipse.theia.cloud.common.tracing.Tracing;
import org.eclipse.theia.cloud.operator.util.TheiaCloudConfigMapUtil;
import org.eclipse.theia.cloud.operator.util.TheiaCloudDeploymentUtil;
import org.eclipse.theia.cloud.operator.util.TheiaCloudHandlerUtil;
import org.eclipse.theia.cloud.operator.util.TheiaCloudK8sUtil;
import org.eclipse.theia.cloud.operator.util.TheiaCloudServiceUtil;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.sentry.ISpan;
import io.sentry.SpanStatus;

/**
 * Manages a pool of prewarmed (eager start) resources for an AppDefinition. Responsibilities: - Creating/scaling the
 * pool of prewarmed instances - Reserving instances for sessions - Releasing instances back to the pool - Cleaning up
 * pool resources
 */
@Singleton
public class PrewarmedResourcePool {

    private static final Logger LOGGER = LogManager.getLogger(PrewarmedResourcePool.class);

    /**
     * Set on both services of an instance in the same edit that adds the claiming session as owner, and removed only
     * when the instance is reset. Its value is the UID of the session. A session has no finalizer, so the garbage
     * collector can drop its owner reference before the operator handles the deletion; the label keeps such an instance
     * from looking free until it was reset.
     */
    public static final String CLAIM_LABEL = "theia-cloud.io/claimed";

    private static final ScheduledExecutorService BACKGROUND = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pool-background");
        thread.setDaemon(true);
        return thread;
    });
    private static final int MAX_RECREATION_ATTEMPTS = 8;
    private final Set<String> pendingReconciles = ConcurrentHashMap.newKeySet();

    static boolean isClaimed(HasMetadata resource) {
        return resource.getMetadata().getLabels() != null
                && resource.getMetadata().getLabels().containsKey(CLAIM_LABEL);
    }

    public static final String EAGER_START_REFRESH_ANNOTATION = "theia-cloud.io/eager-start-refresh";
    public static final String APPDEFINITION_GENERATION_LABEL = "theia-cloud.io/appdefinition-generation";

    @Inject
    private TheiaCloudClient client;

    @Inject
    private K8sResourceFactory resourceFactory;

    @Inject
    private SidecarManager sidecarManager;

    @Inject
    private TheiaCloudOperatorArguments arguments;

    @Inject
    private DataBridgeTokenManager dataBridgeTokens;

    /**
     * Represents a reserved instance from the pool.
     */
    public static class PoolInstance {
        private final int instanceId;
        private final Service externalService;
        private final Service internalService;
        private final String deploymentName;

        public PoolInstance(int instanceId, Service externalService, Service internalService, String deploymentName) {
            this.instanceId = instanceId;
            this.externalService = externalService;
            this.internalService = internalService;
            this.deploymentName = deploymentName;
        }

        public int getInstanceId() {
            return instanceId;
        }

        public Service getExternalService() {
            return externalService;
        }

        public Service getInternalService() {
            return internalService;
        }

        public String getDeploymentName() {
            return deploymentName;
        }
    }

    /**
     * Result of a reservation attempt.
     */
    public static class ReservationResult {
        private final ReservationOutcome outcome;
        private final PoolInstance instance;

        private ReservationResult(ReservationOutcome outcome, PoolInstance instance) {
            this.outcome = outcome;
            this.instance = instance;
        }

        public ReservationOutcome getOutcome() {
            return outcome;
        }

        public Optional<PoolInstance> getInstance() {
            return Optional.ofNullable(instance);
        }

        public static ReservationResult success(PoolInstance instance) {
            return new ReservationResult(ReservationOutcome.SUCCESS, instance);
        }

        public static ReservationResult noCapacity() {
            return new ReservationResult(ReservationOutcome.NO_CAPACITY, null);
        }

        public static ReservationResult error() {
            return new ReservationResult(ReservationOutcome.ERROR, null);
        }
    }

    public enum ReservationOutcome {
        SUCCESS, NO_CAPACITY, ERROR
    }

    // ========== Pool Management ==========

    /**
     * Ensures the pool has the specified minimum number of instances. Creates missing resources (services, configmaps,
     * deployments).
     */
    public boolean ensureCapacity(AppDefinition appDef, int minInstances, String correlationId) {
        String appDefName = appDef.getSpec().getName();
        ISpan span = Tracing.childSpan("pool.ensure_capacity", "Ensure pool capacity");

        span.setTag("app_definition", appDefName);
        span.setData("min_instances", minInstances);

        LOGGER.info(formatLogMessage(correlationId, "Ensuring pool capacity: " + minInstances + " for " + appDefName));

        try {
            String ownerName = appDef.getMetadata().getName();
            String ownerUID = appDef.getMetadata().getUid();
            Map<String, String> labels = new HashMap<>();

            // Get existing resources
            ISpan fetchSpan = span.startChild("pool.fetch_existing", "Fetch existing resources");
            List<Service> existingServices = K8sUtil.getExistingServices(client.kubernetes(), client.namespace(),
                    ownerName, ownerUID);
            List<Deployment> existingDeployments = K8sUtil.getExistingDeployments(client.kubernetes(),
                    client.namespace(), ownerName, ownerUID);
            List<ConfigMap> existingConfigMaps = K8sUtil.getExistingConfigMaps(client.kubernetes(), client.namespace(),
                    ownerName, ownerUID);

            fetchSpan.setData("existing_services", existingServices.size());
            fetchSpan.setData("existing_deployments", existingDeployments.size());
            fetchSpan.setData("existing_configmaps", existingConfigMaps.size());
            Tracing.finishSuccess(fetchSpan);

            // Compute missing IDs
            Set<Integer> missingServiceIds = TheiaCloudServiceUtil.computeIdsOfMissingServices(appDef, correlationId,
                    minInstances, existingServices);
            Set<Integer> missingDeploymentIds = TheiaCloudDeploymentUtil.computeIdsOfMissingDeployments(appDef,
                    correlationId, minInstances, existingDeployments);

            span.setData("missing_service_ids", missingServiceIds.size());
            span.setData("missing_deployment_ids", missingDeploymentIds.size());

            boolean success = true;

            // Create missing services
            if (!missingServiceIds.isEmpty()) {
                ISpan serviceSpan = span.startChild("pool.create_services", "Create missing services");
                serviceSpan.setData("count", missingServiceIds.size());
                int created = 0;
                int failed = 0;
                for (int instance : missingServiceIds) {
                    ISpan svcSpan = serviceSpan.startChild("k8s.service.create", "Create service " + instance);
                    svcSpan.setData("instance_id", instance);
                    boolean extOk = resourceFactory
                            .createServiceForEagerInstance(appDef, instance, labels, correlationId).isPresent();
                    boolean intOk = resourceFactory
                            .createInternalServiceForEagerInstance(appDef, instance, labels, correlationId).isPresent();
                    if (extOk && intOk) {
                        created++;
                        Tracing.finishSuccess(svcSpan);
                    } else {
                        failed++;
                        success = false;
                        svcSpan.setTag("outcome", "failure"); Tracing.finish(svcSpan, SpanStatus.INTERNAL_ERROR);
                    }
                }
                serviceSpan.setData("created", created);
                serviceSpan.setData("failed", failed);
                serviceSpan.setTag("outcome", failed == 0 ? "success" : "failure");
                Tracing.finish(serviceSpan, failed == 0 ? SpanStatus.OK : SpanStatus.INTERNAL_ERROR);
            }

            // Create missing configmaps (if using Keycloak)
            if (arguments.isUseKeycloak()) {
                List<ConfigMap> proxyConfigMaps = existingConfigMaps.stream().filter(
                        cm -> "proxy".equals(cm.getMetadata().getLabels().get("theia-cloud.io/template-purpose")))
                        .collect(Collectors.toList());
                List<ConfigMap> emailConfigMaps = existingConfigMaps.stream().filter(
                        cm -> "emails".equals(cm.getMetadata().getLabels().get("theia-cloud.io/template-purpose")))
                        .collect(Collectors.toList());

                Set<Integer> missingProxyIds = TheiaCloudConfigMapUtil.computeIdsOfMissingProxyConfigMaps(appDef,
                        correlationId, minInstances, proxyConfigMaps);
                Set<Integer> missingEmailIds = TheiaCloudConfigMapUtil.computeIdsOfMissingEmailConfigMaps(appDef,
                        correlationId, minInstances, emailConfigMaps);

                if (!missingProxyIds.isEmpty() || !missingEmailIds.isEmpty()) {
                    ISpan cmSpan = span.startChild("pool.create_configmaps", "Create missing configmaps");
                    cmSpan.setData("missing_proxy", missingProxyIds.size());
                    cmSpan.setData("missing_email", missingEmailIds.size());

                    for (int instance : missingProxyIds) {
                        success &= resourceFactory
                                .createProxyConfigMapForEagerInstance(appDef, instance, labels, correlationId)
                                .isPresent();
                    }
                    for (int instance : missingEmailIds) {
                        success &= resourceFactory
                                .createEmailConfigMapForEagerInstance(appDef, instance, labels, correlationId)
                                .isPresent();
                    }
                    cmSpan.setTag("outcome", success ? "success" : "failure"); Tracing.finish(cmSpan, success ? SpanStatus.OK : SpanStatus.INTERNAL_ERROR);
                }

                success &= restoreEmailConfigsOfClaimedInstances(appDef, correlationId);
            }

            // Ensure PVCs exist first for sidecars with mountWorkspace=true.
            for (int instance = 1; instance <= minInstances; instance++) {
                createInstancePvc(appDef, instance, correlationId);
            }

            // Create sidecar resources BEFORE Theia deployments so DNS resolves at pod startup
            success &= sidecarManager.ensurePrewarmedSidecarCapacity(appDef, minInstances, labels, correlationId);

            // Create missing deployments (with shared PVC)
            if (!missingDeploymentIds.isEmpty()) {
                ISpan deploySpan = span.startChild("pool.create_deployments", "Create missing deployments");
                deploySpan.setData("count", missingDeploymentIds.size());
                int created = 0;
                int failed = 0;
                for (int instance : missingDeploymentIds) {
                    ISpan depSpan = deploySpan.startChild("k8s.deployment.create", "Create deployment " + instance);
                    depSpan.setData("instance_id", instance);
                    Optional<String> pvcName = createInstancePvc(appDef, instance, correlationId);
                    if (resourceFactory.createDeploymentForEagerInstance(appDef, instance, pvcName, labels, correlationId)
                            .isPresent()) {
                        created++;
                        Tracing.finishSuccess(depSpan);
                    } else {
                        failed++;
                        success = false;
                        depSpan.setTag("outcome", "failure"); Tracing.finish(depSpan, SpanStatus.INTERNAL_ERROR);
                    }
                }
                deploySpan.setData("created", created);
                deploySpan.setData("failed", failed);
                deploySpan.setTag("outcome", failed == 0 ? "success" : "failure");
                Tracing.finish(deploySpan, failed == 0 ? SpanStatus.OK : SpanStatus.INTERNAL_ERROR);
            }

            span.setTag("outcome", success ? "success" : "failure"); Tracing.finish(span, success ? SpanStatus.OK : SpanStatus.INTERNAL_ERROR);
            return success;

        } catch (Exception e) {
            Tracing.finishError(span, e);
            throw e;
        }
    }

    /**
     * Reconciles the pool to match the target instance count. Creates missing instances, removes excess instances
     * (respecting ownership).
     */
    public boolean reconcile(AppDefinition appDef, int targetInstances, String correlationId) {
        String appDefName = appDef.getSpec().getName();
        ISpan span = Tracing.childSpan("pool.reconcile", "Reconcile pool");

        span.setTag("app_definition", appDefName);
        span.setData("target_instances", targetInstances);
        span.setData("generation", appDef.getMetadata().getGeneration());

        LOGGER.info(formatLogMessage(correlationId, "Reconciling pool to " + targetInstances + " instances"));

        try {
            String ownerName = appDef.getMetadata().getName();
            String ownerUID = appDef.getMetadata().getUid();
            OwnerContext owner = OwnerContext.of(ownerName, ownerUID, AppDefinition.API, AppDefinition.KIND);
            long currentGeneration = appDef.getMetadata().getGeneration();
            Map<String, String> labels = new HashMap<>();

            // Instances whose session is gone and that were never reset, e.g. because the operator was down
            resetOrphanedClaims(appDef, correlationId);
            Set<Integer> claimedIds = claimedInstanceIds(appDef);

            boolean success = true;

            // Reconcile services
            ISpan serviceSpan = span.startChild("pool.reconcile_services", "Reconcile services");
            List<Service> existingServices = K8sUtil.getExistingServices(client.kubernetes(), client.namespace(),
                    ownerName, ownerUID);
            List<Service> externalServices = existingServices.stream()
                    .filter(s -> !s.getMetadata().getName().endsWith("-int")).collect(Collectors.toList());
            List<Service> internalServices = existingServices.stream()
                    .filter(s -> s.getMetadata().getName().endsWith("-int")).collect(Collectors.toList());
            Set<Integer> missingServiceIds = TheiaCloudServiceUtil.computeIdsOfMissingServices(appDef, correlationId,
                    targetInstances, existingServices);

            serviceSpan.setData("existing_external", externalServices.size());
            serviceSpan.setData("existing_internal", internalServices.size());
            serviceSpan.setData("missing", missingServiceIds.size());

            // Reconcile external services
            ResourceLifecycleManager.ReconcileResult extResult = ResourceLifecycleManager
                    .reconcile(ResourceLifecycleManager.ReconcileContext.<Service> builder()
                            .correlationId(correlationId).existingResources(externalServices)
                            .missingIds(missingServiceIds).targetCount(targetInstances).owner(owner)
                            .resourceAccessor(
                                    s -> client.kubernetes().services().inNamespace(client.namespace()).resource(s))
                            .idExtractor(s -> TheiaCloudServiceUtil.getId(correlationId, appDef, s))
                            .resourceTypeName("service").createResource(instance -> {
                                resourceFactory.createServiceForEagerInstance(appDef, instance, labels, correlationId);
                            }).shouldRecreate(
                                    s -> OwnershipManager.isOwnedSolelyBy(s, owner) && !isClaimed(s)
                                            && isOutdated(s, currentGeneration))
                            .recreateResource(s -> {
                                Integer id = TheiaCloudServiceUtil.getId(correlationId, appDef, s);
                                if (id != null) {
                                    resourceFactory.createServiceForEagerInstance(appDef, id, labels, correlationId);
                                }
                            }).build());
            success &= extResult.isSuccess();

            // Reconcile internal services
            ResourceLifecycleManager.ReconcileResult intResult = ResourceLifecycleManager
                    .reconcile(ResourceLifecycleManager.ReconcileContext.<Service> builder()
                            .correlationId(correlationId).existingResources(internalServices)
                            .missingIds(missingServiceIds).targetCount(targetInstances).owner(owner)
                            .resourceAccessor(
                                    s -> client.kubernetes().services().inNamespace(client.namespace()).resource(s))
                            .idExtractor(s -> TheiaCloudServiceUtil.getId(correlationId, appDef, s))
                            .resourceTypeName("internal service").createResource(instance -> {
                                resourceFactory.createInternalServiceForEagerInstance(appDef, instance, labels,
                                        correlationId);
                            }).shouldRecreate(
                                    s -> OwnershipManager.isOwnedSolelyBy(s, owner) && !isClaimed(s)
                                            && isOutdated(s, currentGeneration))
                            .recreateResource(s -> {
                                Integer id = TheiaCloudServiceUtil.getId(correlationId, appDef, s);
                                if (id != null) {
                                    resourceFactory.createInternalServiceForEagerInstance(appDef, id, labels,
                                            correlationId);
                                }
                            }).build());
            success &= intResult.isSuccess();

            int created = extResult.getCreated() + intResult.getCreated();
            int deleted = extResult.getDeleted() + intResult.getDeleted();
            int recreated = extResult.getRecreated() + intResult.getRecreated();
            int skipped = extResult.getSkipped() + intResult.getSkipped();
            success = extResult.isSuccess() && intResult.isSuccess();
            serviceSpan.setData("created_count", created);
            serviceSpan.setData("deleted_count", deleted);
            serviceSpan.setData("recreated_count", recreated);
            serviceSpan.setData("skipped_count", skipped);
            serviceSpan.setTag("outcome", success ? "success" : "failure");
            serviceSpan.setTag("had_changes", (created + deleted + recreated) > 0 ? "true" : "false");
            serviceSpan.finish();

            // Reconcile configmaps (if using Keycloak)
            if (arguments.isUseKeycloak()) {
                ISpan cmSpan = span.startChild("pool.reconcile_configmaps", "Reconcile configmaps");
                List<ConfigMap> existingConfigMaps = K8sUtil.getExistingConfigMaps(client.kubernetes(),
                        client.namespace(), ownerName, ownerUID);
                List<ConfigMap> proxyConfigMaps = existingConfigMaps.stream().filter(
                        cm -> "proxy".equals(cm.getMetadata().getLabels().get("theia-cloud.io/template-purpose")))
                        .collect(Collectors.toList());
                List<ConfigMap> emailConfigMaps = existingConfigMaps.stream().filter(
                        cm -> "emails".equals(cm.getMetadata().getLabels().get("theia-cloud.io/template-purpose")))
                        .collect(Collectors.toList());

                Set<Integer> missingProxyIds = TheiaCloudConfigMapUtil.computeIdsOfMissingProxyConfigMaps(appDef,
                        correlationId, targetInstances, proxyConfigMaps);
                Set<Integer> missingEmailIds = TheiaCloudConfigMapUtil.computeIdsOfMissingEmailConfigMaps(appDef,
                        correlationId, targetInstances, emailConfigMaps);

                ResourceLifecycleManager.ReconcileResult proxyResult = ResourceLifecycleManager
                        .reconcile(ResourceLifecycleManager.ReconcileContext.<ConfigMap> builder()
                                .correlationId(correlationId).existingResources(proxyConfigMaps)
                                .missingIds(missingProxyIds).targetCount(targetInstances).owner(owner)
                                .resourceAccessor(cm -> client.kubernetes().configMaps().inNamespace(client.namespace())
                                        .resource(cm))
                                .idExtractor(cm -> TheiaCloudConfigMapUtil.getProxyId(correlationId, appDef, cm))
                                .resourceTypeName("proxy configmap")
                                .createResource(instance -> resourceFactory.createProxyConfigMapForEagerInstance(appDef,
                                        instance, labels, correlationId))
                                .shouldRecreate(cm -> OwnershipManager.isOwnedSolelyBy(cm, owner)
                                        && !claimedIds.contains(parseConfigMapInstanceIdOrDefault(appDef, cm, -1))
                                        && isOutdated(cm, currentGeneration))
                                .recreateResource(cm -> {
                                    Integer id = TheiaCloudConfigMapUtil.getProxyId(correlationId, appDef, cm);
                                    if (id != null) {
                                        resourceFactory.createProxyConfigMapForEagerInstance(appDef, id, labels,
                                                correlationId);
                                    }
                                }).build());
                success &= proxyResult.isSuccess();

                ResourceLifecycleManager.ReconcileResult emailResult = ResourceLifecycleManager
                        .reconcile(ResourceLifecycleManager.ReconcileContext.<ConfigMap> builder()
                                .correlationId(correlationId).existingResources(emailConfigMaps)
                                .missingIds(missingEmailIds).targetCount(targetInstances).owner(owner)
                                .resourceAccessor(cm -> client.kubernetes().configMaps().inNamespace(client.namespace())
                                        .resource(cm))
                                .idExtractor(cm -> TheiaCloudConfigMapUtil.getEmailId(correlationId, appDef, cm))
                                .resourceTypeName("email configmap")
                                .createResource(instance -> resourceFactory.createEmailConfigMapForEagerInstance(appDef,
                                        instance, labels, correlationId))
                                .shouldRecreate(cm -> OwnershipManager.isOwnedSolelyBy(cm, owner)
                                        && !claimedIds.contains(parseConfigMapInstanceIdOrDefault(appDef, cm, -1))
                                        && isOutdated(cm, currentGeneration))
                                .recreateResource(cm -> {
                                    Integer id = TheiaCloudConfigMapUtil.getEmailId(correlationId, appDef, cm);
                                    if (id != null) {
                                        resourceFactory.createEmailConfigMapForEagerInstance(appDef, id, labels,
                                                correlationId);
                                    }
                                }).build());
                success &= emailResult.isSuccess();

                int cmCreated = proxyResult.getCreated() + emailResult.getCreated();
                int cmDeleted = proxyResult.getDeleted() + emailResult.getDeleted();
                int cmRecreated = proxyResult.getRecreated() + emailResult.getRecreated();
                int cmSkipped = proxyResult.getSkipped() + emailResult.getSkipped();
                boolean cmSuccess = proxyResult.isSuccess() && emailResult.isSuccess();
                cmSpan.setData("created_count", cmCreated);
                cmSpan.setData("deleted_count", cmDeleted);
                cmSpan.setData("recreated_count", cmRecreated);
                cmSpan.setData("skipped_count", cmSkipped);
                cmSpan.setTag("outcome", cmSuccess ? "success" : "failure");
                cmSpan.setTag("had_changes", (cmCreated + cmDeleted + cmRecreated) > 0 ? "true" : "false");
                cmSpan.finish();

                success &= restoreEmailConfigsOfClaimedInstances(appDef, correlationId);
            }

            // Ensure PVCs exist first for sidecars with mountWorkspace=true.
            for (int instance = 1; instance <= targetInstances; instance++) {
                createInstancePvc(appDef, instance, correlationId);
            }

            // Reconcile sidecar resources BEFORE Theia deployments so DNS resolves at pod startup
            success &= sidecarManager.reconcilePrewarmedSidecars(appDef, targetInstances, labels,
                    claimedIds, correlationId);

            // Reconcile deployments
            ISpan deploySpan = span.startChild("pool.reconcile_deployments", "Reconcile deployments");
            List<Deployment> existingDeployments = K8sUtil.getExistingDeployments(client.kubernetes(),
                    client.namespace(), ownerName, ownerUID);
            Set<Integer> missingDeploymentIds = TheiaCloudDeploymentUtil.computeIdsOfMissingDeployments(appDef,
                    correlationId, targetInstances, existingDeployments);

            deploySpan.setData("existing", existingDeployments.size());
            deploySpan.setData("missing", missingDeploymentIds.size());

            ResourceLifecycleManager.ReconcileResult deployResult = ResourceLifecycleManager
                    .reconcile(ResourceLifecycleManager.ReconcileContext.<Deployment> builder()
                            .correlationId(correlationId).existingResources(existingDeployments)
                            .missingIds(missingDeploymentIds).targetCount(targetInstances).owner(owner)
                            .resourceAccessor(d -> client.kubernetes().apps().deployments()
                                    .inNamespace(client.namespace()).resource(d))
                            .idExtractor(d -> TheiaCloudDeploymentUtil.getId(correlationId, appDef, d))
                            .resourceTypeName("deployment")
                            .createResource(instance -> {
                                Optional<String> pvcName = createInstancePvc(appDef, instance, correlationId);
                                resourceFactory.createDeploymentForEagerInstance(appDef, instance, pvcName, labels, correlationId);
                            })
                            .shouldRecreate(
                                    d -> OwnershipManager.isOwnedSolelyBy(d, owner)
                                            && !claimedIds.contains(parseDeploymentInstanceIdOrDefault(appDef, d, -1))
                                            && (isOutdated(d, currentGeneration) || lacksDataBridgeToken(appDef, d)))
                            .recreateResource(d -> {
                                Integer id = TheiaCloudDeploymentUtil.getId(correlationId, appDef, d);
                                if (id != null) {
                                    Optional<String> pvcName = createInstancePvc(appDef, id, correlationId);
                                    resourceFactory.createDeploymentForEagerInstance(appDef, id, pvcName, labels, correlationId);
                                }
                            }).build());
            success &= deployResult.isSuccess();
            for (Deployment d : existingDeployments) {
                Integer id = TheiaCloudDeploymentUtil.getId(correlationId, appDef, d);
                if (id != null && id > targetInstances && OwnershipManager.isOwnedSolelyBy(d, owner)) {
                    deleteDataBridgeToken(appDef, id, correlationId);
                }
            }

            deploySpan.setData("created_count", deployResult.getCreated());
            deploySpan.setData("deleted_count", deployResult.getDeleted());
            deploySpan.setData("recreated_count", deployResult.getRecreated());
            deploySpan.setData("skipped_count", deployResult.getSkipped());
            deploySpan.setTag("outcome", deployResult.isSuccess() ? "success" : "failure");
            deploySpan.setTag("had_changes", (deployResult.getCreated() + deployResult.getDeleted() + deployResult.getRecreated()) > 0 ? "true" : "false");
            deploySpan.finish();

            span.setTag("outcome", success ? "success" : "failure"); Tracing.finish(span, success ? SpanStatus.OK : SpanStatus.INTERNAL_ERROR);
            return success;

        } catch (Exception e) {
            Tracing.finishError(span, e);
            throw e;
        }
    }

    /**
     * Reconciles a single instance after session release. - If instanceId > minInstances → delete all resources for
     * this instance - If resource generation != current AppDefinition generation → recreate - If only the NetworkPolicy
     * labels or the data bridge token reference are missing → recreate the deployment and keep the PVC - Otherwise → do nothing
     */
    public void reconcileInstance(AppDefinition appDef, int instanceId, String correlationId) {
        boolean create;
        // Looking at the instance and deleting its resources happens under the pool lock, so that no session can claim
        // it in between. Building the new one can wait for a volume and happens outside.
        synchronized (this) {
            create = reconcileInstanceLocked(appDef, instanceId, correlationId);
        }
        if (create) {
            createInstanceResources(appDef, instanceId, correlationId);
        }
    }

    /** @return true when the instance was cleared and has to be created again */
    private boolean reconcileInstanceLocked(AppDefinition appDef, int instanceId, String correlationId) {
        String appDefName = appDef.getSpec().getName();
        int minInstances = appDef.getSpec().getMinInstances();
        long currentGeneration = appDef.getMetadata().getGeneration();

        ISpan span = Tracing.childSpan("pool.reconcile_instance", "Reconcile instance " + instanceId);

        span.setTag("app_definition", appDefName);
        span.setData("instance_id", instanceId);
        span.setData("min_instances", minInstances);
        span.setData("generation", currentGeneration);

        try {
            String ownerName = appDef.getMetadata().getName();
            String ownerUID = appDef.getMetadata().getUid();

            LOGGER.info(formatLogMessage(correlationId, "Reconciling instance " + instanceId + " (minInstances="
                    + minInstances + ", generation=" + currentGeneration + ")"));

            // Find resources for this instance
            List<Service> allServices = K8sUtil.getExistingServices(client.kubernetes(), client.namespace(), ownerName,
                    ownerUID);
            List<Deployment> allDeployments = K8sUtil.getExistingDeployments(client.kubernetes(), client.namespace(),
                    ownerName, ownerUID);
            List<ConfigMap> allConfigMaps = K8sUtil.getExistingConfigMaps(client.kubernetes(), client.namespace(),
                    ownerName, ownerUID);

            // Filter to just this instance
            List<Service> instanceServices = allServices.stream()
                    .filter(s -> instanceId == parseInstanceIdOrDefault(s, -1)).collect(Collectors.toList());
            List<Deployment> instanceDeployments = allDeployments.stream()
                    .filter(d -> instanceId == parseDeploymentInstanceIdOrDefault(appDef, d, -1))
                    .collect(Collectors.toList());
            List<ConfigMap> instanceConfigMaps = allConfigMaps.stream()
                    .filter(cm -> instanceId == parseConfigMapInstanceIdOrDefault(appDef, cm, -1))
                    .collect(Collectors.toList());

            span.setData("found_services", instanceServices.size());
            span.setData("found_deployments", instanceDeployments.size());
            span.setData("found_configmaps", instanceConfigMaps.size());

            if (instanceServices.stream().anyMatch(s -> isClaimed(s) || !TheiaCloudServiceUtil.isUnusedService(s))) {
                // A session holds this instance, or just claimed it. It is reset when that session ends.
                span.setTag("action", "claimed");
                Tracing.finishSuccess(span);
                return false;
            }

            if (instanceId > minInstances) {
                span.setTag("action", "delete_excess");
                LOGGER.info(formatLogMessage(correlationId,
                        "Instance " + instanceId + " exceeds minInstances (" + minInstances + "), deleting"));

                ISpan deleteSpan = span.startChild("pool.delete_excess_instance", "Delete excess instance");
                deleteInstanceResources(instanceServices, instanceDeployments, instanceConfigMaps, correlationId);
                sidecarManager.deletePrewarmedSidecars(appDef, instanceId, correlationId);
                deleteInstancePvc(appDef, instanceId, correlationId);
                deleteDataBridgeToken(appDef, instanceId, correlationId);
                Tracing.finishSuccess(deleteSpan);
                Tracing.finishSuccess(span);
                return false;
            }

            boolean missingVolume = SidecarConfig.requiresSharedWorkspace(appDef) && !instanceDeployments.isEmpty()
                    && client.persistentVolumeClaimsClient().get(getInstancePvcName(appDef, instanceId)).isEmpty();
            boolean incomplete = instanceDeployments.isEmpty() || instanceServices.size() < 2;
            boolean outdated = incomplete || missingVolume || isOutdated(instanceServices, currentGeneration)
                    || isOutdated(instanceDeployments, currentGeneration)
                    || isOutdated(instanceConfigMaps, currentGeneration);

            if (outdated) {
                span.setTag("action", "recreate_outdated");
                LOGGER.info(formatLogMessage(correlationId,
                        "Instance " + instanceId + " has outdated resources, recreating"));

                ISpan recreateSpan = span.startChild("pool.recreate_outdated_instance", "Recreate outdated instance");
                deleteInstanceResources(instanceServices, instanceDeployments, instanceConfigMaps, correlationId);
                sidecarManager.deletePrewarmedSidecars(appDef, instanceId, correlationId);
                deleteInstancePvc(appDef, instanceId, correlationId);
                Tracing.finishSuccess(recreateSpan);
                Tracing.finishSuccess(span);
                return true;
            }

            if (instanceDeployments.stream().anyMatch(PrewarmedResourcePool::lacksNetworkPolicyLabels)
                    || instanceDeployments.stream().anyMatch(PrewarmedResourcePool::usesSkipAuthRoute)
                    || instanceDeployments.stream().anyMatch(d -> lacksDataBridgeToken(appDef, d))) {
                // Only the pod labels, the proxy arguments or the data bridge token are wrong, so the PVC and the workspace stored on it stay.
                span.setTag("action", "repair_labels");
                LOGGER.info(formatLogMessage(correlationId, "Instance " + instanceId
                        + " lacks the NetworkPolicy labels or the data bridge token, recreating its deployment"));

                deleteInstanceResources(List.of(), instanceDeployments, List.of(), correlationId);
                Optional<String> pvcName = createInstancePvc(appDef, instanceId, correlationId);
                boolean repaired = resourceFactory
                        .createDeploymentForEagerInstance(appDef, instanceId, pvcName, new HashMap<>(), correlationId)
                        .isPresent() && (pvcName.isPresent() || !SidecarConfig.requiresSharedWorkspace(appDef));
                if (repaired) {
                    Tracing.finishSuccess(span);
                } else {
                    // The next reconcile recreates a missing deployment through the capacity check.
                    LOGGER.error(formatLogMessage(correlationId,
                            "Could not fully repair instance " + instanceId + " (deployment or its PVC missing)"));
                    span.setTag("outcome", "failure");
                    Tracing.finish(span, SpanStatus.INTERNAL_ERROR);
                }
                return false;
            }

            span.setTag("action", "no_action");
            LOGGER.info(formatLogMessage(correlationId, "Instance " + instanceId + " is up-to-date, no action needed"));
            Tracing.finishSuccess(span);
            return false;

        } catch (Exception e) {
            Tracing.finishError(span, e);
            throw e;
        }
    }

    private boolean isOutdated(List<? extends HasMetadata> resources, long currentGeneration) {
        for (var resource : resources) {
            if (isOutdated(resource, currentGeneration)) {
                return true;
            }
        }
        return false;
    }

    private boolean isOutdated(Deployment deployment, long currentGeneration) {
        return isOutdated((HasMetadata) deployment, currentGeneration) || lacksNetworkPolicyLabels(deployment)
                || usesSkipAuthRoute(deployment);
    }

    /**
     * Pods created by an older operator carry a proxy argument that the current templates no longer have. The
     * AppDefinition generation does not change with an operator upgrade, so look for the argument itself.
     */
    static boolean usesSkipAuthRoute(Deployment deployment) {
        var podSpec = deployment.getSpec().getTemplate().getSpec();
        if (podSpec == null || podSpec.getContainers() == null) {
            return false;
        }
        return podSpec.getContainers().stream()
                .flatMap(container -> container.getArgs() == null ? java.util.stream.Stream.<String> empty()
                        : container.getArgs().stream())
                .anyMatch(arg -> arg.startsWith("--skip-auth-route"));
    }

    /**
     * NetworkPolicies select session pods by these labels. A release that does not change the AppDefinition keeps its
     * generation, so without this check a pod created before the labels existed would stay outside the isolation.
     */
    static boolean lacksNetworkPolicyLabels(Deployment deployment) {
        Map<String, String> labels = deployment.getSpec().getTemplate().getMetadata().getLabels();
        return labels == null || !LabelsUtil.LABEL_VALUE_SESSION.equals(labels.get(LabelsUtil.LABEL_KEY_SESSION))
                || !LabelsUtil.LABEL_VALUE_THEIACLOUD.equals(labels.get(LabelsUtil.LABEL_KEY_THEIACLOUD));
    }

    private boolean lacksDataBridgeToken(AppDefinition appDef, Deployment deployment) {
        Integer id = TheiaCloudDeploymentUtil.getId("", appDef, deployment);
        return id != null && DataBridgeTokenManager.lacksTokenReference(deployment, appDef, id);
    }

    private boolean isOutdated(HasMetadata resource, long currentGeneration) {
        Map<String, String> labels = resource.getMetadata().getLabels();
        if (labels == null) {
            return true;
        }
        String genLabel = labels.get(APPDEFINITION_GENERATION_LABEL);
        if (genLabel == null) {
            return true;
        }
        try {
            long resourceGen = Long.parseLong(genLabel);
            return resourceGen != currentGeneration;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private void deleteInstanceResources(List<Service> services, List<Deployment> deployments,
            List<ConfigMap> configMaps, String correlationId) {
        for (Service s : services) {
            try {
                client.kubernetes().services().inNamespace(client.namespace()).resource(s).delete();
                LOGGER.trace(formatLogMessage(correlationId, "Deleted service " + s.getMetadata().getName()));
            } catch (KubernetesClientException e) {
                LOGGER.warn(formatLogMessage(correlationId, "Failed to delete service " + s.getMetadata().getName()),
                        e);
            }
        }
        for (Deployment d : deployments) {
            try {
                client.kubernetes().apps().deployments().inNamespace(client.namespace()).resource(d).delete();
                LOGGER.trace(formatLogMessage(correlationId, "Deleted deployment " + d.getMetadata().getName()));
            } catch (KubernetesClientException e) {
                LOGGER.warn(formatLogMessage(correlationId, "Failed to delete deployment " + d.getMetadata().getName()),
                        e);
            }
        }
        for (ConfigMap cm : configMaps) {
            try {
                client.kubernetes().configMaps().inNamespace(client.namespace()).resource(cm).delete();
                LOGGER.trace(formatLogMessage(correlationId, "Deleted configmap " + cm.getMetadata().getName()));
            } catch (KubernetesClientException e) {
                LOGGER.warn(formatLogMessage(correlationId, "Failed to delete configmap " + cm.getMetadata().getName()),
                        e);
            }
        }
    }

    void createInstanceResources(AppDefinition appDef, int instanceId, String correlationId) {
        Map<String, String> labels = new HashMap<>();

        Optional<String> pvcName = createInstancePvc(appDef, instanceId, correlationId);
        if (pvcName.isEmpty() && SidecarConfig.requiresSharedWorkspace(appDef)) {
            // The old volume is still being deleted. Create nothing, so no session can claim a half-built instance. The
            // next reconcile creates the missing resources once the volume is gone.
            LOGGER.warn(formatLogMessage(correlationId, "Volume of instance " + instanceId
                    + " is not available yet, leaving the instance to the next reconcile"));
            return;
        }

        if (arguments.isUseKeycloak()) {
            resourceFactory.createProxyConfigMapForEagerInstance(appDef, instanceId, labels, correlationId);
            resourceFactory.createEmailConfigMapForEagerInstance(appDef, instanceId, labels, correlationId);
        }

        // Create sidecar Deployment+Service BEFORE Theia Deployment so DNS resolves at pod startup
        sidecarManager.createPrewarmedSidecars(appDef, instanceId, pvcName, labels, correlationId);

        resourceFactory.createDeploymentForEagerInstance(appDef, instanceId, pvcName, labels, correlationId);

        // The services make an instance claimable, so they come last: nothing can claim a half-built instance
        resourceFactory.createServiceForEagerInstance(appDef, instanceId, labels, correlationId);
        resourceFactory.createInternalServiceForEagerInstance(appDef, instanceId, labels, correlationId);
    }

    /**
     * Re-applies the authenticated email of every session that currently holds an instance of this pool. The email
     * config maps belong to the app definition, so they are rebuilt empty whenever the pool is rebuilt, e.g. after an
     * operator restart or after the app definition changed. The sessions that claimed those instances survive that
     * rebuild, and an empty list makes oauth2-proxy answer 403 for every user. Restored instances are refreshed like
     * they are on reservation. Writing the email back is idempotent, config maps that already hold the right email are
     * left untouched.
     */
    boolean restoreEmailConfigsOfClaimedInstances(AppDefinition appDef, String correlationId) {
        Map<Integer, Session> claimedInstances = computeClaimedInstances(appDef, client.sessions().list());
        if (claimedInstances.isEmpty()) {
            return true;
        }

        ISpan span = Tracing.childSpan("pool.restore_email_configs", "Restore email configs of claimed instances");
        span.setTag("app_definition", appDef.getSpec().getName());
        span.setData("claimed_instances", claimedInstances.size());

        boolean success = true;
        int restored = 0;
        for (Map.Entry<Integer, Session> claim : claimedInstances.entrySet()) {
            int instanceId = claim.getKey();
            Session session = claim.getValue();
            String user = session.getSpec().getUser();
            String emailConfigName = TheiaCloudConfigMapUtil.getEmailConfigName(appDef, instanceId);
            try {
                ConfigMap emailConfigMap = client.kubernetes().configMaps().inNamespace(client.namespace())
                        .withName(emailConfigName).get();
                if (emailConfigMap == null) {
                    LOGGER.warn(formatLogMessage(correlationId, "Email config " + emailConfigName
                            + " of instance " + instanceId + " claimed by a session does not exist"));
                    continue;
                }
                Map<String, String> data = emailConfigMap.getData();
                if (data != null
                        && user.equals(data.get(AddedHandlerUtil.FILENAME_AUTHENTICATED_EMAILS_LIST))) {
                    continue;
                }
                // The claim may have changed since it was listed. Releasing an instance clears this config map, so
                // writing an outdated claim back would admit a user that no longer holds the instance.
                if (!stillClaimsInstance(appDef, instanceId, session)) {
                    LOGGER.info(formatLogMessage(correlationId, "Session no longer claims instance " + instanceId
                            + ", leaving the email config alone"));
                    continue;
                }
                LOGGER.info(formatLogMessage(correlationId, "Restoring authenticated email of instance " + instanceId
                        + " that is still claimed by a session"));
                client.kubernetes().configMaps().inNamespace(client.namespace()).withName(emailConfigName).edit(cm -> {
                    cm.setData(
                            Collections.singletonMap(AddedHandlerUtil.FILENAME_AUTHENTICATED_EMAILS_LIST, user));
                    return cm;
                });
                refreshPods(TheiaCloudDeploymentUtil.getDeploymentName(appDef, instanceId), correlationId);
                restored++;
            } catch (KubernetesClientException e) {
                LOGGER.error(formatLogMessage(correlationId, "Failed to restore email config " + emailConfigName), e);
                success = false;
            }
        }

        span.setData("restored", restored);
        span.setTag("outcome", success ? "success" : "failure");
        Tracing.finish(span, success ? SpanStatus.OK : SpanStatus.INTERNAL_ERROR);
        return success;
    }

    /**
     * Maps the ids of the pool instances that are currently claimed to the session that claimed them. Sessions record
     * their instance in the instance id annotation when they reserve it; sessions of another app definition, sessions
     * without that annotation and sessions without a user are ignored. So are sessions that are being deleted, they are
     * about to release their instance. Instances are numbered from one, an annotation outside that range is not a
     * claim on anything this pool created.
     */
    static Map<Integer, Session> computeClaimedInstances(AppDefinition appDef, List<Session> sessions) {
        String appDefName = appDef.getMetadata().getName();
        Map<Integer, Session> claims = new LinkedHashMap<>();
        for (Session session : sessions) {
            SessionSpec spec = session.getSpec();
            if (spec == null || !appDefName.equals(spec.getAppDefinition())) {
                continue;
            }
            if (session.getMetadata() != null && session.getMetadata().getDeletionTimestamp() != null) {
                continue;
            }
            String user = spec.getUser();
            if (user == null || user.isBlank()) {
                continue;
            }
            Integer instanceId = parseClaimedInstanceId(session);
            if (instanceId != null && instanceId > 0) {
                claims.putIfAbsent(instanceId, session);
            }
        }
        return claims;
    }

    /**
     * Re-reads a claiming session right before its email is written back and runs it through the same claim rules
     * again, so that a session which was deleted or changed since the claims were listed does not get its email
     * restored on an instance it no longer holds.
     */
    private boolean stillClaimsInstance(AppDefinition appDef, int instanceId, Session listed) {
        Optional<Session> current = client.sessions().get(listed.getMetadata().getName());
        if (current.isEmpty()) {
            return false;
        }
        Session claiming = computeClaimedInstances(appDef, List.of(current.get())).get(instanceId);
        return claiming != null && listed.getMetadata().getUid().equals(claiming.getMetadata().getUid())
                && listed.getSpec().getUser().equals(claiming.getSpec().getUser());
    }

    private static Integer parseClaimedInstanceId(Session session) {
        if (session.getMetadata() == null || session.getMetadata().getAnnotations() == null) {
            return null;
        }
        String instanceId = session.getMetadata().getAnnotations()
                .get(EagerSessionHandler.SESSION_INSTANCE_ID_ANNOTATION);
        if (instanceId == null) {
            return null;
        }
        try {
            return Integer.valueOf(instanceId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int parseInstanceIdOrDefault(Service service, int defaultValue) {
        Integer id = parseInstanceId(service);
        return id != null ? id : defaultValue;
    }

    private int parseDeploymentInstanceIdOrDefault(AppDefinition appDef, Deployment deployment, int defaultValue) {
        Integer id = TheiaCloudDeploymentUtil.getId(null, appDef, deployment);
        return id != null ? id : defaultValue;
    }

    private int parseConfigMapInstanceIdOrDefault(AppDefinition appDef, ConfigMap configMap, int defaultValue) {
        Integer id = TheiaCloudConfigMapUtil.getProxyId(null, appDef, configMap);
        if (id != null) {
            return id;
        }
        id = TheiaCloudConfigMapUtil.getEmailId(null, appDef, configMap);
        return id != null ? id : defaultValue;
    }

    /**
     * Releases all pool resources for an app definition (used during deletion).
     */
    public boolean releaseAll(AppDefinition appDef, String correlationId) {
        String appDefName = appDef.getSpec().getName();
        ISpan span = Tracing.childSpan("pool.release_all", "Release all pool resources");

        span.setTag("app_definition", appDefName);

        LOGGER.info(formatLogMessage(correlationId, "Releasing all pool resources for " + appDefName));

        try {
            String ownerName = appDef.getMetadata().getName();
            String ownerUID = appDef.getMetadata().getUid();
            OwnerContext owner = OwnerContext.of(ownerName, ownerUID);

            boolean success = true;

            ISpan svcSpan = span.startChild("pool.release_services", "Release services");
            List<Service> services = K8sUtil.getExistingServices(client.kubernetes(), client.namespace(), ownerName,
                    ownerUID);
            svcSpan.setData("count", services.size());
            success &= ResourceLifecycleManager.releaseOwnership(services, owner,
                    s -> client.kubernetes().services().inNamespace(client.namespace()).resource(s), "service",
                    correlationId);
            svcSpan.setTag("outcome", success ? "success" : "failure"); Tracing.finish(svcSpan, success ? SpanStatus.OK : SpanStatus.INTERNAL_ERROR);

            ISpan cmSpan = span.startChild("pool.release_configmaps", "Release configmaps");
            List<ConfigMap> configMaps = K8sUtil.getExistingConfigMaps(client.kubernetes(), client.namespace(),
                    ownerName, ownerUID);
            cmSpan.setData("count", configMaps.size());
            boolean cmSuccess = ResourceLifecycleManager.releaseOwnership(configMaps, owner,
                    cm -> client.kubernetes().configMaps().inNamespace(client.namespace()).resource(cm), "configmap",
                    correlationId);
            success &= cmSuccess;
            cmSpan.setTag("outcome", cmSuccess ? "success" : "failure"); Tracing.finish(cmSpan, cmSuccess ? SpanStatus.OK : SpanStatus.INTERNAL_ERROR);

            ISpan deploySpan = span.startChild("pool.release_deployments", "Release deployments");
            List<Deployment> deployments = K8sUtil.getExistingDeployments(client.kubernetes(), client.namespace(),
                    ownerName, ownerUID);
            deploySpan.setData("count", deployments.size());
            boolean deploySuccess = ResourceLifecycleManager.releaseOwnership(deployments, owner,
                    d -> client.kubernetes().apps().deployments().inNamespace(client.namespace()).resource(d),
                    "deployment", correlationId);
            success &= deploySuccess;
            deploySpan.setTag("outcome", deploySuccess ? "success" : "failure"); Tracing.finish(deploySpan, deploySuccess ? SpanStatus.OK : SpanStatus.INTERNAL_ERROR);

            span.setTag("outcome", success ? "success" : "failure"); Tracing.finish(span, success ? SpanStatus.OK : SpanStatus.INTERNAL_ERROR);
            return success;

        } catch (Exception e) {
            Tracing.finishError(span, e);
            throw e;
        }
    }

    // ========== Instance Reservation ==========

    /**
     * Reserves a prewarmed instance for a session. Adds the session as an owner of the instance's resources.
     */
    public synchronized ReservationResult reserveInstance(Session session, AppDefinition appDef, String correlationId) {
        String sessionName = session.getSpec().getName();
        String appDefName = appDef.getSpec().getName();

        ISpan span = Tracing.childSpan("pool.reserve", "Reserve pool instance");

        span.setTag("app_definition", appDefName);
        span.setData("session_name", sessionName);

        LOGGER.info(formatLogMessage(correlationId, "Attempting to reserve instance for session " + sessionName));

        try {
            String appDefOwnerName = appDef.getMetadata().getName();
            String appDefOwnerUID = appDef.getMetadata().getUid();
            String sessionUID = session.getMetadata().getUid();

            // Get all services for this app definition
            List<Service> existingServices = K8sUtil.getExistingServices(client.kubernetes(), client.namespace(),
                    appDefOwnerName, appDefOwnerUID);

            // Separate external and internal services
            List<Service> externalServices = existingServices.stream()
                    .filter(s -> !s.getMetadata().getName().endsWith("-int")).collect(Collectors.toList());
            List<Service> internalServices = existingServices.stream()
                    .filter(s -> s.getMetadata().getName().endsWith("-int")).collect(Collectors.toList());

            // Track pool capacity
            int totalCapacity = externalServices.size();
            int availableCount = (int) externalServices.stream().filter(s -> TheiaCloudServiceUtil.isUnusedService(s))
                    .count();
            span.setData("pool.total_capacity", totalCapacity);
            span.setData("pool.available", availableCount);

            // Check if session already has a reservation
            Optional<Service> alreadyReservedExternal = TheiaCloudServiceUtil.getServiceOwnedBySession(sessionName,
                    sessionUID, externalServices);
            Optional<Service> alreadyReservedInternal = TheiaCloudServiceUtil.getServiceOwnedBySession(sessionName,
                    sessionUID, internalServices);

            if (alreadyReservedExternal.isPresent() && alreadyReservedInternal.isPresent()) {
                Integer extId = parseInstanceId(alreadyReservedExternal.get());
                Integer intId = parseInstanceId(alreadyReservedInternal.get());
                if (extId == null || intId == null || !extId.equals(intId)) {
                    LOGGER.error(formatLogMessage(correlationId, "Reservation mismatch for session " + sessionName));
                    span.setTag("pool.outcome", "error");;
                    span.setTag("outcome", "error"); Tracing.finish(span, SpanStatus.INTERNAL_ERROR);
                    return ReservationResult.error();
                }
                String deploymentName = TheiaCloudDeploymentUtil.getDeploymentName(appDef, extId);
                span.setTag("reservation.type", "already_reserved");
                span.setTag("pool.outcome", "success");
                span.setData("instance_id", extId);;
                Tracing.finishSuccess(span);
                return ReservationResult.success(new PoolInstance(extId, alreadyReservedExternal.get(),
                        alreadyReservedInternal.get(), deploymentName));
            }

            // Build instance maps
            Map<Integer, Service> externalByInstance = buildInstanceMap(externalServices);
            Map<Integer, Service> internalByInstance = buildInstanceMap(internalServices);

            // Handle partial reservation
            if (alreadyReservedExternal.isPresent() ^ alreadyReservedInternal.isPresent()) {
                span.setTag("reservation.type", "partial_recovery");
                ReservationResult result = handlePartialReservation(session, appDef, alreadyReservedExternal,
                        alreadyReservedInternal, externalByInstance, internalByInstance, correlationId);
                span.setTag("pool.outcome", result.getOutcome().name().toLowerCase());
                Integer instanceId = result.getInstance().map(PoolInstance::getInstanceId).orElse(null);
                if (instanceId != null) {
                    span.setData("instance_id", instanceId);
                }
                String outcome = result.getOutcome() == ReservationOutcome.SUCCESS ? "success"
                        : result.getOutcome().name().toLowerCase();
                SpanStatus status = result.getOutcome() == ReservationOutcome.SUCCESS ? SpanStatus.OK
                        : result.getOutcome() == ReservationOutcome.NO_CAPACITY ? SpanStatus.RESOURCE_EXHAUSTED
                        : SpanStatus.INTERNAL_ERROR;
                span.setTag("outcome", outcome);
                Tracing.finish(span, status);
                return result;
            }

            // Find available instance
            List<Integer> availableIds = externalByInstance.entrySet().stream()
                    .filter(e -> TheiaCloudServiceUtil.isUnusedService(e.getValue()) && !isClaimed(e.getValue()))
                    .filter(e -> {
                        Service internal = internalByInstance.get(e.getKey());
                        return internal != null && TheiaCloudServiceUtil.isUnusedService(internal)
                                && !isClaimed(internal) && hasDeployment(appDef, e.getKey());
                    }).map(Map.Entry::getKey).sorted(Comparator.naturalOrder()).collect(Collectors.toList());

            if (availableIds.isEmpty()) {
                LOGGER.info(formatLogMessage(correlationId, "No prewarmed instances available"));
                scheduleReconcile(appDef);
                span.setTag("reservation.type", "no_capacity");
                span.setTag("pool.outcome", "no_capacity");;
                span.setTag("outcome", "no_capacity"); Tracing.finish(span, SpanStatus.RESOURCE_EXHAUSTED);
                return ReservationResult.noCapacity();
            }

            int chosenInstance = availableIds.get(0);
            Service chosenExternal = externalByInstance.get(chosenInstance);
            Service chosenInternal = internalByInstance.get(chosenInstance);

            span.setTag("reservation.type", "new");
            span.setData("chosen_instance", chosenInstance);

            // Reserve both services
            ISpan reserveExtSpan = span.startChild("pool.reserve_external_service", "Reserve external service");
            try {
                reserveService(chosenExternal, sessionName, sessionUID, correlationId);
                Tracing.finishSuccess(reserveExtSpan);
            } catch (KubernetesClientException e) {
                LOGGER.error(formatLogMessage(correlationId, "Failed to reserve external service"), e);
                Tracing.finishError(reserveExtSpan, e);
                span.setTag("pool.outcome", "error");;
                span.setTag("outcome", "error"); Tracing.finish(span, SpanStatus.INTERNAL_ERROR);
                return ReservationResult.error();
            }

            ISpan reserveIntSpan = span.startChild("pool.reserve_internal_service", "Reserve internal service");
            try {
                reserveService(chosenInternal, sessionName, sessionUID, correlationId);
                Tracing.finishSuccess(reserveIntSpan);
            } catch (KubernetesClientException e) {
                LOGGER.error(formatLogMessage(correlationId, "Failed to reserve internal service"), e);
                Tracing.finishError(reserveIntSpan, e);
                rollbackReservation(chosenExternal, sessionName, sessionUID, correlationId);
                span.setTag("pool.outcome", "error");;
                span.setTag("outcome", "error"); Tracing.finish(span, SpanStatus.INTERNAL_ERROR);
                return ReservationResult.error();
            }

            String deploymentName = TheiaCloudDeploymentUtil.getDeploymentName(appDef, chosenInstance);
            span.setTag("pool.outcome", "success");
                span.setData("instance_id", chosenInstance);;
            Tracing.finishSuccess(span);
            return ReservationResult
                    .success(new PoolInstance(chosenInstance, chosenExternal, chosenInternal, deploymentName));

        } catch (Exception e) {
            Tracing.finishError(span, e);
            throw e;
        }
    }

    /**
     * Completes the session setup after reservation. Adds session labels, reserves deployment, configures email config.
     */
    public boolean completeSessionSetup(Session session, AppDefinition appDef, PoolInstance instance,
            String correlationId) {

        ISpan span = Tracing.childSpan("pool.complete_setup", "Complete session setup");

        span.setData("session_name", session.getSpec().getName());
        span.setData("instance_id", instance.getInstanceId());

        try {
            String sessionName = session.getMetadata().getName();
            String sessionUID = session.getMetadata().getUid();

            Map<String, String> sessionLabels = LabelsUtil.createSessionLabels(session, appDef);

            // Add labels to services
            ISpan labelSpan = span.startChild("pool.add_session_labels", "Add session labels to services");
            try {
                addSessionLabelsToService(instance.getExternalService(), sessionLabels, correlationId);
                addSessionLabelsToService(instance.getInternalService(), sessionLabels, correlationId);
                Tracing.finishSuccess(labelSpan);
            } catch (KubernetesClientException e) {
                LOGGER.error(formatLogMessage(correlationId, "Failed to add session labels to services"), e);
                Tracing.finishError(labelSpan, e);
                span.setTag("outcome", "failure"); Tracing.finish(span, SpanStatus.INTERNAL_ERROR);
                return false;
            }

            // Reserve deployment
            ISpan deploySpan = span.startChild("pool.reserve_deployment", "Reserve deployment");
            try {
                client.kubernetes().inNamespace(session.getMetadata().getNamespace()).apps().deployments().withName(instance.getDeploymentName()).edit(
                        d -> TheiaCloudHandlerUtil.addOwnerReferenceToItem(correlationId, sessionName, sessionUID, d));
                Tracing.finishSuccess(deploySpan);
            } catch (KubernetesClientException e) {
                LOGGER.error(formatLogMessage(correlationId, "Failed to reserve deployment"), e);
                Tracing.finishError(deploySpan, e);
                span.setTag("outcome", "failure"); Tracing.finish(span, SpanStatus.INTERNAL_ERROR);
                return false;
            }

            // Configure email config (if using Keycloak)
            if (arguments.isUseKeycloak()) {
                ISpan emailSpan = span.startChild("pool.configure_email", "Configure email config");
                String emailConfigName = TheiaCloudConfigMapUtil.getEmailConfigName(appDef, instance.getInstanceId());
                try {
                    client.kubernetes().inNamespace(session.getMetadata().getNamespace()).configMaps().withName(emailConfigName).edit(cm -> {
                        cm.setData(Collections.singletonMap(AddedHandlerUtil.FILENAME_AUTHENTICATED_EMAILS_LIST,
                                session.getSpec().getUser()));
                        return cm;
                    });
                    Tracing.finishSuccess(emailSpan);
                } catch (KubernetesClientException e) {
                    LOGGER.error(formatLogMessage(correlationId, "Failed to configure email config"), e);
                    Tracing.finishError(emailSpan, e);
                    span.setTag("outcome", "failure"); Tracing.finish(span, SpanStatus.INTERNAL_ERROR);
                    return false;
                }

                // Trigger pod refresh
                ISpan refreshSpan = span.startChild("pool.refresh_pods", "Trigger pod refresh");
                refreshPods(instance.getDeploymentName(), correlationId);
                Tracing.finishSuccess(refreshSpan);
            }

            Tracing.finishSuccess(span);
            return true;

        } catch (Exception e) {
            Tracing.finishError(span, e);
            throw e;
        }
    }

    /**
     * Releases an instance back to the pool (used when session ends). Removes session ownership and clears
     * session-specific data.
     */
    public synchronized boolean releaseInstance(Session session, AppDefinition appDef, String correlationId) {
        String sessionName = session.getSpec().getName();
        String appDefName = appDef.getSpec().getName();

        ISpan span = Tracing.childSpan("pool.release_instance", "Release pool instance");

        span.setTag("app_definition", appDefName);
        span.setData("session_name", sessionName);

        LOGGER.info(formatLogMessage(correlationId, "Releasing instance for session " + sessionName));

        try {
            String sessionUID = session.getMetadata().getUid();

            // The instance id the session recorded is authoritative: the labels of the services are only a fallback
            Integer instanceId = parseClaimedInstanceId(session);
            List<Service> services = instanceId != null ? servicesOfInstance(appDef, instanceId)
                    : findServicesByLabels(LabelsUtil.createSessionLabels(session, appDef), correlationId);

            if (services.isEmpty()) {
                LOGGER.error(formatLogMessage(correlationId,
                        "No services found for session " + session.getSpec().getName()));
                span.setTag("error.reason", "no_services_found");
                span.setTag("outcome", "failure"); Tracing.finish(span, SpanStatus.NOT_FOUND);
                return false;
            }

            List<Service> externalServices = services.stream().filter(s -> !s.getMetadata().getName().endsWith("-int"))
                    .collect(Collectors.toList());
            List<Service> internalServices = services.stream().filter(s -> s.getMetadata().getName().endsWith("-int"))
                    .collect(Collectors.toList());

            if (externalServices.size() != 1 || internalServices.size() != 1) {
                LOGGER.error(formatLogMessage(correlationId, "Expected 1 external and 1 internal service, found "
                        + externalServices.size() + " and " + internalServices.size()));
                span.setTag("error.reason", "service_count_mismatch");
                span.setData("external_count", externalServices.size());
                span.setData("internal_count", internalServices.size());
                span.setTag("outcome", "failure"); Tracing.finish(span, SpanStatus.INTERNAL_ERROR);
                return false;
            }

            Service externalService = externalServices.get(0);
            Service internalService = internalServices.get(0);

            if (instanceId == null) {
                instanceId = parseInstanceId(externalService);
            }
            if (instanceId == null) {
                LOGGER.error(formatLogMessage(correlationId, "Cannot determine instance ID from service"));
                span.setTag("error.reason", "cannot_parse_instance_id");
                span.setTag("outcome", "failure"); Tracing.finish(span, SpanStatus.INTERNAL_ERROR);
                return false;
            }
            span.setData("instance_id", instanceId);

            String claimant = externalService.getMetadata().getLabels() == null ? null
                    : externalService.getMetadata().getLabels().get(CLAIM_LABEL);
            if (claimant != null && !claimant.equals(sessionUID)) {
                // Reset already (e.g. as an orphaned claim) and claimed again since. Not this session's any more.
                LOGGER.warn(formatLogMessage(correlationId, "Instance " + instanceId
                        + " is claimed by another session, nothing to release for " + sessionName));
                Tracing.finishSuccess(span);
                return true;
            }

            boolean success = resetClaimedInstance(appDef, instanceId, externalService, internalService,
                    correlationId);

            span.setTag("outcome", success ? "success" : "failure"); Tracing.finish(span, success ? SpanStatus.OK : SpanStatus.INTERNAL_ERROR);
            return success;

        } catch (Exception e) {
            Tracing.finishError(span, e);
            throw e;
        }
    }

    /**
     * Resets an instance whose session is gone, with the pool lock held. Everything that reaches the next user is reset
     * before the claim label is removed: a free instance can be claimed at once. An instance that is shared with
     * sidecars through a volume, or that is outdated, is not reset but discarded and recreated, so its pod, volume and
     * resources are replaced by new ones.
     */
    private boolean resetClaimedInstance(AppDefinition appDef, int instanceId, Service externalService,
            Service internalService, String correlationId) {
        boolean success = true;
        String deploymentName = TheiaCloudDeploymentUtil.getDeploymentName(appDef, instanceId);

        // Clear email config
        if (arguments.isUseKeycloak()) {
            String emailConfigName = TheiaCloudConfigMapUtil.getEmailConfigName(appDef, instanceId);
            try {
                client.kubernetes().configMaps().withName(emailConfigName).edit(cm -> {
                    cm.setData(Collections.singletonMap(AddedHandlerUtil.FILENAME_AUTHENTICATED_EMAILS_LIST, null));
                    return cm;
                });
            } catch (KubernetesClientException e) {
                LOGGER.error(formatLogMessage(correlationId, "Failed to clear email config"), e);
                success = false;
            }
        }

        if (SidecarConfig.requiresSharedWorkspace(appDef) || needsRecreation(appDef, instanceId)) {
            discardInstance(appDef, instanceId, externalService, internalService, deploymentName, correlationId);
            return success;
        }

        // Replace the pod and rotate the data bridge token
        resetPod(appDef, instanceId, deploymentName, correlationId);

        // Clean up deployment
        try {
            client.kubernetes().apps().deployments().withName(deploymentName).edit(d -> {
                removeSessionOwners(d);
                return d;
            });
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId, "Failed to clean up deployment"), e);
            success = false;
        }

        // Clean up services: the claim label goes last, from here on the instance can be claimed
        success &= cleanupService(externalService, correlationId);
        success &= cleanupService(internalService, correlationId);
        return success;
    }

    /** Outdated or incomplete instances are not worth resetting, a new one is built in their place. */
    boolean needsRecreation(AppDefinition appDef, int instanceId) {
        String ownerName = appDef.getMetadata().getName();
        String ownerUID = appDef.getMetadata().getUid();
        long generation = appDef.getMetadata().getGeneration();
        List<Deployment> deployments = K8sUtil.getExistingDeployments(client.kubernetes(), client.namespace(),
                ownerName, ownerUID).stream()
                .filter(d -> instanceId == parseDeploymentInstanceIdOrDefault(appDef, d, -1))
                .collect(Collectors.toList());
        List<ConfigMap> configMaps = K8sUtil
                .getExistingConfigMaps(client.kubernetes(), client.namespace(), ownerName, ownerUID).stream()
                .filter(cm -> instanceId == parseConfigMapInstanceIdOrDefault(appDef, cm, -1))
                .collect(Collectors.toList());
        List<Service> services = servicesOfInstance(appDef, instanceId);
        return deployments.isEmpty() || isOutdated(services, generation) || isOutdated(deployments, generation)
                || isOutdated(configMaps, generation)
                || deployments.stream().anyMatch(d -> lacksDataBridgeToken(appDef, d));
    }

    /**
     * Takes an instance away completely: no services to claim, no deployment that could start a pod on an old volume,
     * and a rotated data bridge token. The reconcile that follows builds a new instance.
     */
    private void discardInstance(AppDefinition appDef, int instanceId, Service externalService,
            Service internalService, String deploymentName, String correlationId) {
        if (DataBridgeUtil.isDataBridgeEnabled(appDef.getSpec())
                && !dataBridgeTokens.rotate(appDef, instanceId, correlationId)) {
            deleteDataBridgeToken(appDef, instanceId, correlationId);
        }
        // The pod goes with the deployment. The services are deleted last: while they exist the instance is claimed.
        try {
            client.kubernetes().apps().deployments().inNamespace(client.namespace()).withName(deploymentName).delete();
        } catch (KubernetesClientException e) {
            LOGGER.warn(formatLogMessage(correlationId, "Failed to delete deployment " + deploymentName), e);
        }
        sidecarManager.deletePrewarmedSidecars(appDef, instanceId, correlationId);
        if (SidecarConfig.requiresSharedWorkspace(appDef)) {
            deleteInstancePvc(appDef, instanceId, correlationId);
        }
        for (Service service : List.of(externalService, internalService)) {
            try {
                client.kubernetes().services().inNamespace(client.namespace()).withName(service.getMetadata().getName())
                        .delete();
            } catch (KubernetesClientException e) {
                LOGGER.warn(formatLogMessage(correlationId,
                        "Failed to delete service " + service.getMetadata().getName()), e);
            }
        }
    }

    /**
     * Resets instances that are marked as claimed but whose session no longer exists, which happens when the operator
     * missed the deletion event (it was down). Also marks instances of live claims that predate the claim label.
     */
    synchronized void resetOrphanedClaims(AppDefinition appDef, String correlationId) {
        String appDefName = appDef.getMetadata().getName();
        List<Session> sessions = client.sessions().list();
        Set<String> liveSessions = sessions.stream()
                .filter(s -> s.getSpec() != null && appDefName.equals(s.getSpec().getAppDefinition()))
                .map(s -> s.getMetadata().getUid()).collect(Collectors.toSet());

        List<Service> services = K8sUtil.getExistingServices(client.kubernetes(), client.namespace(), appDefName,
                appDef.getMetadata().getUid());
        for (Service external : services) {
            if (external.getMetadata().getName().endsWith("-int") || !isClaimed(external)) {
                continue;
            }
            Integer instanceId = parseInstanceId(external);
            if (instanceId == null || liveSessions.contains(external.getMetadata().getLabels().get(CLAIM_LABEL))) {
                continue;
            }
            List<Service> instanceServices = servicesOfInstance(appDef, instanceId);
            Optional<Service> internal = instanceServices.stream()
                    .filter(s -> s.getMetadata().getName().endsWith("-int")).findAny();
            if (internal.isEmpty()) {
                continue;
            }
            LOGGER.warn(formatLogMessage(correlationId,
                    "Instance " + instanceId + " is claimed by a session that no longer exists, resetting it"));
            resetClaimedInstance(appDef, instanceId, external, internal.get(), correlationId);
        }

        for (Map.Entry<Integer, Session> claim : computeClaimedInstances(appDef, sessions).entrySet()) {
            for (Service service : servicesOfInstance(appDef, claim.getKey())) {
                if (!isClaimed(service)) {
                    String uid = claim.getValue().getMetadata().getUid();
                    client.services().inNamespace(client.namespace()).withName(service.getMetadata().getName())
                            .edit(s -> {
                                if (s.getMetadata().getLabels() == null) {
                                    s.getMetadata().setLabels(new HashMap<>());
                                }
                                s.getMetadata().getLabels().put(CLAIM_LABEL, uid);
                                return s;
                            });
                }
            }
        }
    }

    /** Ids of the instances that carry a claim, from the live services. */
    private Set<Integer> claimedInstanceIds(AppDefinition appDef) {
        return K8sUtil.getExistingServices(client.kubernetes(), client.namespace(), appDef.getMetadata().getName(),
                appDef.getMetadata().getUid()).stream().filter(PrewarmedResourcePool::isClaimed)
                .map(this::parseInstanceId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    // ========== Background work ==========

    /**
     * Recreates a discarded instance in the background and retries with a growing delay until it exists. A recreation
     * can wait for the old volume to go, which must not block the thread of the session that was deleted. There is no
     * periodic reconcile, so this is also what repairs an instance when a first attempt fails.
     */
    public void scheduleInstanceRecreation(AppDefinition appDef, int instanceId, String correlationId) {
        BACKGROUND.execute(() -> recreateInstance(appDef.getMetadata().getName(), instanceId, correlationId, 0));
    }

    private void recreateInstance(String appDefName, int instanceId, String correlationId, int attempt) {
        try {
            Optional<AppDefinition> current = client.appDefinitions().get(appDefName);
            if (current.isEmpty()) {
                return;
            }
            AppDefinition appDef = current.get();
            reconcileInstance(appDef, instanceId, correlationId);
            boolean done = instanceId > appDef.getSpec().getMinInstances() || isInstanceComplete(appDef, instanceId);
            if (!done && attempt < MAX_RECREATION_ATTEMPTS) {
                long delay = Math.min(5L << attempt, 120L);
                BACKGROUND.schedule(() -> recreateInstance(appDefName, instanceId, correlationId, attempt + 1), delay,
                        TimeUnit.SECONDS);
            } else if (!done) {
                LOGGER.error(formatLogMessage(correlationId,
                        "Giving up recreating instance " + instanceId + " of " + appDefName));
            }
        } catch (Exception e) {
            LOGGER.error(formatLogMessage(correlationId, "Recreating instance " + instanceId + " failed"), e);
            if (attempt < MAX_RECREATION_ATTEMPTS) {
                BACKGROUND.schedule(() -> recreateInstance(appDefName, instanceId, correlationId, attempt + 1),
                        Math.min(5L << attempt, 120L), TimeUnit.SECONDS);
            }
        }
    }

    private boolean isInstanceComplete(AppDefinition appDef, int instanceId) {
        return hasDeployment(appDef, instanceId) && servicesOfInstance(appDef, instanceId).size() == 2;
    }

    /** A pool without a free instance reconciles itself once, so a lost instance does not block launches for good. */
    private void scheduleReconcile(AppDefinition appDef) {
        String name = appDef.getMetadata().getName();
        if (!pendingReconciles.add(name)) {
            return;
        }
        BACKGROUND.schedule(() -> {
            pendingReconciles.remove(name);
            try {
                client.appDefinitions().get(name).ifPresent(current -> reconcile(current,
                        current.getSpec().getMinInstances(), "pool-no-capacity"));
            } catch (Exception e) {
                LOGGER.error(formatLogMessage("pool-no-capacity", "Reconciling the pool of " + name + " failed"), e);
            }
        }, 5, TimeUnit.SECONDS);
    }

    // ========== Helper Methods ==========

    private Map<Integer, Service> buildInstanceMap(List<Service> services) {
        Map<Integer, Service> map = new HashMap<>();
        for (Service s : services) {
            Integer id = parseInstanceId(s);
            if (id != null) {
                map.putIfAbsent(id, s);
            }
        }
        return map;
    }

    private Integer parseInstanceId(Service service) {
        String id = TheiaCloudK8sUtil.extractIdFromName(service.getMetadata());
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private ReservationResult handlePartialReservation(Session session, AppDefinition appDef,
            Optional<Service> reservedExternal, Optional<Service> reservedInternal, Map<Integer, Service> externalMap,
            Map<Integer, Service> internalMap, String correlationId) {

        String sessionName = session.getMetadata().getName();
        String sessionUID = session.getMetadata().getUid();

        Service reserved = reservedExternal.orElseGet(reservedInternal::get);
        Integer instance = parseInstanceId(reserved);
        if (instance == null) {
            LOGGER.error(formatLogMessage(correlationId, "Cannot parse instance from partially reserved service"));
            rollbackReservation(reserved, sessionName, sessionUID, correlationId);
            return ReservationResult.error();
        }

        Service counterpart = reservedExternal.isPresent() ? internalMap.get(instance) : externalMap.get(instance);
        if (counterpart == null || !TheiaCloudServiceUtil.isUnusedService(counterpart) || isClaimed(counterpart)) {
            LOGGER.warn(formatLogMessage(correlationId, "Partial reservation cannot be completed, rolling back"));
            rollbackReservation(reserved, sessionName, sessionUID, correlationId);
            return ReservationResult.noCapacity();
        }

        try {
            reserveService(counterpart, sessionName, sessionUID, correlationId);
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId, "Failed to complete partial reservation"), e);
            rollbackReservation(reserved, sessionName, sessionUID, correlationId);
            return ReservationResult.error();
        }

        Service ext = reservedExternal.orElse(counterpart);
        Service in = reservedInternal.orElse(counterpart);
        String deploymentName = TheiaCloudDeploymentUtil.getDeploymentName(appDef, instance);
        return ReservationResult.success(new PoolInstance(instance, ext, in, deploymentName));
    }

    private void reserveService(Service service, String sessionName, String sessionUID, String correlationId) {
        client.services().inNamespace(client.namespace()).withName(service.getMetadata().getName()).edit(s -> {
            TheiaCloudHandlerUtil.addOwnerReferenceToItem(correlationId, sessionName, sessionUID, s);
            if (s.getMetadata().getLabels() == null) {
                s.getMetadata().setLabels(new HashMap<>());
            }
            s.getMetadata().getLabels().put(CLAIM_LABEL, sessionUID);
            return s;
        });
    }

    private void rollbackReservation(Service service, String sessionName, String sessionUID, String correlationId) {
        try {
            client.services().inNamespace(client.namespace()).withName(service.getMetadata().getName()).edit(s -> {
                TheiaCloudHandlerUtil.removeOwnerReferenceFromItem(correlationId, sessionName, sessionUID, s);
                if (s.getMetadata().getLabels() != null) {
                    s.getMetadata().getLabels().remove(CLAIM_LABEL);
                }
                return s;
            });
        } catch (KubernetesClientException e) {
            LOGGER.warn(formatLogMessage(correlationId, "Failed to rollback reservation"), e);
        }
    }

    private void addSessionLabelsToService(Service service, Map<String, String> labels, String correlationId) {
        client.services().inNamespace(client.namespace()).withName(service.getMetadata().getName()).edit(s -> {
            Map<String, String> existing = s.getMetadata().getLabels();
            if (existing == null) {
                existing = new HashMap<>();
                s.getMetadata().setLabels(existing);
            }
            existing.putAll(labels);
            return s;
        });
    }

    private boolean cleanupService(Service service, String correlationId) {
        int attempts = 0;
        while (attempts < 3) {
            try {
                client.services().inNamespace(service.getMetadata().getNamespace()).withName(service.getMetadata().getName()).edit(s -> {
                    removeSessionOwners(s);
                    if (s.getMetadata().getLabels() != null) {
                        s.getMetadata().getLabels().keySet().removeAll(LabelsUtil.getSessionSpecificLabelKeys());
                        s.getMetadata().getLabels().remove(CLAIM_LABEL);
                    }
                    return s;
                });
                return true;
            } catch (KubernetesClientException e) {
                attempts++;
                if (attempts >= 3) {
                    LOGGER.error(formatLogMessage(correlationId, "Failed to cleanup service after 3 attempts"), e);
                    return false;
                }
            }
        }
        return false;
    }

    private static void removeSessionOwners(HasMetadata item) {
        if (item.getMetadata().getOwnerReferences() != null) {
            item.getMetadata().getOwnerReferences().removeIf(owner -> Session.KIND.equals(owner.getKind()));
        }
    }

    private boolean hasDeployment(AppDefinition appDef, int instanceId) {
        return client.kubernetes().apps().deployments().inNamespace(client.namespace())
                .withName(TheiaCloudDeploymentUtil.getDeploymentName(appDef, instanceId)).get() != null;
    }

    private List<Service> servicesOfInstance(AppDefinition appDef, int instanceId) {
        String external = TheiaCloudServiceUtil.getServiceName(appDef, instanceId);
        String internal = TheiaCloudServiceUtil.getInternalServiceName(appDef, instanceId);
        return K8sUtil.getExistingServices(client.kubernetes(), client.namespace(), appDef.getMetadata().getName(),
                appDef.getMetadata().getUid()).stream()
                .filter(s -> external.equals(s.getMetadata().getName()) || internal.equals(s.getMetadata().getName()))
                .collect(Collectors.toList());
    }

    private List<Service> findServicesByLabels(Map<String, String> labels, String correlationId) {
        String labelSelector = labels.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
        return client.kubernetes().services().inNamespace(client.namespace()).withLabelSelector(labelSelector).list()
                .getItems();
    }

    private void refreshPods(String deploymentName, String correlationId) {
        try {
            // Get deployment to extract selector labels
            Deployment deployment = client.kubernetes().apps().deployments().inNamespace(client.namespace())
                    .withName(deploymentName).get();
            if (deployment == null || deployment.getSpec() == null || deployment.getSpec().getSelector() == null
                    || deployment.getSpec().getSelector().getMatchLabels() == null) {
                return;
            }

            // Build label selector from deployment's matchLabels
            Map<String, String> selectorLabels = deployment.getSpec().getSelector().getMatchLabels();
            String labelSelector = selectorLabels.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(","));

            // Query pods using label selector
            client.kubernetes().pods().inNamespace(client.namespace()).withLabelSelector(labelSelector).list()
                    .getItems().forEach(pod -> {
                        // Null-check metadata and ownerReferences before streaming
                        if (pod.getMetadata() == null || pod.getMetadata().getOwnerReferences() == null) {
                            return;
                        }
                        // Check if pod belongs to this deployment via owner references
                        if (pod.getMetadata().getOwnerReferences().stream()
                                .anyMatch(or -> or.getName().startsWith(deploymentName))) {
                            // Null-check and initialize annotations before mutating
                            if (pod.getMetadata().getAnnotations() == null) {
                                pod.getMetadata().setAnnotations(new HashMap<>());
                            }
                            pod.getMetadata().getAnnotations().put(EAGER_START_REFRESH_ANNOTATION,
                                    Instant.now().toString());
                            PodResource podResource = client.pods().inNamespace(client.namespace())
                                    .withName(pod.getMetadata().getName());
                            podResource.edit(p -> pod);
                        }
                    });
        } catch (KubernetesClientException e) {
            LOGGER.warn(formatLogMessage(correlationId, "Failed to refresh pods"), e);
        }
    }

    /**
     * The previous user could read the data bridge token in their pod, so the next pod has to start with a new one.
     * If the new token cannot be written, the deployment is replaced instead: the replacement either gets a fresh
     * token or none at all, and a bridge without a token rejects every injection.
     */
    void resetPod(AppDefinition appDef, int instanceId, String deploymentName, String correlationId) {
        if (DataBridgeUtil.isDataBridgeEnabled(appDef.getSpec())
                && !dataBridgeTokens.rotate(appDef, instanceId, correlationId)) {
            LOGGER.error(formatLogMessage(correlationId,
                    "Replacing the deployment of instance " + instanceId + ", its data bridge token was not rotated"));
            client.kubernetes().apps().deployments().inNamespace(client.namespace()).withName(deploymentName).delete();
            Optional<String> pvcName = createInstancePvc(appDef, instanceId, correlationId);
            resourceFactory.createDeploymentForEagerInstance(appDef, instanceId, pvcName, new HashMap<>(),
                    correlationId);
            return;
        }
        deletePod(deploymentName, correlationId);
    }

    private void deleteDataBridgeToken(AppDefinition appDef, int instanceId, String correlationId) {
        if (DataBridgeUtil.isDataBridgeEnabled(appDef.getSpec())) {
            dataBridgeTokens.delete(appDef, instanceId, correlationId);
        }
    }

    void deletePod(String deploymentName, String correlationId) {
        try {
            Deployment deployment = client.kubernetes().apps().deployments().inNamespace(client.namespace())
                    .withName(deploymentName).get();
            Map<String, String> selector = deployment == null || deployment.getSpec() == null
                    || deployment.getSpec().getSelector() == null ? null
                            : deployment.getSpec().getSelector().getMatchLabels();
            // Every pod of the deployment, also the ones a rollout left behind
            List<Pod> pods = selector != null && !selector.isEmpty()
                    ? client.kubernetes().pods().inNamespace(client.namespace()).withLabels(selector).list()
                            .getItems()
                    : client.kubernetes().pods().list().getItems().stream()
                            .filter(p -> p.getMetadata().getName().startsWith(deploymentName))
                            .collect(Collectors.toList());
            for (Pod pod : pods) {
                LOGGER.info(formatLogMessage(correlationId, "Deleting pod " + pod.getMetadata().getName()));
                client.pods().withName(pod.getMetadata().getName()).delete();
            }
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId, "Failed to delete pod"), e);
        }
    }

    // ========== PVC Management ==========

    private static final String TEMPLATE_PERSISTENTVOLUMECLAIM_YAML = "/templatePersistentVolumeClaim.yaml";
    private static final int PVC_DELETION_WAIT_ATTEMPTS = 120;
    private static final long PVC_DELETION_WAIT_MILLIS = 1000;

    static String getInstancePvcName(AppDefinition appDef, int instanceId) {
        return NamingUtil.createNameWithSuffix(appDef, instanceId, "pvc");
    }

    /**
     * Creates a PersistentVolumeClaim for an eager-start pool instance. The PVC is owned by the AppDefinition so it
     * survives session lifecycle and is cleaned up when the AppDefinition is deleted or the instance is scaled down.
     *
     * @return the PVC name if created successfully, or empty if creation fails
     */
    private Optional<String> createInstancePvc(AppDefinition appDef, int instanceId, String correlationId) {
        if (!SidecarConfig.requiresSharedWorkspace(appDef)) {
            return Optional.empty();
        }

        String pvcName = getInstancePvcName(appDef, instanceId);

        Optional<io.fabric8.kubernetes.api.model.PersistentVolumeClaim> existingPvc = client
                .persistentVolumeClaimsClient().get(pvcName);
        if (existingPvc.isPresent()
                && existingPvc.get().getMetadata() != null
                && existingPvc.get().getMetadata().getDeletionTimestamp() == null) {
            LOGGER.info(formatLogMessage(correlationId,
                    "PVC " + pvcName + " already exists for instance " + instanceId));
            return Optional.of(pvcName);
        }

        if (existingPvc.isPresent()) {
            LOGGER.warn(formatLogMessage(correlationId,
                    "PVC " + pvcName + " is terminating for instance " + instanceId
                            + ". Waiting for deletion before recreation."));
            if (!waitUntilPvcDeleted(pvcName, correlationId)) {
                LOGGER.error(formatLogMessage(correlationId,
                        "PVC " + pvcName + " is still present/terminating after waiting. Skipping recreation."));
                return Optional.empty();
            }
        }

        ISpan span = Tracing.childSpan("pool.create_pvc", "Create instance PVC");
        span.setData("pvc_name", pvcName);
        span.setData("instance_id", instanceId);

        LOGGER.info(formatLogMessage(correlationId,
                "Creating PVC " + pvcName + " for pool instance " + instanceId));

        try {
            Map<String, String> replacements = new LinkedHashMap<>();
            replacements.put(DefaultPersistentVolumeTemplateReplacements.PLACEHOLDER_PERSISTENTVOLUMENAME, pvcName);
            replacements.put(TheiaCloudHandlerUtil.PLACEHOLDER_NAMESPACE, client.namespace());
            replacements.put(DefaultPersistentVolumeTemplateReplacements.PLACEHOLDER_LABEL_WORKSPACE_NAME,
                    appDef.getSpec().getName() + "-instance-" + instanceId);
            replacements.put(DefaultPersistentVolumeTemplateReplacements.PLACEHOLDER_STORAGE_CLASS_NAME,
                    arguments.getStorageClassName() != null ? arguments.getStorageClassName() : "");
            replacements.put(DefaultPersistentVolumeTemplateReplacements.PLACEHOLDER_REQUESTED_STORAGE,
                    arguments.getRequestedStorage() != null ? arguments.getRequestedStorage()
                            : DefaultPersistentVolumeTemplateReplacements.DEFAULT_REQUESTED_STORAGE);
            replacements.put(DefaultPersistentVolumeTemplateReplacements.PLACEHOLDER_ACCESS_MODE,
                    DefaultPersistentVolumeTemplateReplacements.READ_WRITE_MANY);

            String pvcYaml = JavaResourceUtil.readResourceAndReplacePlaceholders(
                    TEMPLATE_PERSISTENTVOLUMECLAIM_YAML, replacements, correlationId);

            Optional<io.fabric8.kubernetes.api.model.PersistentVolumeClaim> result =
                    client.persistentVolumeClaimsClient().loadAndCreate(correlationId, pvcYaml,
                            claim -> claim.addOwnerReference(appDef));

            if (result.isPresent()) {
                Tracing.finishSuccess(span);
                LOGGER.info(formatLogMessage(correlationId,
                        "Successfully created PVC " + pvcName));
                return Optional.of(pvcName);
            } else {
                span.setTag("outcome", "failure");
                Tracing.finish(span, SpanStatus.INTERNAL_ERROR);
                LOGGER.error(formatLogMessage(correlationId,
                        "Failed to create PVC " + pvcName));
                return Optional.empty();
            }

        } catch (IOException | URISyntaxException e) {
            LOGGER.error(formatLogMessage(correlationId,
                    "Error loading PVC template for instance " + instanceId), e);
            Tracing.finishError(span, e);
            return Optional.empty();
        } catch (RuntimeException e) {
            LOGGER.error(formatLogMessage(correlationId,
                    "Unexpected error creating PVC for instance " + instanceId), e);
            Tracing.finishError(span, e);
            return Optional.empty();
        }
    }

    private void deleteInstancePvc(AppDefinition appDef, int instanceId, String correlationId) {
        String pvcName = getInstancePvcName(appDef, instanceId);

        LOGGER.info(formatLogMessage(correlationId,
                "Deleting PVC " + pvcName + " for pool instance " + instanceId));

        try {
            client.kubernetes().persistentVolumeClaims()
                    .inNamespace(client.namespace())
                    .withName(pvcName)
                    .delete();
            LOGGER.info(formatLogMessage(correlationId, "Deleted PVC " + pvcName));
        } catch (KubernetesClientException e) {
            LOGGER.warn(formatLogMessage(correlationId, "Failed to delete PVC " + pvcName), e);
        }
    }

    private boolean waitUntilPvcDeleted(String pvcName, String correlationId) {
        for (int attempt = 1; attempt <= PVC_DELETION_WAIT_ATTEMPTS; attempt++) {
            if (client.persistentVolumeClaimsClient().get(pvcName).isEmpty()) {
                return true;
            }

            try {
                Thread.sleep(PVC_DELETION_WAIT_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn(formatLogMessage(correlationId,
                        "Interrupted while waiting for PVC deletion: " + pvcName), e);
                return false;
            }
        }

        return client.persistentVolumeClaimsClient().get(pvcName).isEmpty();
    }
}
