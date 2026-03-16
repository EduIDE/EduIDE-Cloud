# Subtask 3 — Eager Path: Sidecar Env Injection + Lifecycle Fix

## Your Mission

Fix two problems in the eager (prewarmed pool) path:

1. **Env var injection**: Prewarmed Theia deployments are created WITHOUT sidecar env vars (`SIDECAR_*_HOST`, `SIDECAR_*_PORT`, `SIDECAR_CONFIG`). The env vars must be injected into the Theia Deployment at creation time, and sidecar Service+Deployment must exist BEFORE the Theia Deployment so Kubernetes DNS resolves the service names.

2. **Lifecycle on session release**: Currently `EagerSessionHandler.sessionDeleted()` calls `sidecarManager.deletePrewarmedSidecars()` which deletes the sidecar Deployment AND Service. This is wrong — on session release, we should only restart the sidecar Pod (delete the Pod, let the Deployment recreate it), keeping Deployment+Service alive for reuse when the pool instance is recycled.

**Working directory**: `/Users/nikolas/BA Workdir/EduIDE-Cloud`
**Branch**: `feature/external-ls-v2` (already checked out)

---

## CHANGE 1: Reorder sidecar creation BEFORE Theia deployment in `ensureCapacity`

**File**: `java/operator/org.eclipse.theia.cloud.operator/src/main/java/org/eclipse/theia/cloud/operator/pool/PrewarmedResourcePool.java`

In `ensureCapacity()`, move the sidecar capacity call from AFTER the deployment creation block to BEFORE it.

**Current code** (lines 257–283):
```java
            // Create missing deployments (with shared PVC)
            if (!missingDeploymentIds.isEmpty()) {
                ISpan deploySpan = span.startChild("pool.create_deployments", "Create missing deployments");
                // ... deployment creation loop ...
                Tracing.finish(deploySpan, failed == 0 ? SpanStatus.OK : SpanStatus.INTERNAL_ERROR);
            }

            success &= sidecarManager.ensurePrewarmedSidecarCapacity(appDef, minInstances, labels, correlationId);
```

**New code** — move the sidecar line to BEFORE the deployment block:
```java
            // Create sidecar resources BEFORE Theia deployments so DNS resolves at pod startup
            success &= sidecarManager.ensurePrewarmedSidecarCapacity(appDef, minInstances, labels, correlationId);

            // Create missing deployments (with shared PVC)
            if (!missingDeploymentIds.isEmpty()) {
                ISpan deploySpan = span.startChild("pool.create_deployments", "Create missing deployments");
                // ... (keep the entire deployment loop unchanged) ...
                Tracing.finish(deploySpan, failed == 0 ? SpanStatus.OK : SpanStatus.INTERNAL_ERROR);
            }
```

Delete the old `success &= sidecarManager.ensurePrewarmedSidecarCapacity(...)` line that was after the deployment block.

---

## CHANGE 2: Reorder sidecar reconciliation BEFORE deployment reconciliation in `reconcile`

**Same file**: `PrewarmedResourcePool.java`

In `reconcile()`, move the sidecar reconciliation call from AFTER the deployment reconciliation (line 503) to BEFORE it (before the `// Reconcile deployments` comment at line 461).

**Current code** (lines 461–503):
```java
            // Reconcile deployments
            ISpan deploySpan = span.startChild("pool.reconcile_deployments", "Reconcile deployments");
            // ... deployment reconciliation ...
            deploySpan.finish();

            success &= sidecarManager.reconcilePrewarmedSidecars(appDef, targetInstances, labels, correlationId);
```

**New code**:
```java
            // Reconcile sidecar resources BEFORE Theia deployments so DNS resolves at pod startup
            success &= sidecarManager.reconcilePrewarmedSidecars(appDef, targetInstances, labels, correlationId);

            // Reconcile deployments
            ISpan deploySpan = span.startChild("pool.reconcile_deployments", "Reconcile deployments");
            // ... (keep the entire deployment reconciliation unchanged) ...
            deploySpan.finish();
```

Delete the old `success &= sidecarManager.reconcilePrewarmedSidecars(...)` line that was after the deploy block.

---

## CHANGE 3: Reorder sidecar creation BEFORE deployment in `createInstanceResources`

**Same file**: `PrewarmedResourcePool.java`

In `createInstanceResources()` (line 659), move the sidecar call from AFTER the deployment to BEFORE it.

**Current code** (lines 659–675):
```java
    private void createInstanceResources(AppDefinition appDef, int instanceId, String correlationId) {
        Map<String, String> labels = new HashMap<>();

        resourceFactory.createServiceForEagerInstance(appDef, instanceId, labels, correlationId);
        resourceFactory.createInternalServiceForEagerInstance(appDef, instanceId, labels, correlationId);

        if (arguments.isUseKeycloak()) {
            resourceFactory.createProxyConfigMapForEagerInstance(appDef, instanceId, labels, correlationId);
            resourceFactory.createEmailConfigMapForEagerInstance(appDef, instanceId, labels, correlationId);
        }

        Optional<String> pvcName = createInstancePvc(appDef, instanceId, correlationId);

        resourceFactory.createDeploymentForEagerInstance(appDef, instanceId, pvcName, labels, correlationId);

        sidecarManager.createPrewarmedSidecars(appDef, instanceId, pvcName, labels, correlationId);
    }
```

**New code**:
```java
    private void createInstanceResources(AppDefinition appDef, int instanceId, String correlationId) {
        Map<String, String> labels = new HashMap<>();

        resourceFactory.createServiceForEagerInstance(appDef, instanceId, labels, correlationId);
        resourceFactory.createInternalServiceForEagerInstance(appDef, instanceId, labels, correlationId);

        if (arguments.isUseKeycloak()) {
            resourceFactory.createProxyConfigMapForEagerInstance(appDef, instanceId, labels, correlationId);
            resourceFactory.createEmailConfigMapForEagerInstance(appDef, instanceId, labels, correlationId);
        }

        Optional<String> pvcName = createInstancePvc(appDef, instanceId, correlationId);

        // Create sidecar Deployment+Service BEFORE Theia Deployment so DNS resolves at pod startup
        sidecarManager.createPrewarmedSidecars(appDef, instanceId, pvcName, labels, correlationId);

        resourceFactory.createDeploymentForEagerInstance(appDef, instanceId, pvcName, labels, correlationId);
    }
```

---

## CHANGE 4: Inject sidecar env vars into eager Theia deployments via `K8sResourceFactory`

**File**: `java/operator/org.eclipse.theia.cloud.operator/src/main/java/org/eclipse/theia/cloud/operator/util/K8sResourceFactory.java`

The `createDeploymentForEagerInstance` method uses a callback lambda to customize the Deployment before it's submitted to K8s. We need to add `SidecarManager.injectPrewarmedSidecarEnvVars()` inside this callback.

**Problem**: `K8sResourceFactory` doesn't currently have a reference to `SidecarManager`, and injecting it would create a circular dependency risk. Instead, add `SidecarManager` and the `instanceId` parameter to the method signature.

### Step 4A: Add SidecarManager field to K8sResourceFactory

Add import:
```java
import org.eclipse.theia.cloud.operator.sidecar.SidecarManager;
```

Add field (alongside the other `@Inject` fields):
```java
    @Inject
    private SidecarManager sidecarManager;
```

### Step 4B: Modify `createDeploymentForEagerInstance` to inject sidecar env vars

The 5-arg method (lines 152–175) has a lambda that customizes the deployment. Add a `sidecarManager.injectPrewarmedSidecarEnvVars()` call inside that lambda.

**Current code** (lines 152–175):
```java
    public Optional<Deployment> createDeploymentForEagerInstance(AppDefinition appDef, int instance,
            Optional<String> pvcName, Map<String, String> labels, String correlationId) {

        Map<String, String> replacements = deploymentReplacements.getReplacements(client.namespace(), appDef, instance);

        String template = arguments.isUseKeycloak() ? AddedHandlerUtil.TEMPLATE_DEPLOYMENT_YAML
                : AddedHandlerUtil.TEMPLATE_DEPLOYMENT_WITHOUT_AOUTH2_PROXY_YAML;

        Map<String, String> labelsWithGeneration = addGenerationLabel(labels, appDef);

        return createDeployment(template, replacements, OwnerContext.of(appDef.getMetadata().getName(),
                appDef.getMetadata().getUid(), AppDefinition.API, AppDefinition.KIND), labelsWithGeneration,
                deployment -> {
                    if (pvcName.isPresent()) {
                        addVolumeClaim(deployment, pvcName.get(), appDef.getSpec());
                    }
                    bandwidthLimiter.limit(deployment, appDef.getSpec().getDownlinkLimit(),
                            appDef.getSpec().getUplinkLimit(), correlationId);
                    AddedHandlerUtil.removeEmptyResources(deployment);
                    if (appDef.getSpec().getPullSecret() != null && !appDef.getSpec().getPullSecret().isEmpty()) {
                        AddedHandlerUtil.addImagePullSecret(deployment, appDef.getSpec().getPullSecret());
                    }
                }, correlationId);
    }
```

**New code**:
```java
    public Optional<Deployment> createDeploymentForEagerInstance(AppDefinition appDef, int instance,
            Optional<String> pvcName, Map<String, String> labels, String correlationId) {

        Map<String, String> replacements = deploymentReplacements.getReplacements(client.namespace(), appDef, instance);

        String template = arguments.isUseKeycloak() ? AddedHandlerUtil.TEMPLATE_DEPLOYMENT_YAML
                : AddedHandlerUtil.TEMPLATE_DEPLOYMENT_WITHOUT_AOUTH2_PROXY_YAML;

        Map<String, String> labelsWithGeneration = addGenerationLabel(labels, appDef);

        return createDeployment(template, replacements, OwnerContext.of(appDef.getMetadata().getName(),
                appDef.getMetadata().getUid(), AppDefinition.API, AppDefinition.KIND), labelsWithGeneration,
                deployment -> {
                    if (pvcName.isPresent()) {
                        addVolumeClaim(deployment, pvcName.get(), appDef.getSpec());
                    }
                    bandwidthLimiter.limit(deployment, appDef.getSpec().getDownlinkLimit(),
                            appDef.getSpec().getUplinkLimit(), correlationId);
                    AddedHandlerUtil.removeEmptyResources(deployment);
                    if (appDef.getSpec().getPullSecret() != null && !appDef.getSpec().getPullSecret().isEmpty()) {
                        AddedHandlerUtil.addImagePullSecret(deployment, appDef.getSpec().getPullSecret());
                    }
                    sidecarManager.injectPrewarmedSidecarEnvVars(deployment, appDef, instance, correlationId);
                }, correlationId);
    }
```

The only change is adding this one line at the end of the lambda:
```java
                    sidecarManager.injectPrewarmedSidecarEnvVars(deployment, appDef, instance, correlationId);
```

### Step 4C: Verify no circular dependency

Check that `SidecarManager` does NOT inject `K8sResourceFactory`. Looking at SidecarManager.java:
- It only injects `SidecarResourceFactory` (our new class).
- `SidecarResourceFactory` only injects `TheiaCloudClient`.
- So the dependency chain is: `K8sResourceFactory → SidecarManager → SidecarResourceFactory → TheiaCloudClient`. No cycle.

---

## CHANGE 5: Fix session release lifecycle — restart sidecar Pod, don't delete Deployment+Service

**File**: `java/operator/org.eclipse.theia.cloud.operator/src/main/java/org/eclipse/theia/cloud/operator/handler/session/EagerSessionHandler.java`

In `sessionDeleted()` (line 343), replace:
```java
            sidecarManager.deletePrewarmedSidecars(appDef, instanceId, correlationId);
```

With:
```java
            sidecarManager.restartPrewarmedSidecarPods(appDef, instanceId, correlationId);
```

This method doesn't exist yet — we need to add it.

**File**: `java/operator/org.eclipse.theia.cloud.operator/src/main/java/org/eclipse/theia/cloud/operator/sidecar/SidecarManager.java`

Add this new method after `deletePrewarmedSidecars`:
```java
    /**
     * Restarts sidecar pods for a prewarmed instance WITHOUT deleting the Deployment or Service.
     * Deletes the Pod(s) managed by each sidecar Deployment, allowing the Deployment controller
     * to recreate them with a fresh state. Used during session release so the sidecar
     * infrastructure stays in place for pool reuse.
     */
    public void restartPrewarmedSidecarPods(AppDefinition appDef, int instanceId, String correlationId) {
        List<SidecarConfig> configs = getSidecarConfigs(appDef);
        if (configs.isEmpty()) {
            return;
        }

        ISpan span = Tracing.childSpan("sidecar.restart_prewarmed_pods", "Restart prewarmed sidecar pods");
        span.setData("instance_id", instanceId);
        span.setData("sidecar_count", configs.size());

        LOGGER.info(formatLogMessage(correlationId,
            "[Sidecar] Restarting " + configs.size() + " sidecar pod(s) for instance " + instanceId));

        for (SidecarConfig config : configs) {
            String resourceName = SidecarResourceFactory.getPrewarmedResourceName(appDef, instanceId, config);
            factory.deletePodsForDeployment(resourceName, correlationId);
        }

        Tracing.finishSuccess(span);
    }
```

**File**: `java/operator/org.eclipse.theia.cloud.operator/src/main/java/org/eclipse/theia/cloud/operator/sidecar/SidecarResourceFactory.java`

Add this new method in the `// ========== Deletion ==========` section, after `deletePrewarmedResources`:
```java
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
```

---

## CHANGE 6: Also fix `reconcileInstance` — only delete sidecar pods (not full resources) when instance is recycled with `no_action`

Look at `reconcileInstance()` in `PrewarmedResourcePool.java`. When `instanceId <= minInstances` and the instance is NOT outdated, the method takes `no_action` — this is the session-release-recycle path. The sidecar restart already happens in `EagerSessionHandler.sessionDeleted()` via the new `restartPrewarmedSidecarPods`, so `reconcileInstance` itself doesn't need changes for this path.

However, when the instance IS outdated (line 577–589) or exceeds minInstances (line 559–571), the current code calls `sidecarManager.deletePrewarmedSidecars()` which is correct — in those cases we ARE tearing down the full sidecar infrastructure. **No change needed in reconcileInstance.**

---

## Build Verification

Run these commands **in order** from the repo root `/Users/nikolas/BA Workdir/EduIDE-Cloud`. All must succeed:

```bash
cd java/common/maven-conf && mvn clean install -q
cd java/common/org.eclipse.theia.cloud.common && mvn clean install -q
cd java/conversion/org.eclipse.theia.cloud.conversion && mvn clean install -q
cd java/operator/org.eclipse.theia.cloud.operator && mvn clean install -q
cd java/service/org.eclipse.theia.cloud.service && mvn clean compile -q
```

If the operator build fails, fix the compilation errors before proceeding. Common issues:
- Missing import for `SidecarManager` in `K8sResourceFactory`
- The `Map` import needed in `SidecarManager` (already present, but verify)

---

## Summary of All Changes

| # | File | What |
|---|------|------|
| 1 | `PrewarmedResourcePool.java` | Move `ensurePrewarmedSidecarCapacity` call BEFORE deployment creation in `ensureCapacity()` |
| 2 | `PrewarmedResourcePool.java` | Move `reconcilePrewarmedSidecars` call BEFORE deployment reconciliation in `reconcile()` |
| 3 | `PrewarmedResourcePool.java` | Move `createPrewarmedSidecars` call BEFORE `createDeploymentForEagerInstance` in `createInstanceResources()` |
| 4 | `K8sResourceFactory.java` | Add `@Inject SidecarManager sidecarManager` field; add `sidecarManager.injectPrewarmedSidecarEnvVars(...)` call inside `createDeploymentForEagerInstance` lambda |
| 5 | `EagerSessionHandler.java` | Replace `deletePrewarmedSidecars` with `restartPrewarmedSidecarPods` in `sessionDeleted()` |
| 6 | `SidecarManager.java` | Add `restartPrewarmedSidecarPods()` method |
| 7 | `SidecarResourceFactory.java` | Add `deletePodsForDeployment()` method |

**Files modified**: 4 (`PrewarmedResourcePool.java`, `K8sResourceFactory.java`, `EagerSessionHandler.java`, `SidecarManager.java`)
**Files with new methods added**: 2 (`SidecarManager.java`, `SidecarResourceFactory.java`)
**No new files created.**

---

## Coding Conventions Checklist

- [ ] `LOGGER.info(formatLogMessage(correlationId, "[Sidecar] ..."))` for all new log statements
- [ ] `Tracing.childSpan(...)` / `Tracing.finishSuccess(span)` for new spans
- [ ] `@Inject` on the new SidecarManager field in K8sResourceFactory
- [ ] Null-safe: no new collection mutations without null checks (all new code uses Fabric8 label selector APIs, no manual list manipulation)
- [ ] No wildcard imports
- [ ] No `@ts-ignore` / `as any` / empty catch blocks
