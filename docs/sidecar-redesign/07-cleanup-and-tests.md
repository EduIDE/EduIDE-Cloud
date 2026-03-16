# Subtask 7: Cleanup + Tests + Documentation

## Objective

Remove all legacy artifacts, add unit test coverage, and update documentation.

## What to Delete

### Dead code removal

| File | Reason |
|------|--------|
| `LanguageServerRegistry.java` | Replaced by CRD-driven config |
| `LanguageServerConfig.java` | Replaced by `SidecarConfig.java` |
| `LanguageServerManager.java` | Replaced by `SidecarManager.java` |
| `LanguageServerResourceFactory.java` | Replaced by `SidecarResourceFactory.java` |
| `templateLanguageServerDeployment.yaml` | Replaced by Fabric8 builders |
| `templateLanguageServerService.yaml` | Replaced by Fabric8 builders |

### Legacy option keys to remove

After all environments are migrated from `options.langserver-image` to `sidecars`:
- Remove `SidecarConfig.fromLegacyOptions()` fallback
- Remove `LanguageServerConfig.OPTION_LS_*` constants
- Remove backward-compat `LS_<LANG>_HOST` env var injection

## Tests to Write

### Unit tests (JUnit 5 + Mockito)

All test classes in `java/operator/.../sidecar/` with `Tests` suffix per AGENTS.md.

#### `SidecarConfigTests.java`

```java
@Test void fromSpec_defaultPort()       // port=0 → default 5000
@Test void fromSpec_allFieldsPresent()  // all fields map correctly
@Test void fromSpec_nullLanguages()     // languages=null → empty list
@Test void hostEnvVar_simpleNamee()     // "langserver" → "SIDECAR_LANGSERVER_HOST"
@Test void hostEnvVar_hyphenatedName()  // "rust-analyzer" → "SIDECAR_RUST_ANALYZER_HOST"
@Test void portEnvVar()                 // "langserver" → "SIDECAR_LANGSERVER_PORT"
```

#### `SidecarManagerTests.java`

```java
@Test void hasSidecars_nullList()       // sidecars=null → false
@Test void hasSidecars_emptyList()      // sidecars=[] → false
@Test void hasSidecars_withEntries()    // sidecars=[...] → true
@Test void getSidecarConfigs_fromSpec() // maps SidecarSpec list to SidecarConfig list
@Test void getSidecarConfigs_legacyFallback() // options.langserver-image → single config
@Test void createSidecars_success()     // creates N deployments + N services
@Test void createSidecars_partialFailure_rollback() // if 2nd sidecar fails, 1st is rolled back
@Test void deleteSidecars_deletesAll()  // all sidecars cleaned up
```

#### `SidecarResourceFactoryTests.java`

```java
@Test void createDeployment_correctName()    // lazy: <session>-<sidecar.name>
@Test void createDeployment_withPvc()        // volume + volumeMount added
@Test void createDeployment_withoutPvc()     // no volume when mountWorkspace=false
@Test void createPrewarmedDeployment_name()  // prewarmed: NamingUtil + sidecar.name
@Test void injectEnvVars_singleSidecar()     // correct SIDECAR_* vars
@Test void injectEnvVars_multipleSidecars()  // all sidecars get their vars
@Test void injectEnvVars_jsonValid()         // SIDECAR_CONFIG parses as valid JSON
@Test void injectEnvVars_noSidecars()        // empty config → no env vars added
@Test void nameUniquenessValidation()        // duplicate names → error
```

#### `SidecarSpecTests.java` (in java/common)

```java
@Test void serialization_roundTrip()    // Jackson serialize → deserialize
@Test void deserialization_defaults()   // missing optional fields get defaults
```

## Documentation Updates

### AGENTS.md (EduIDE-Cloud)

Update the "Language Server Specifics" section:

**Replace**:
> LS operations are best-effort: if createLanguageServer() returns false, log a warning...
> javascript/node/-js- image substrings must be checked before java/jdt...

**With**:
> Sidecar operations are best-effort: if createSidecars() returns false, log a warning but continue the session flow.
> Sidecar configuration is read from AppDefinitionSpec.getSidecars() — no hardcoded language detection.
> Sidecar names must be unique within an AppDefinition; the operator validates this at reconciliation time.

### AGENTS.md sections to verify/update
- "Kubernetes (Fabric8)" section — update to mention sidecar Fabric8 builders
- "Tracing (Sentry)" — verify span names are updated from `ls.*` to `sidecar.*`
- "Error Handling" — verify sidecar rollback pattern is documented

## Implementation Order

Recommended implementation sequence:

```
1. CRD Model (subtask 1)        — foundation, no runtime impact
2. Env Var Contract (subtask 4)  — define the interface
3. Operator Core (subtask 2)     — rename + generalize
4. Eager Path Fix (subtask 3)    — eliminate patches
5. Extension (subtask 5)         — consume new env vars
6. Helm (subtask 6)              — deploy new config format
7. Cleanup + Tests (subtask 7)   — polish
```

Each subtask is independently testable. Subtasks 1-4 can be developed on the same branch. Subtask 5 is a separate repo/PR. Subtask 6 is deployed after the operator image is pushed.

## Validation

- Full build: `cd java && mvn clean install`
- All new tests pass: `mvn test`
- Zero regressions in existing tests
- Deploy to test3: verify full flow (create session → sidecar starts → extension connects → delete session → sidecar pod restarted)
