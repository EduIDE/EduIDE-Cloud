# Subtask 1: CRD Model + Conversion (EduIDE-Cloud, java/common + java/conversion)

## Objective

Add a typed `sidecars` field to the AppDefinition CRD, creating version v1beta11 with a proper conversion mapper.

## What Changes

### 1.1 New class: `SidecarSpec.java`

**Location**: `java/common/.../k8s/resource/appdefinition/SidecarSpec.java`

```java
@JsonDeserialize()
public class SidecarSpec {
    @JsonProperty("name")     private String name;        // e.g. "langserver", "database"
    @JsonProperty("image")    private String image;       // e.g. "ghcr.io/.../langserver-java:latest"
    @JsonProperty("port")     private int port;           // default 5000
    @JsonProperty("languages") private List<String> languages; // e.g. ["java"] — optional, for LSP sidecars
    @JsonProperty("cpuLimit")     private String cpuLimit;
    @JsonProperty("memoryLimit")  private String memoryLimit;
    @JsonProperty("cpuRequest")   private String cpuRequest;
    @JsonProperty("memoryRequest") private String memoryRequest;
    @JsonProperty("mountWorkspace") private boolean mountWorkspace; // default true
    // getters, toString, equals, hashCode
}
```

**Design notes**:
- `name` is the unique identifier for this sidecar within the AppDefinition. Used in K8s resource naming and env var generation.
- `languages` is optional. Only meaningful for LSP sidecars — tells the extension which file types to route to this sidecar.
- `mountWorkspace` defaults to `true` for LS (needs project files), but might be `false` for utility sidecars.

### 1.2 Update `AppDefinitionSpec.java`

**Location**: `java/common/.../k8s/resource/appdefinition/AppDefinitionSpec.java`

Add field:
```java
@JsonProperty("sidecars")
private List<SidecarSpec> sidecars;

public List<SidecarSpec> getSidecars() {
    return sidecars;
}
```

### 1.3 Update `AppDefinitionHub.java`

**Location**: `java/common/.../k8s/resource/appdefinition/hub/AppDefinitionHub.java`

Add to the hub (union of all versions):
```java
private Optional<List<SidecarSpec>> sidecars = Optional.empty();

public Optional<List<SidecarSpec>> getSidecars() { return sidecars; }
public void setSidecars(Optional<List<SidecarSpec>> sidecars) { this.sidecars = sidecars; }
```

### 1.4 New mapper: `AppDefinitionV1beta11Mapper.java`

**Location**: `java/conversion/.../mappers/appdefinition/AppDefinitionV1beta11Mapper.java`

- Copy from `AppDefinitionV1beta10Mapper`.
- Add `sidecars` field mapping (hub ↔ v1beta11 spec).
- v1beta10 → hub conversion: `sidecars = Optional.empty()` (older versions don't have it).
- hub → v1beta11 conversion: write `sidecars` if present.

### 1.5 Update `ConversionEndpoint.java`

Register the new mapper:
```java
registry.register("v1beta11", new AppDefinitionV1beta11Mapper());
```

### 1.6 Backward compatibility strategy

| From version | Sidecars field |
|-------------|----------------|
| v1beta8/9/10 → hub | `sidecars = Optional.empty()` |
| hub → v1beta11 | `sidecars = []` (empty list) or null |

The operator treats `null` or empty `sidecars` list as "no sidecars configured" — existing AppDefinitions work unchanged.

**Migration from `options.langserver-image`**: During a transition period, if `sidecars` is empty/null but `options.langserver-image` exists, the operator can auto-construct a single sidecar from the legacy options. This is a temporary fallback in the operator, NOT in the CRD.

## Files to Create/Modify

| Action | File |
|--------|------|
| CREATE | `java/common/.../appdefinition/SidecarSpec.java` |
| MODIFY | `java/common/.../appdefinition/AppDefinitionSpec.java` |
| MODIFY | `java/common/.../appdefinition/hub/AppDefinitionHub.java` |
| CREATE | `java/conversion/.../mappers/appdefinition/AppDefinitionV1beta11Mapper.java` |
| MODIFY | `java/conversion/.../ConversionEndpoint.java` |

## Validation

- Build `java/common` and `java/conversion` modules: `mvn clean install`
- Existing tests must pass (backward compat)
- Write unit test for `SidecarSpec` serialization/deserialization
