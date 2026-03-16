# Agnostic Sidecar Redesign — Implementation Plan

> **Status**: Draft  
> **Branch**: `feature/external-ls-v2`  
> **Date**: 2026-03-15  

## Goal

Replace the single-language-server implementation with an **agnostic sidecar system** that supports N arbitrary companion containers (language servers, databases, tools) per AppDefinition, configured entirely via CRD/Helm — no hardcoded registry.

## Key Decisions

| Decision | Choice | Reasoning |
|----------|--------|-----------|
| Sidecar topology | Separate pods (Deployment+Service) | User requirement. Enables independent scaling and resource isolation. |
| Configuration source | `sidecars: List<SidecarSpec>` in CRD | Typed, validated, no stringly-typed hacks in `options` map. |
| CRD version | v1beta11 | Follows existing conversion pipeline (v1beta8→9→10→11). |
| Eager env var injection | At pool creation time | Eliminates pod restart. Sidecar service names are deterministic via `NamingUtil`. |
| Eager sidecar lifecycle | Delete pod only on release | Matches Theia pod behavior. K8s auto-restarts from Deployment. |
| Registry | Removed | All config from CRD. No hardcoded language/image mapping. |
| Extension | Redesigned for dynamic discovery | Reads `SIDECAR_CONFIG` JSON env var. No hardcoded languages. |
| Resource generation | Migrate to Fabric8 builders | Type-safe, no YAML placeholder bugs, handles dynamic fields. |
| Name uniqueness | Validated at operator boundary | Fail AppDef reconciliation if duplicate sidecar names. |

## Repos Affected

| Repo | Scope |
|------|-------|
| **EduIDE-Cloud** (`java/common`) | CRD model: `SidecarSpec`, AppDefinitionSpec, Hub, v1beta11 mapper |
| **EduIDE-Cloud** (`java/operator`) | Operator: rename LS→Sidecar, remove registry, fix eager path, reconciliation |
| **EduIDE-Cloud** (`java/conversion`) | Conversion endpoint: register v1beta11 |
| **EduIDE-lsp-extension** | Extension: dynamic sidecar discovery from `SIDECAR_CONFIG` |
| **EduIDE-deployment** | Helm: update AppDefinition template + test3 values |

## Subtask Index

| # | File | Scope | Effort |
|---|------|-------|--------|
| 1 | [01-crd-model.md](./01-crd-model.md) | CRD schema + model classes + conversion | Medium |
| 2 | [02-operator-sidecar-manager.md](./02-operator-sidecar-manager.md) | Operator core: rename, generalize, remove registry | Medium |
| 3 | [03-eager-path-fix.md](./03-eager-path-fix.md) | Eliminate patches, pre-inject env vars, fix lifecycle | Medium |
| 4 | [04-env-var-contract.md](./04-env-var-contract.md) | Env var injection design + SIDECAR_CONFIG spec | Small |
| 5 | [05-extension-redesign.md](./05-extension-redesign.md) | LSP extension: dynamic discovery | Medium |
| 6 | [06-helm-deployment.md](./06-helm-deployment.md) | Helm template + values migration | Small |
| 7 | [07-cleanup-and-tests.md](./07-cleanup-and-tests.md) | Remove dead code, add tests, update AGENTS.md | Small |

## Why the Current Patches Are Unnecessary

This was the core investigation question. Here's the definitive answer:

### `patchEnvVarsIntoExistingDeployment` (Eager path)
**Unnecessary.** The prewarmed Theia deployment is created at pool creation time. At that exact moment, the sidecar service name is already known: `NamingUtil.createName(appDef, instance, "ls")`. The lazy path already does this correctly — it injects env vars into the Theia deployment object *before* applying it to K8s. The eager path can do the same. The patch exists because the original implementation didn't pre-inject these known values.

Removing this patch eliminates the pod restart on every session claim — a significant UX and performance improvement.

### `patchPvcIntoPrewarmedLsDeployment`
**Dead code.** The method exists in `LanguageServerManager` but is **never called** anywhere. The PVC is already passed to `createPrewarmedDeployment()` at creation time in `createLanguageServerResources()`. Safe to delete.

## Architecture Diagram

```
                    Helm values.yaml
                          │
                          ▼
                  ┌─────────────────┐
                  │  AppDefinition  │  (CRD v1beta11)
                  │  spec:          │
                  │    sidecars:    │
                  │    - name: ls   │
                  │      image: ... │
                  │      port: 5000 │
                  └────────┬────────┘
                           │
                    ┌──────┴──────┐
                    │  Operator   │
                    │ SidecarMgr  │
                    └──────┬──────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │  Theia   │ │ Sidecar  │ │ Sidecar  │
        │  Pod     │ │ Pod (LS) │ │ Pod (DB) │
        │          │ │          │ │          │
        │ SIDECAR_ │ │ port     │ │ port     │
        │ CONFIG=  │ │ 5000     │ │ 3306     │
        │ [...]    │ │          │ │          │
        └────┬─────┘ └──────────┘ └──────────┘
             │              ▲            ▲
             │   ClusterIP  │            │
             └──────────────┴────────────┘
                   (K8s Service DNS)
```

## Risk Mitigations

| Risk | Mitigation |
|------|------------|
| Extension connects before sidecar is Ready | Retry with exponential backoff in TCP connection logic |
| JSON env var escaping | Use Jackson serialization; test with special characters |
| Scale-down leaves orphaned sidecars | Use `ResourceLifecycleManager` (already handles this for Theia) |
| Backward compat for old AppDefs | v1beta11 mapper defaults `sidecars = null`; operator treats null as no sidecars |
| Name collisions | Validate uniqueness at AppDef reconciliation time |
