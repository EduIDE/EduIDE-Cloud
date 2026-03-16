# Subtask 6: Helm Template + Deployment Values Migration

## Objective

Update the AppDefinition Helm template to render the new `sidecars` field and migrate test3 values from `options.langserver-image` to the structured array.

## What Changes

### 6.1 Update AppDefinition Helm template

**File**: `EduIDE-deployment/charts/theia-appdefinitions/templates/appdefinition.yaml`

Add `sidecars` rendering after the `options` block:

```yaml
  {{- if .options }}
  options:
    {{- toYaml .options | nindent 4 }}
  {{- end }}
  {{- if .sidecars }}
  sidecars:
    {{- range .sidecars }}
    - name: {{ .name }}
      image: {{ .image }}
      port: {{ .port | default 5000 }}
      {{- if .languages }}
      languages:
        {{- range .languages }}
        - {{ . }}
        {{- end }}
      {{- end }}
      {{- if .cpuLimit }}
      cpuLimit: {{ .cpuLimit }}
      {{- end }}
      {{- if .memoryLimit }}
      memoryLimit: {{ .memoryLimit }}
      {{- end }}
      {{- if .cpuRequest }}
      cpuRequest: {{ .cpuRequest }}
      {{- end }}
      {{- if .memoryRequest }}
      memoryRequest: {{ .memoryRequest }}
      {{- end }}
      mountWorkspace: {{ .mountWorkspace | default true }}
    {{- end }}
  {{- end }}
```

Also update the `apiVersion` to `theia.cloud/v1beta11`.

### 6.2 Migrate test3 values

**File**: `EduIDE-deployment/deployments/test3.theia-test.artemis.cit.tum.de/values.yaml`

Before:
```yaml
theia-appdefinitions:
  apps:
    - name: java-17-no-ls
      image: ghcr.io/ls1intum/theia/java-17-no-ls
      imageTag: latest
      minInstances: 1
      options:
        langserver-image: ghcr.io/ls1intum/theia/langserver-java:latest
    - name: rust-no-ls
      image: ghcr.io/ls1intum/theia/rust-no-ls
      imageTag: latest
      minInstances: 1
      options:
        langserver-image: ghcr.io/ls1intum/theia/langserver-rust:latest
```

After:
```yaml
theia-appdefinitions:
  apps:
    - name: java-17-no-ls
      image: ghcr.io/ls1intum/theia/java-17-no-ls
      imageTag: latest
      minInstances: 1
      sidecars:
        - name: langserver
          image: ghcr.io/ls1intum/theia/langserver-java:latest
          port: 5000
          languages: [java]
          mountWorkspace: true
    - name: rust-no-ls
      image: ghcr.io/ls1intum/theia/rust-no-ls
      imageTag: latest
      minInstances: 1
      sidecars:
        - name: langserver
          image: ghcr.io/ls1intum/theia/langserver-rust:latest
          port: 5000
          languages: [rust]
          mountWorkspace: true
```

Note: `options.langserver-image` is removed. The operator's legacy fallback (subtask 2) will handle any environments not yet migrated.

### 6.3 Update operator image reference

Once the new operator is built and pushed, update the operator image tag in test3 values:

```yaml
  operator:
    image: ghcr.io/ls1intum/theia/operator:<new-tag>
```

### 6.4 Other environments

Other environments (test1, test2, staging, prod) do NOT currently use external LS — they have no `langserver-image` in their options. No changes needed for them. The operator with an empty `sidecars` list behaves identically to today.

## Files to Modify

| Action | File | Repo |
|--------|------|------|
| MODIFY | `charts/theia-appdefinitions/templates/appdefinition.yaml` | EduIDE-deployment |
| MODIFY | `deployments/test3.../values.yaml` | EduIDE-deployment |

## Validation

- `helm template` dry-run to verify rendered YAML is valid
- Deploy to test3, verify AppDefinition CR has correct `sidecars` array
- Verify operator picks up sidecars and creates Deployment+Service
