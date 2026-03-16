# Subtask 4: Environment Variable Contract

## Objective

Define the env var injection scheme for communicating sidecar connection info from the operator to the Theia extension.

## Current State

The operator injects 5 env vars per language server into the Theia container:

```
LS_JAVA_HOST=ws-abc123-ls        # language-specific
LS_JAVA_PORT=5000                 # language-specific
LS_HOST=ws-abc123-ls              # generic fallback
LS_PORT=5000                      # generic fallback
LS_LANGUAGE=java                  # which language
```

**Problems**:
1. Generic `LS_HOST`/`LS_PORT` are ambiguous with multiple sidecars
2. Env var names are derived from a hardcoded registry
3. No way to communicate non-LS sidecar info

## New Contract

### Primary: `SIDECAR_CONFIG` (JSON)

Single JSON env var containing the full sidecar array:

```json
[
  {
    "name": "langserver",
    "host": "java-17-no-ls-1-langserver",
    "port": 5000,
    "languages": ["java"]
  },
  {
    "name": "database",
    "host": "java-17-no-ls-1-database",
    "port": 3306,
    "languages": []
  }
]
```

This is the **primary contract** that the extension reads. It supports N sidecars with arbitrary metadata.

### Secondary: Per-sidecar env vars

For each sidecar, also inject individual env vars for simple consumption:

```
SIDECAR_LANGSERVER_HOST=java-17-no-ls-1-langserver
SIDECAR_LANGSERVER_PORT=5000
SIDECAR_DATABASE_HOST=java-17-no-ls-1-database
SIDECAR_DATABASE_PORT=3306
```

Pattern: `SIDECAR_<NAME_UPPER>_HOST` and `SIDECAR_<NAME_UPPER>_PORT`

These allow non-extension consumers (scripts, other tools) to access sidecar info without parsing JSON.

### Backward compatibility (transitional)

During the migration period, if there is exactly ONE sidecar with a language that matches a known key (java, rust, python), also inject:

```
LS_JAVA_HOST=...
LS_JAVA_PORT=...
```

This keeps the old extension version working until the new version is deployed. **Remove this fallback once all images use the updated extension.**

### Implementation in `SidecarResourceFactory`

```java
private void injectSidecarEnvVars(Container container, List<SidecarConfig> configs,
        Function<SidecarConfig, String> serviceNameResolver) {

    List<EnvVar> envVars = container.getEnv();
    if (envVars == null) {
        envVars = new ArrayList<>();
        container.setEnv(envVars);
    }

    // Remove any previous sidecar env vars
    envVars.removeIf(e -> e.getName().startsWith("SIDECAR_") ||
                          e.getName().equals("LS_HOST") ||
                          e.getName().equals("LS_PORT") ||
                          e.getName().equals("LS_LANGUAGE"));

    // Build SIDECAR_CONFIG JSON
    List<Map<String, Object>> configEntries = new ArrayList<>();

    for (SidecarConfig config : configs) {
        String serviceName = serviceNameResolver.apply(config);

        // Per-sidecar vars
        envVars.add(envVar(config.hostEnvVar(), serviceName));
        envVars.add(envVar(config.portEnvVar(), String.valueOf(config.containerPort())));

        // JSON entry
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", config.name());
        entry.put("host", serviceName);
        entry.put("port", config.containerPort());
        entry.put("languages", config.languages());
        configEntries.add(entry);
    }

    // Inject JSON
    String json = new ObjectMapper().writeValueAsString(configEntries);
    envVars.add(envVar("SIDECAR_CONFIG", json));
}
```

### JSON escaping safety

Kubernetes env var values are strings. Jackson's `writeValueAsString` produces valid JSON. The extension parses it with `JSON.parse(process.env.SIDECAR_CONFIG)`. No special escaping is needed — K8s handles string values correctly in the Pod spec.

**Test case**: sidecar name with hyphens (e.g., `rust-analyzer`) should produce `SIDECAR_RUST_ANALYZER_HOST`.

## Files to Modify

| Action | File |
|--------|------|
| MODIFY | `SidecarResourceFactory.java` — new `injectSidecarEnvVars()` replacing `applyLanguageServerEnvVars()` |
| MODIFY | `SidecarManager.java` — call the new injection for both lazy and eager paths |

## Validation

- Unit test: verify correct env vars for 0, 1, and 3 sidecars
- Unit test: verify `SIDECAR_CONFIG` JSON is valid and parseable
- Unit test: verify backward-compat `LS_JAVA_HOST` injection for single-sidecar-with-language case
- Integration: deploy, exec into Theia pod, verify `env | grep SIDECAR`
