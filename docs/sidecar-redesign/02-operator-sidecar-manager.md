# Subtask 2: Operator Core — Rename, Generalize, Remove Registry

## Objective

Replace the language-server-specific operator code with an agnostic sidecar system that handles N sidecars per AppDefinition.

## What Changes

### 2.1 Delete `LanguageServerRegistry.java`

**Why**: The registry hardcodes Java/Rust/Python configs (images, env vars, resource limits). All configuration now comes from `AppDefinitionSpec.getSidecars()`.

**Impact**: Remove all references from `LanguageServerManager` and `AbstractTheiaCloudOperatorModule`.

### 2.2 Rename `LanguageServerConfig` → `SidecarConfig`

**Location**: `java/operator/.../languageserver/` → rename package to `.../sidecar/`

The record becomes a simple adapter that reads from `SidecarSpec`:

```java
public record SidecarConfig(
    String name,           // from SidecarSpec.name
    String image,          // from SidecarSpec.image
    int containerPort,     // from SidecarSpec.port (default 5000)
    List<String> languages, // from SidecarSpec.languages
    String cpuLimit,
    String memoryLimit,
    String cpuRequest,
    String memoryRequest,
    boolean mountWorkspace
) {
    public static SidecarConfig fromSpec(SidecarSpec spec) {
        return new SidecarConfig(
            spec.getName(),
            spec.getImage(),
            spec.getPort() > 0 ? spec.getPort() : 5000,
            spec.getLanguages() != null ? spec.getLanguages() : List.of(),
            spec.getCpuLimit()    != null ? spec.getCpuLimit()    : "500m",
            spec.getMemoryLimit() != null ? spec.getMemoryLimit() : "1Gi",
            spec.getCpuRequest()  != null ? spec.getCpuRequest()  : "100m",
            spec.getMemoryRequest() != null ? spec.getMemoryRequest() : "256Mi",
            spec.isMountWorkspace()
        );
    }

    /** Host env var for this sidecar: SIDECAR_LANGSERVER_HOST */
    public String hostEnvVar() {
        return "SIDECAR_" + name.toUpperCase().replace("-", "_") + "_HOST";
    }

    /** Port env var for this sidecar: SIDECAR_LANGSERVER_PORT */
    public String portEnvVar() {
        return "SIDECAR_" + name.toUpperCase().replace("-", "_") + "_PORT";
    }
}
```

**Key change**: No more `LS_JAVA_HOST` / `LS_RUST_HOST` pattern derived from hardcoded registry. Env var names are now derived from the sidecar `name` field.

### 2.3 Rename `LanguageServerManager` → `SidecarManager`

Core changes:
- `requiresLanguageServer(appDef)` → `hasSidecars(appDef)` — checks `appDef.getSpec().getSidecars() != null && !empty`
- `getLanguageServerConfig(appDef)` → `getSidecarConfigs(appDef)` — returns `List<SidecarConfig>` mapped from `AppDefinitionSpec.getSidecars()`
- `createLanguageServer(session, appDef, pvcName, correlationId)` → `createSidecars(session, appDef, pvcName, correlationId)` — loops over all sidecars
- `injectEnvVars(deployment, session, appDef, correlationId)` → `injectSidecarEnvVars(deployment, appDef, correlationId)` — injects env vars for ALL sidecars + `SIDECAR_CONFIG` JSON
- `deleteLanguageServer(session, correlationId)` → `deleteSidecars(session, correlationId)` — deletes all sidecar resources for a session

**Remove entirely**:
- `patchEnvVarsIntoExistingDeployment()` — see [03-eager-path-fix.md](./03-eager-path-fix.md)
- `patchPvcIntoPrewarmedLsDeployment()` — dead code

### 2.4 Rename `LanguageServerResourceFactory` → `SidecarResourceFactory`

Core changes:
- All methods take `SidecarConfig` instead of `LanguageServerConfig`
- Naming:
  - Lazy: `<session-name>-<sidecar.name>` (e.g., `ws-abc123-langserver`)
  - Prewarmed: `NamingUtil.createName(appDef, instance, sidecar.name)` (e.g., `java-17-no-ls-1-langserver`)
- **Migrate from YAML templates to Fabric8 builders** (see below)

### 2.5 Migrate resource creation to Fabric8 builders

Replace `templateLanguageServerDeployment.yaml` + `templateLanguageServerService.yaml` with programmatic construction:

```java
Deployment dep = new DeploymentBuilder()
    .withNewMetadata()
        .withName(deploymentName)
        .withNamespace(namespace)
        .addToLabels("app", deploymentName)
    .endMetadata()
    .withNewSpec()
        .withReplicas(1)
        .withNewSelector().addToMatchLabels("app", deploymentName).endSelector()
        .withNewTemplate()
            .withNewMetadata().addToLabels("app", deploymentName).endMetadata()
            .withNewSpec()
                .addNewContainer()
                    .withName(config.name())
                    .withImage(config.image())
                    .addNewPort().withContainerPort(config.containerPort()).endPort()
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
```

**Why**: YAML placeholder replacement is fragile for dynamic fields (conditional volume mounts, variable resource limits). Fabric8 builders are type-safe and handle optional fields cleanly.

### 2.6 Update DI module

**Location**: `AbstractTheiaCloudOperatorModule.java`

- Remove `LanguageServerRegistry` binding
- Bind `SidecarManager` as singleton (replacing `LanguageServerManager`)
- Bind `SidecarResourceFactory` as singleton (replacing `LanguageServerResourceFactory`)

### 2.7 Legacy fallback (transitional)

In `SidecarManager.getSidecarConfigs()`, add a temporary fallback:

```java
List<SidecarSpec> sidecars = appDef.getSpec().getSidecars();
if (sidecars == null || sidecars.isEmpty()) {
    // Legacy fallback: check options.langserver-image
    String lsImage = options.get("langserver-image");
    if (lsImage != null && !lsImage.isBlank()) {
        return List.of(SidecarConfig.fromLegacyOptions(options));
    }
    return List.of();
}
return sidecars.stream().map(SidecarConfig::fromSpec).toList();
```

This allows existing AppDefinitions using `options.langserver-image` to work until migrated. Remove after all environments are migrated.

## Files to Create/Modify/Delete

| Action | File |
|--------|------|
| DELETE | `LanguageServerRegistry.java` |
| RENAME+REWRITE | `LanguageServerConfig.java` → `SidecarConfig.java` |
| RENAME+REWRITE | `LanguageServerManager.java` → `SidecarManager.java` |
| RENAME+REWRITE | `LanguageServerResourceFactory.java` → `SidecarResourceFactory.java` |
| DELETE | `templateLanguageServerDeployment.yaml` |
| DELETE | `templateLanguageServerService.yaml` |
| MODIFY | `AbstractTheiaCloudOperatorModule.java` |
| MODIFY | `LazySessionHandler.java` (update import/method names) |
| MODIFY | `EagerSessionHandler.java` (update import/method names) |
| MODIFY | `PrewarmedResourcePool.java` (update import/method names) |

## Validation

- `mvn clean install` from `java/operator`
- All existing session handler tests must pass
- New unit tests for `SidecarConfig.fromSpec()`, `SidecarConfig.hostEnvVar()`, `SidecarConfig.portEnvVar()`
- Name uniqueness validation test
