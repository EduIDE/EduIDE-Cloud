# Subtask 3: Fix Eager Path — Eliminate Patches, Pre-inject Env Vars, Fix Lifecycle

## Objective

Remove all runtime patching from the eager session flow. Pre-inject sidecar env vars when the prewarmed Theia deployment is created. Fix sidecar lifecycle to match Theia's pod-delete pattern.

## Problem Statement

### Current eager flow (broken)
```
Pool creation:
  1. Create Theia Deployment (WITHOUT sidecar env vars)  ← problem
  2. Create Sidecar Deployment+Service

Session claim:
  3. Reserve instance
  4. patchEnvVarsIntoExistingDeployment()  ← patches env vars into live Deployment
     → K8s restarts Theia pod (user waits for restart)

Session release:
  5. deletePrewarmedLanguageServer()  ← deletes entire Deployment+Service
     → Next reconcile must recreate from scratch (slow)
```

### Target eager flow (fixed)
```
Pool creation:
  1. Create Sidecar Deployment+Service (names are deterministic)
  2. Create Theia Deployment WITH sidecar env vars pre-injected  ← fixed
     (sidecar service name = NamingUtil.createName(appDef, instance, sidecar.name))

Session claim:
  3. Reserve instance
  4. (no patching needed — env vars already present)  ← pod NOT restarted

Session release:
  5. Delete sidecar POD only (keep Deployment+Service)  ← fixed
     → K8s auto-recreates pod from Deployment (fast, clean state)
  6. Delete Theia POD (existing behavior, unchanged)
```

## What Changes

### 3.1 Pre-inject env vars at pool creation time

**File**: `PrewarmedResourcePool.java` → `createInstanceResources()`

Current order:
```java
resourceFactory.createDeploymentForEagerInstance(appDef, instance, pvcName, labels, correlationId);
createLanguageServerResources(appDef, instanceId, pvcName, labels, correlationId);
```

New order (sidecars FIRST, then Theia with env vars):
```java
// 1. Create sidecar resources first (so service names exist)
createSidecarResources(appDef, instanceId, pvcName, labels, correlationId);

// 2. Create Theia deployment WITH sidecar env vars pre-injected
resourceFactory.createDeploymentForEagerInstance(
    appDef, instance, pvcName, labels,
    deployment -> {
        sidecarManager.injectSidecarEnvVars(deployment, appDef, instanceId, correlationId);
    },
    correlationId);
```

The `injectSidecarEnvVars` overload for the eager path uses deterministic names:
```java
public void injectSidecarEnvVars(Deployment deployment, AppDefinition appDef, int instanceId, String correlationId) {
    List<SidecarConfig> configs = getSidecarConfigs(appDef);
    for (SidecarConfig config : configs) {
        String serviceName = SidecarResourceFactory.getPrewarmedServiceName(appDef, instanceId, config.name());
        injectEnvVarsForSidecar(deployment, config, serviceName, appDef, correlationId);
    }
    injectSidecarConfigJson(deployment, configs, appDef, instanceId, correlationId);
}
```

### 3.2 Remove patching from `EagerSessionHandler.sessionAdded()`

**File**: `EagerSessionHandler.java`

Remove these lines (currently around line 233-238):
```java
// DELETE THIS BLOCK:
String prewarmedLsServiceName = LanguageServerResourceFactory.getPrewarmedServiceName(appDef, instance.getInstanceId());
if (!languageServerManager.patchEnvVarsIntoExistingDeployment(instance.getDeploymentName(), prewarmedLsServiceName, appDef, correlationId)) {
    LOGGER.warn(...);
}
```

The env vars are already present from pool creation. No action needed at session claim time.

### 3.3 Fix sidecar lifecycle on session release

**File**: `EagerSessionHandler.sessionDeleted()`

Replace:
```java
// BEFORE: deletes entire Deployment+Service
languageServerManager.deletePrewarmedLanguageServer(appDef, instanceId, correlationId);
```

With:
```java
// AFTER: delete sidecar POD only (K8s restarts from Deployment)
sidecarManager.restartPrewarmedSidecars(appDef, instanceId, correlationId);
```

New method in `SidecarManager`:
```java
public void restartPrewarmedSidecars(AppDefinition appDef, int instanceId, String correlationId) {
    List<SidecarConfig> configs = getSidecarConfigs(appDef);
    for (SidecarConfig config : configs) {
        String deploymentName = SidecarResourceFactory.getPrewarmedDeploymentName(appDef, instanceId, config.name());
        deletePodByDeployment(deploymentName, correlationId);
    }
}
```

`deletePodByDeployment()` uses the same pattern as `PrewarmedResourcePool.deletePod()` — finds the pod by deployment name prefix and deletes it. K8s Deployment controller recreates a fresh pod.

### 3.4 Delete dead code

**Delete from `SidecarManager`** (formerly `LanguageServerManager`):
- `patchEnvVarsIntoExistingDeployment()` — no longer needed
- `patchPvcIntoPrewarmedLsDeployment()` — never called, dead code
- `deletePrewarmedLanguageServer()` — replaced by `restartPrewarmedSidecars()`

**Delete from `SidecarResourceFactory`** (formerly `LanguageServerResourceFactory`):
- `patchEnvVarsIntoExistingDeployment()` — the underlying factory method
- `patchPvcIntoLsDeployment()` — dead code

### 3.5 Update `ensureLanguageServerCapacity` / `reconcileLanguageServers`

**File**: `PrewarmedResourcePool.java`

These two methods are near-identical duplicates. Consolidate into one:

```java
private boolean ensureSidecarCapacity(AppDefinition appDef, int targetInstances,
        Map<String, String> labels, String correlationId) {
    List<SidecarConfig> configs = sidecarManager.getSidecarConfigs(appDef);
    if (configs.isEmpty()) return true;

    boolean success = true;
    for (SidecarConfig config : configs) {
        for (int instance = 1; instance <= targetInstances; instance++) {
            String deploymentName = SidecarResourceFactory.getPrewarmedDeploymentName(appDef, instance, config.name());
            if (client.kubernetes().apps().deployments().withName(deploymentName).get() == null) {
                Optional<String> pvcName = lookupExistingPvc(appDef, instance);
                success &= sidecarFactory.createPrewarmedService(appDef, instance, config, labels, correlationId).isPresent();
                success &= sidecarFactory.createPrewarmedDeployment(appDef, instance, config, pvcName, labels, correlationId).isPresent();
            }
        }
    }
    return success;
}
```

**Important**: Also add scale-down logic. When `instance > targetInstances`, delete sidecar resources. Use `ResourceLifecycleManager` pattern for proper ownership-aware deletion.

## Files to Modify

| Action | File |
|--------|------|
| MODIFY | `PrewarmedResourcePool.java` — reorder creation, pre-inject env vars, consolidate methods |
| MODIFY | `EagerSessionHandler.java` — remove patching block, use `restartPrewarmedSidecars` |
| MODIFY | `SidecarManager.java` — add `restartPrewarmedSidecars()`, eager-path `injectSidecarEnvVars()` |
| MODIFY | `SidecarResourceFactory.java` — delete patch methods |
| MODIFY | `K8sResourceFactory.java` — ensure `createDeploymentForEagerInstance` accepts a customizer callback |

## Validation

- Build passes
- Manual test: deploy with `minInstances: 1`, verify Theia pod starts with env vars already present
- Manual test: start session, verify NO pod restart occurs
- Manual test: end session, verify sidecar pod is deleted and recreated by K8s
