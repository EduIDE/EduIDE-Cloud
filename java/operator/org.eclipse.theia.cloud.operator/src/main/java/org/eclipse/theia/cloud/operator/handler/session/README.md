# Session Handling

## Approaches

There are currently two approaches to session handling, represented by the `SessionHandler` interface.

### Lazy Session Handler (default)

Creates sessions on-demand, i.e. when a session is requested, the operator will create the necessary resources to run the session.

The session is created with all environment variables and options set.

### Eager Session Handler

Creates generic sessions ahead of time, according to the `AppDefinition` spec.

The session is created with all environment variables known ahead of time, like the data bridge port.

#### Data Bridge component

As the environment can't be personalized for prewarmed sessions, we need a way of injecting configuration at runtime.

This is handled by the data bridge component, a VSCode extension exposing HTTP endpoints for injection data.

Only the operator may inject. Every prewarmed instance with the data bridge enabled has a Secret `instance-<n>-<appdef>-bridge-token` (owned by the AppDefinition) holding a random token. The Theia container gets it as `DATA_BRIDGE_TOKEN` through a `secretKeyRef`, and the operator reads the Secret on every injection and sends `Authorization: Bearer <token>`. The operator writes a fresh token whenever it creates the instance's deployment and before it deletes the pod on session release, so the next session's pod starts with a token the previous user never saw. On release the pod is reset first and the services lose the session as owner last, because an instance can be claimed as soon as its services are free. If the new token cannot be written, the operator replaces the instance's deployment instead of only deleting its pod, so the next pod never starts with the old token (it gets a fresh one, or none, and a bridge without a token rejects every injection). Unclaimed pool deployments of a data bridge app that do not reference the token Secret, for example those created by an older operator, are recreated on the next reconcile; their PVC stays. If the operator stops in the middle of a release, the instance stays owned by the ended session until that session's deletion is handled again; the pod is already new by then. Removing an excess instance deletes its Secret. The operator needs `get`, `create`, `update` and `delete` on Secrets in its namespace (EduIDE-Helm). See EduIDE/EduIDE-Cloud#157.

The operator retries a failed injection with the same payload and reads the token again for each attempt. The data bridge replaces what it stored with every injection, so a retry that already half succeeded does no harm. The bridge keeps only the keys the platform extensions read (`THEIA`, `TEMPLATE`, `GIT_URI`, `GIT_USER`, `GIT_MAIL`, `ARTEMIS_URL`, `ARTEMIS_TOKEN`, `GRADLE_PREWARM`, `GRADLE_PREWARM_AUTO_CONFIRM`, `THEIACLOUD_SESSION_SECRET`); a key from an allowed ConfigMap or Secret that is not in this list needs to be named in `DATA_BRIDGE_EXTRA_KEYS` in the IDE container. The bridge answers with the names of the keys it ignored, and the operator logs those names (never values) as a warning.

If the operator cannot write the new token on release, it logs the error and the next pod starts with the old one. We accept that: a session is never reused, the previous user's pod is gone, and the session NetworkPolicies let only the gateway and the operator reach a session pod, so the old token is of no use to anyone. Without those NetworkPolicies this no longer holds.

#### Environment Configuration for prewarmed sessionsw

Prewarmed sessions need to be configured in advance to allow successful data injection later.

-   `SCORPIO_THEIA_ENV_STRATEGY`: Sets the strategy to use for environment configuration. Must be set to `data-bridge` for prewarmed sessions.
-   `DATA_BRIDGE_PORT` (optional): Overrides the default port the data bridge component should listen on.
-   `DATA_BRIDGE_ENABLED`: Must be set to `1` or `true` to enable the data bridge component in prewarmed sessions.
