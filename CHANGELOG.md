# Changelog

## [Unreleased]

### Breaking changes

- [java/operator,java/common] **Security:** the operator authenticates data bridge injection with a per-instance token. It keeps a random token per prewarmed instance in a Secret `instance-<n>-<appdef>-bridge-token`, hands it to the pod as `DATA_BRIDGE_TOKEN` through a `secretKeyRef`, sends it as `Authorization: Bearer <token>` on `POST /data`, writes a new one whenever it creates the instance's deployment and before the pod restarts after a session, and deletes it with an excess instance. Install the EduIDE-Helm chart that lets the operator manage Secrets first, otherwise the pool is built without tokens. On release the pod and token are reset before the instance can be claimed again, a failed rotation replaces the deployment, and pool deployments without the token reference are recreated. `DataBridgeClient.injectData` takes the token as a new parameter, and the operator logs the names of keys the data bridge ignored. A data bridge without the check ignores the header. ([#156](https://github.com/EduIDE/EduIDE-Cloud/pull/156))

### Dependencies

- Update the Quarkus platform from 3.25.1 to 3.27.6.1 (LTS). The service now ships jackson 2.21.7 (was 2.19.2) and netty 4.1.139.Final (was 4.1.121.Final), which fixes several denial-of-service advisories, for example the HTTP/2 reset flood GHSA-prj3-ccx8-p6x4. The fabric8 Kubernetes client stays at 7.3.1, the version this Quarkus release manages, so `kubernetes-client.version` and the BOM agree. The operator modules have no Quarkus BOM, so they now import the jackson BOM (`jackson.version` 2.21.7 in maven-conf) ahead of fabric8 and exclude the unused Vert.x HTTP client of fabric8 (they build clients with OkHttp), which removes netty from the operator. Quarkus 3.31 and later drop `quarkus-smallrye-metrics`, so the service stays on the 3.27 LTS line until its metrics move to Micrometer. Found by an internal security audit 2026-11-16, DEP-01.
- Update the Java dependencies: Maven wrapper 3.3.4 (Maven 3.10.0), Sentry 8.63.0, sentry-maven-plugin 0.12.0, flatten-maven-plugin 1.9.0, slf4j 2.0.20 (non-Quarkus modules only; Quarkus manages it for the service and conversion), kubernetes-webhooks-framework-core 3.0.5, log4j 2.25.5, JUnit 5.14.4, Mockito 5.24.0 and the compiler, surefire and assembly plugins. The fabric8 Kubernetes client stays at 7.3.1 and Renovate no longer proposes it on its own, because the service takes fabric8 from Quarkus and a separate bump mixes jar versions. ([#176](https://github.com/EduIDE/EduIDE-Cloud/pull/176))

### Security

Fixes from the internal security audit 2026-11-16 (CL-04 to CL-10, CL-14, CI-01).

- [java/service] Remove the unauthenticated `PATCH /service/session` activity endpoint. The operator already polls the monitor in the session pod; nothing called it. The generated TypeScript client and the API documentation no longer contain it (`TheiaCloud.Session.reportSessionActivity` is removed from `@eclipse-theiacloud/common`). (CL-04)
- [java/service] Stop writing configuration values to the log at startup. Only the names of the configuration sources are logged. Rotate the admin API token and the OIDC client secret in every environment that ran an earlier version. (CL-05)
- [java/service] `POST /service/session` runs the same AppDefinition checks as `POST /service`: the AppDefinition must exist and have a free instance, and it must match the one of the workspace. A request can no longer rewrite the AppDefinition of a workspace. (CL-06)
- [java/service] CORS is off unless `CORS_ORIGINS` is set (the chart passes the landing page origin). `GET /service/appdefinition/{appId}` returns only the names of the AppDefinitions (`AppDefinitionSummary`); the full spec stays with the admin API. Health, metrics and OpenAPI move to a management port (9000), which is not routed through the gateway. The Prometheus scrape of `/q/metrics` must use that port. (CL-07)
- [java/service] The OIDC token audience is checked when `KEYCLOAK_AUDIENCE` is set (the Keycloak client needs an audience mapper). TLS verification of the identity provider is always on; a private CA goes into a trust store (`KEYCLOAK_TRUSTSTORE_FILE`, `KEYCLOAK_TRUSTSTORE_PASSWORD`). (CL-08)
- [java/service] In Keycloak mode the email claim must pass the same character check as an anonymous `user`, which now also rejects identifiers containing `placeholder-` (the operator fills its templates by text replacement). A caller with another email gets 403 with an explanation. The operator side (replacing all placeholders in one pass) is a separate change. (CL-09)
- [java/service] The AppDefinition is checked before the startup-time metric tag is created, so callers cannot add metric series. New `SESSIONS_PER_USER` limits the active sessions per user on all launch paths (0, the default, means no limit). (CL-10)
- [java/service] Launch request logging, and so Sentry events, no longer contain the values of `env.fromMap`. The service refuses to start without a service auth token instead of falling back to a built-in default. The image reads the secrets from environment variables instead of `-D` arguments, drops the default `APPID` and runs as a non-root user. (CL-14)
- [dockerfiles] The operator and conversion webhook images run as a non-root user (uid 10001). (CL-14)
- [node/monitor] Compare the session secret in constant time. (CL-14)
- [ci] New `tests.yml` runs the operator and service unit tests on every pull request that touches `java/`. (CI-01)

## [2.0.1] - 2026-11-16

### Fixes

- [java/operator] Do not crash the operator when a Workspace is deleted. Deleting a Workspace (garbage collector, cleanup after a launch timeout, admin) called DELETE on a PersistentVolume that does not exist with dynamic provisioning, and the operator service account may not delete PersistentVolumes, so the 403 stopped the operator. It now deletes the volume only when one with that name exists and logs a warning instead of failing when the delete is refused. The RBAC is unchanged. ([#175](https://github.com/EduIDE/EduIDE-Cloud/pull/175))

## [2.0.0] - 2026-11-16

Aligns EduIDE-Cloud with the EduIDE 3.0 platform release. There is no breaking change: the REST API, the CRDs and the existing operator arguments are unchanged, and the only addition is the optional `--metricsPort` argument.

### Features

- [java/operator] Count the sessions the operator stops as `theiacloud_sessions_ended_total{reason, app_definition}` (`reason` is `inactivity` or `lifetime`). The new `--metricsPort` argument serves it in the Prometheus format at `/metrics` on every replica; 0, the default, serves nothing. ([#167](https://github.com/EduIDE/EduIDE-Cloud/pull/167)) ([#171](https://github.com/EduIDE/EduIDE-Cloud/pull/171))

### Fixes

- [java/common,java/service] Never answer a launch with 200 and an empty URL. When one launch request timed out and a retry of the same session saw the error, the retry deleted the session, created a new one and returned it before it had a URL, so the landing page redirected the student to `https://`. A launch now never recreates or re-reads the session after waiting: it returns the state the watch saw, such as the 551. A session without URL is answered with 503 so the client tries again, and a launch on a new workspace keeps that workspace on a 503. ([#172](https://github.com/EduIDE/EduIDE-Cloud/pull/172))

### Dependencies

- Update npm dev dependencies ([#162](https://github.com/EduIDE/EduIDE-Cloud/pull/162))

## [1.4.0] - 2026-11-14

- [java/operator] Time out a session whose IDE tab was closed. The monitor extension runs in the plugin host, which ends with the last tab, so every later poll failed and the session ran until its lifetime limit. A failed poll now ends a session when its monitor answered at least once since the operator started and the last activity it reported is older than `timeoutAfter`. A monitor that never answered keeps the protection from #140 ([#160](https://github.com/EduIDE/EduIDE-Cloud/pull/160))
- [java/operator,java/service] Record why a session ended. Before the operator stops a session for inactivity or for reaching its AppDefinition lifetime limit, it annotates the session's workspace with `theia-cloud.io/last-session-end-reason` (`inactivity` or `lifetime`) and `theia-cloud.io/last-session-end-time`. `GET /service/workspace/{appId}/{user}` returns them as `lastSessionEndReason` and `lastSessionEndTime`, so the landing page's `/session-ended` can tell the student why. Ephemeral sessions have no workspace and get no stamp. Recording is best effort and never blocks the stop. A session stopped any other way leaves the stamp unchanged, so a reader compares the time with the start of the session it asks about. ([#160](https://github.com/EduIDE/EduIDE-Cloud/pull/160))

## [1.3.0] - 2026-11-11

- [java/operator] Add the operator argument `--sessionNodeSelector`, comma-separated `key=value` node labels that every session pod the operator creates is pinned to via `nodeSelector`: lazy sessions, prewarmed instances and their sidecars. It merges with a selector the deployment template already carries. Empty or absent leaves the pod spec unchanged, and a pair without `=` or with an empty key stops the operator at startup. Existing prewarmed instances keep their old pod spec until they are recreated. ([#164](https://github.com/EduIDE/EduIDE-Cloud/pull/164))

## [1.2.2] - 2026-11-06

- [theia,node] **Security:** fix the remaining fixable Dependabot alerts. The Theia example app moves to Theia 1.71 (with the `inversify` pin moved to 6.2.2), lerna to 9, and scoped resolutions and overrides lift minimatch, glob, tar, uuid, proxy-addr, `@tootallnate/once`, svgo, nth-check and postcss to patched versions. This also repairs `yarn build` under `theia/`, which the minimatch 11 resolution for lerna and a compile error in `config-store` had broken. ([#158](https://github.com/EduIDE/EduIDE-Cloud/pull/158))

## [1.2.1] - 2026-11-06

- [java/service,java/common] **Security:** refuse a launch or workspace creation with 403 when the requested name belongs to another user's workspace. Previously any logged-in user could start a session on another user's workspace, with their own environment variables, by sending its name, and could get an errored workspace of that user deleted. The refusal never deletes or modifies the other user's workspace. In anonymous mode the check compares the caller-supplied `user`, so it stops accidental name collisions but cannot stop a caller who claims to be the owner. ([#153](https://github.com/EduIDE/EduIDE-Cloud/pull/153))
- [java/operator] Keep waiting for the data bridge until the user opens the session, retry failed injections, stop when the session ended, and inject the session secret for the monitor ([#152](https://github.com/EduIDE/EduIDE-Cloud/pull/152))
- [ci] Skip the Sentry source bundle upload unless the repository variable `SENTRY_BUILD_ENABLED` is `true` ([#152](https://github.com/EduIDE/EduIDE-Cloud/pull/152))
- [java/operator] Never time out a session on an unanswered activity poll. When the monitor activity read fails or returns no timestamp, the tracker now counts consecutive failures and keeps the session running instead of falling through to the timeout check against a `lastActivity` frozen at session start, which could delete an active session whose monitor was briefly unreachable. ([#152](https://github.com/EduIDE/EduIDE-Cloud/pull/152))
- [java/operator] Replace unclaimed warm-pool deployments whose pods lack the NetworkPolicy labels, even when the AppDefinition generation is unchanged. The instance keeps its PVC. ([#152](https://github.com/EduIDE/EduIDE-Cloud/pull/152))
- [java/operator] Add the NetworkPolicy labels to eager pods from any deployment template, so custom templates are not recreated on every reconcile ([#152](https://github.com/EduIDE/EduIDE-Cloud/pull/152))
- [java/operator] Bind Theia to the pod IP and the app port in the deployment template without oauth2-proxy ([#152](https://github.com/EduIDE/EduIDE-Cloud/pull/152))
- [java/operator] **Security:** label prewarmed session pods so the session NetworkPolicies select them; claiming an instance only labelled its Services ([#152](https://github.com/EduIDE/EduIDE-Cloud/pull/152))
- [java/operator] **Security:** add restricted Pod Security Standard hardening controls to the session pod securityContext - `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`, `seccompProfile: RuntimeDefault` on the IDE and oauth2-proxy containers (both deployment templates), plus `runAsNonRoot: true` on oauth2-proxy. This does not make the pod fully Restricted-compliant: the IDE container runs as a configured non-zero UID without `runAsNonRoot`, and bandwidth-limited app definitions may still add a `NET_ADMIN` init container. ([#152](https://github.com/EduIDE/EduIDE-Cloud/pull/152))
- [java/operator] **Security:** enforce an allowlist for environment variables injected into sessions. `fromSecrets`/`fromConfigMaps` references are honoured only if listed in the new operator arguments `--allowedEnvFromSecrets`/`--allowedEnvFromConfigMaps` (default empty = deny), and reserved platform variables are always dropped from `fromMap` (toggle `--allowCustomEnvFromMap`). Reserved are the prefixes `THEIACLOUD_`, `KEYCLOAK_`, `BUILD_CACHE_`, `DEPENDENCY_CACHE_`, `DATA_BRIDGE_`, `SIDECAR_` and the names `LD_PRELOAD`, `LD_LIBRARY_PATH`, `NODE_OPTIONS`, `BAZEL_BUILD_CACHE_URL`, `SCORPIO_THEIA_ENV_STRATEGY`, so a caller cannot override administrator-controlled cache, data-bridge or sidecar connection settings. Closes an issue where an unauthenticated launch request could mount arbitrary namespace Secrets/ConfigMaps into a session pod. Running sessions keep what they already received until they end, so restart them when upgrading to revoke it. ([#152](https://github.com/EduIDE/EduIDE-Cloud/pull/152))
- [java/service] **Security:** validate the caller-supplied `user` in anonymous mode against a conservative character set (and require alphanumeric endpoints), rejecting CRLF/log/YAML injection, path traversal and shell-metacharacter payloads. ([#152](https://github.com/EduIDE/EduIDE-Cloud/pull/152))
- [java/common] Guarantee generated session label values are valid Kubernetes labels: trim leading/trailing separators left by sanitization or truncation so the API server cannot reject session reconciliation. ([#152](https://github.com/EduIDE/EduIDE-Cloud/pull/152))
- [java/operator] **Security:** scope the trust-all TLS socket factory used for the session availability probe to the single probe connection instead of calling `HttpsURLConnection.setDefaultSSLSocketFactory`, which disabled certificate verification process-wide for the operator's lifetime. ([#152](https://github.com/EduIDE/EduIDE-Cloud/pull/152))
- [java/service] **Security:** compare the admin API token in constant time (`MessageDigest.isEqual`) to remove a timing side channel. ([#152](https://github.com/EduIDE/EduIDE-Cloud/pull/152))
- [java/common,java/service] **Security:** mark the session `sessionSecret` as sensitive so it is redacted in REST responses and logs (it is the pod bearer token). CR serialization to Kubernetes is unaffected. ([#152](https://github.com/EduIDE/EduIDE-Cloud/pull/152))
- [java/service] Return a clean error for a request with no body instead of a `NullPointerException`/500. ([#152](https://github.com/EduIDE/EduIDE-Cloud/pull/152))

## [1.2.0] - estimated between 2025-11 and 2026-05

## [1.1.2] - 2025-09-26

- [java/operator] Use ingress path type ImplementationSpecific instead of Prefix [#449](https://github.com/eclipse-theia/theia-cloud/pull/449)

## [1.1.1] - 2025-08-01

- [java/operator] Create missing internal services in eager start app definition handler [#441](https://github.com/eclipse-theia/theia-cloud/pull/441)

## [1.1.0] - 2025-08-01

- [theia] Introduce new folder `theia` for all Theia extensions and an example app to test these [#389](https://github.com/eclipse-theia/theia-cloud/pull/389)
- [node/monitor-theia] Move Theia monitor extension to `theia/extensions/monitor-theia` [#389](https://github.com/eclipse-theia/theia-cloud/pull/389)
- [theia/extensions/monitor-theia] Update Theia dependencies to `^1.55.0` [#389](https://github.com/eclipse-theia/theia-cloud/pull/389)
- [ci] Add Theia CI workflow, add reusable Theia extension publish workflow [#389](https://github.com/eclipse-theia/theia-cloud/pull/388)
- [java] Fix and improve eager start handling [#393](https://github.com/eclipse-theia/theia-cloud/pull/393), [#396](https://github.com/eclipse-theia/theia-cloud/pull/396)
- [java/service] Add admin user concept and app definition scaling endpoint [#400](https://github.com/eclipse-theia/theia-cloud/pull/400)
- [docker] Provide ARM64 images [#404](https://github.com/eclipse-theia/theia-cloud/pull/404), [#408](https://github.com/eclipse-theia/theia-cloud/pull/408)
- [java/common] Truncate labels longer than 63 chars [#413](https://github.com/eclipse-theia/theia-cloud/pull/413), [#417](https://github.com/eclipse-theia/theia-cloud/pull/417)
- [java/conversion] Allow to set conversion webhook certificate reload period [#410](https://github.com/eclipse-theia/theia-cloud/pull/410)
- [all components] Introduce config store Theia extension and REST endpoint [#432](https://github.com/eclipse-theia/theia-cloud/pull/432), [#438](https://github.com/eclipse-theia/theia-cloud/pull/438)

### Breaking Changes in 1.1.0

- [java/common] Changed `LabelsUtil.createSessionLabels` to accept `AppDefinition` instead of `AppDefinitionSpec`

## [1.0.1] - 2025-03-24

- [java/common] Truncate labels longer than 63 chars [#413](https://github.com/eclipse-theia/theia-cloud/pull/413)

## [1.0.0] - 2024-11-29

- [java/operator] Add Theia Cloud Labels on Resources created by operator [#362](https://github.com/eclipse-theia/theia-cloud/pull/362)
- [node] Updated Node Dependencies [#371](https://github.com/eclipse-theia/theia-cloud/pull/371) - contributed on behalf of STMicroelectronics
- [terraform] Update `cert-manager`, `ingress-nginx`, and `keycloak` [#371](https://github.com/eclipse-theia/theia-cloud/pull/371) - contributed on behalf of STMicroelectronics

## [0.12.0] - 2024-10-30

- [All components] Documented naming conventions and updated code to follow [#368](https://github.com/eclipse-theia/theia-cloud/pull/368)
- [documentation] Add REST API markdown docs [#363](https://github.com/eclipse-theia/theia-cloud/pull/363)
- [node/common] Update Common Package to support listing app definitions [#361](https://github.com/eclipse-theia/theia-cloud/pull/361)
- [java/service] Extend service with a list app definitions endpoint [#361](https://github.com/eclipse-theia/theia-cloud/pull/361)
- [documentation] Improved debugging documentation [#354](https://github.com/eclipse-theia/theia-cloud/pull/354)
- [java] Fixed issue where monitor might have stopped session during session startup [#354](https://github.com/eclipse-theia/theia-cloud/pull/354)
- [terraform] Added Devcontainer for GKE demo [#353](https://github.com/eclipse-theia/theia-cloud/pull/353)
- [github] Moved from `eclipsesource` to `eclipse-theia` org as an official Theia subproject [#353](https://github.com/eclipse-theia/theia-cloud/pull/353) [#358](https://github.com/eclipse-theia/theia-cloud/pull/358)
- [node] Updated Node Dependencies [#345](https://github.com/eclipse-theia/theia-cloud/pull/345) - contributed on behalf of STMicroelectronics
- [java] Updated Maven Dependencies [#345](https://github.com/eclipse-theia/theia-cloud/pull/345) - contributed on behalf of STMicroelectronics

### Breaking Changes in 0.12.0

See the helm chart Changelog for [more details](https://github.com/eclipse-theia/theia-cloud-helm/blob/main/CHANGELOG.md#breaking-changes-in-0120).

We did some renaming in preparation for 1.0.0

- NOTE: default namespace renamed from `theiacloud` to `theia-cloud`
- Config Map template label key updated from `theiacloud` to `theia-cloud.io/template-purpose`
- PVC label `theia.cloud.workspace.name` renamed to `theia-cloud.io/workspace-name`
- System property `THEIA_CLOUD_APP_ID` renamed to `THEIACLOUD_APP_ID`
- System property `THEIA_CLOUD_USE_KEYCLOAK` renamed to `THEIACLOUD_USE_KEYCLOAK`
- Theia Monitor paths changed from `/services/theiacloud-` to `/services/theia-cloud-`

## [0.11.0] - 2024-07-23

- [common] Add option field to CRDs and increase version to `Session.v1beta8`, `Workspace.v1beta5` and `AppDefinition.v1beta10` [#293](https://github.com/eclipse-theia/theia-cloud/pull/293) | [#55](https://github.com/eclipse-theia/theia-cloud-helm/pull/55)
- [java] Separate operator default implementation from library to allow for easier customization [#303](https://github.com/eclipse-theia/theia-cloud/pull/303)
- [node] Unify the existing landing and try now pages to a new ViteJS based landing page [#304](https://github.com/eclipse-theia/theia-cloud/pull/304) | [#58](https://github.com/eclipse-theia/theia-cloud-helm/pull/58) - contributed on behalf of STMicroelectronics
  - The new page is based on the old try now page but uses ViteJS instead of the deprecated Create React App
  - Extend configuration options for the new landing page for texts and logo file type
  - Removed terms and conditions
  - Build the common package as ESM and CJS bundles for extended compatibility
- [common] Add `ingressHostnamePrefixes` list to `AppDefinition.v1beta10` [#298](https://github.com/eclipse-theia/theia-cloud/pull/298) | [#57](https://github.com/eclipse-theia/theia-cloud-helm/pull/57)
- [java] Improved naming for kubernetes resources [#326](https://github.com/eclipse-theia/theia-cloud/pull/326)

### Breaking Changes in 0.11.0

See the helm chart Changelog for [more details](https://github.com/eclipse-theia/theia-cloud-helm/blob/main/CHANGELOG.md).

## [0.10.0] - 2024-04-02

- [.github/workflows] Improve version detection in workflows (do not build release commits, auto-detect version for demo publishing) [#280](https://github.com/eclipse-theia/theia-cloud/pull/280) - contributed on behalf of STMicroelectronics
- [node] Separate `monitor` package from other workspaces to fix bundling the extension [#280](https://github.com/eclipse-theia/theia-cloud/pull/280) - contributed on behalf of STMicroelectronics
- [conversion] Provide java conversion webhook for CRD updates [#283](https://github.com/eclipse-theia/theia-cloud/pull/283) | [#49](https://github.com/eclipse-theia/theia-cloud-helm/pull/49) - contributed on behalf of STMicroelectronics
- [.github/workflows] Add ci for `conversion-webhook` and fix typo to build on version bumps [#283](https://github.com/eclipse-theia/theia-cloud/pull/283) | [#49](https://github.com/eclipse-theia/theia-cloud-helm/pull/49) - contributed on behalf of STMicroelectronics
- [common] Update CRs, keep previous version and offer Hub (used by conversion-webhook) [#283](https://github.com/eclipse-theia/theia-cloud/pull/283) | [#49](https://github.com/eclipse-theia/theia-cloud-helm/pull/49) - contributed on behalf of STMicroelectronics
  - Move status like fields to status
    - `Session.v1beta7`: Move `url`, `lastActivity` and `error` fields from the spec to the status.
    - `Workspace.v1beta4`: Move the `error` field from the spec to the status. Also add the `error` field to `Workspace.v1beta3` as it was missing
  - Remove `timeout.strategy` from AppDefinition
    - `AppDefinition.v1beta9`: Removed `timeout.strategy` and `timeout.limit` is now just `timeout`. This was done, as there is only one Strategy left.
- [java] Update io.fabric8.kubernetes-client to version 6.10.0. Also update Quarkus platform to 3.8.1. This provides kubernetes 1.29 support [#287](https://github.com/eclipse-theia/theia-cloud/pull/287)
- [terraform] Change terraform values to conform to helm chart changes [#289](https://github.com/eclipse-theia/theia-cloud/pull/289) | [#52](https://github.com/eclipse-theia/theia-cloud-helm/pull/52) - contributed on behalf of STMicroelectronics

## [0.9.0] - 2024-01-23

- [node/landing-page] Make npm workspace and remove yarn references from repo [#258](https://github.com/eclipse-theia/theia-cloud/pull/258) | [#45](https://github.com/eclipse-theia/theia-cloud-helm/pull/45) - contributed on behalf of STMicroelectronics
- [All components] Align [versioning](https://github.com/eclipse-theia/theia-cloud#versioning) between all components and introduce Changelog [#258](https://github.com/eclipse-theia/theia-cloud/pull/258) | [#45](https://github.com/eclipse-theia/theia-cloud-helm/pull/45) - contributed on behalf of STMicroelectronics
- [All components] Update CRD version scheme for all CRDs from `theia.cloud/vXbeta` to `theia.cloud/v1betaX` [#266](https://github.com/eclipse-theia/theia-cloud/pull/266) | [#46](https://github.com/eclipse-theia/theia-cloud-helm/pull/46) - contributed on behalf of STMicroelectronics
- [java/service] Update Quarkus from 2.12.0 to 3.3.1 [#266](https://github.com/eclipse-theia/theia-cloud/pull/266) - contributed on behalf of STMicroelectronics
- [java] Update fabric8 Kubernetes client from 5.12.2 to 6.7.2 [#266](https://github.com/eclipse-theia/theia-cloud/pull/266) - contributed on behalf of STMicroelectronics
- [java/common] Introduce hub-objects for all custom resources [#266](https://github.com/eclipse-theia/theia-cloud/pull/266) - contributed on behalf of STMicroelectronics
- [demo] Fix demo applications to Theia's latest Community Release (1.43.1) [#267](https://github.com/eclipse-theia/theia-cloud/pull/267) - contributed on behalf of STMicroelectronics
- [node/monitor-theia] Make `@theia` dependencies peerDependencies [#267](https://github.com/eclipse-theia/theia-cloud/pull/267) - contributed on behalf of STMicroelectronics
- [node] Update to node 20 [#269](https://github.com/eclipse-theia/theia-cloud/pull/269)
- [All components] Clean up repository [#275](https://github.com/eclipse-theia/theia-cloud/pull/275) - contributed on behalf of STMicroelectronics

## [0.8.1] - 2023-10-01

- Last Milestone based version. No changelog available due to alpha-phase.
