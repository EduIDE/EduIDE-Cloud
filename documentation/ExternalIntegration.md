# External Session Launch Integration

External systems (primarily the EduIDE landing page / Artemis) launch IDE sessions by calling the Quarkus service. This page documents the direct REST contract and how the launch data flows through to the session container. For the auto-generated endpoint and model reference, see [`./api/README.md`](./api/README.md); those files are generated from [`openapi.json`](./openapi.json) (see [`./OpenAPI.md`](./OpenAPI.md)) and must not be edited by hand.

## The launch endpoint

`POST /service` accepts a `LaunchRequest` and returns a plain-string session URL. It creates a workspace if required.

`GET /service/{appId}` is an OPTIONAL health/readiness probe that returns a boolean. It is not a launch prerequisite.

The following example first pings the service (optional) and then launches a session:

```bash
# Optional readiness probe. Returns "true" when the service is available.
curl "https://<host>/service/<appId>"

# Launch a session. Blocks until the session URL is ready.
curl -X POST "https://<host>/service" \
  -H "Content-Type: application/json" \
  -H "Accept: text/plain" \
  -d '{
        "appId": "<service-auth-token>",
        "user": "student@example.org",
        "appDefinition": "eduide",
        "workspaceName": "student-exercise-1",
        "env": {
          "fromMap": {
            "THEIA": "true",
            "GIT_URI": "https://artemis.example.org/git/course/exercise.git",
            "GIT_USER": "student",
            "GIT_MAIL": "student@example.org",
            "ARTEMIS_TOKEN": "<token>",
            "ARTEMIS_URL": "https://artemis.example.org",
            "TEMPLATE": "java-maven"
          }
        }
      }'
```

The response body is the session URL as plain text.

## LaunchRequest and EnvironmentVars payload

`LaunchRequest` carries the following fields:

| Field           | Type              | Notes                                                                       |
| --------------- | ----------------- | --------------------------------------------------------------------------- |
| `appId`         | string            | The service auth token. Required.                                           |
| `user`          | string            | The user identification, usually the email address.                         |
| `appDefinition` | string            | The app to launch.                                                          |
| `workspaceName` | string            | Optional. Name of the workspace to mount or create.                        |
| `label`         | string            | Optional. Human-readable workspace label. A default is generated if absent. |
| `ephemeral`     | boolean           | Optional. If true, no workspace is created for the session.                  |
| `timeout`       | integer           | Minutes to wait for session launch. Default is 3 minutes.                    |
| `env`           | `EnvironmentVars` | Environment variables to deliver to the session.                            |

`EnvironmentVars` bundles three sources of environment variables:

| Field            | Type                | Notes                                                                     |
| ---------------- | ------------------- | ------------------------------------------------------------------------- |
| `fromMap`        | map of key/value    | Environment variables passed directly to the deployment.                  |
| `fromConfigMaps` | list of names       | Names of PRE-EXISTING in-cluster ConfigMaps to source variables from.     |
| `fromSecrets`    | list of names       | Names of PRE-EXISTING in-cluster Secrets to source variables from.        |

External callers outside the cluster pass values via `fromMap`. Prefer `fromSecrets` for sensitive values that are already held as Kubernetes Secrets in the cluster.

The EduIDE env-var keys the landing page sends via `fromMap` are `THEIA`, `GIT_URI`, `GIT_USER`, `GIT_MAIL`, `ARTEMIS_TOKEN`, `ARTEMIS_URL`, and `TEMPLATE`. Example body:

```json
{
  "appId": "<service-auth-token>",
  "user": "student@example.org",
  "appDefinition": "eduide",
  "workspaceName": "student-exercise-1",
  "env": {
    "fromMap": {
      "THEIA": "true",
      "GIT_URI": "https://artemis.example.org/git/course/exercise.git",
      "GIT_USER": "student",
      "GIT_MAIL": "student@example.org",
      "ARTEMIS_TOKEN": "<token>",
      "ARTEMIS_URL": "https://artemis.example.org",
      "TEMPLATE": "java-maven"
    }
  }
}
```

## End-to-end flow

The service (`RootResource.launch`) validates the request, then `K8sUtil` writes a `Session` custom resource that carries the env map (`spec.envVars`, `spec.envVarsFromConfigMaps`, and `spec.envVarsFromSecrets`). The operator then delivers the variables to the running IDE in one of two ways:

- Lazy path: the operator injects the variables directly into the IDE container's environment in the pod spec (`AddedHandlerUtil.addCustomEnvVarsToDeploymentFromSession`).
- Eager / prewarmed path: the deployment already exists, so the operator collects the variables (`SessionEnvCollector`) and pushes them asynchronously over HTTP `POST /data` (`AsyncDataInjector`) to the data-bridge extension inside the container.

The environment variables are opaque to EduIDE-Cloud itself. The session container image (Scorpio) consumes them to configure git, authenticate with Artemis, and clone `GIT_URI`.

For the broader system context, see [`./Architecture.md`](./Architecture.md).

## Authentication

`appId` must equal the server's configured service auth token. It is validated in `BaseResource`, and a mismatch returns HTTP 470.

OIDC (Keycloak) authentication runs via Quarkus. Send `Authorization: Bearer <jwt>`. The `user` field defaults to the JWT `email` claim if omitted, and must match the token email if supplied (otherwise HTTP 403).

In anonymous mode (Keycloak disabled), `user` is mandatory. A blank `user` returns HTTP 400.

## Behaviors integrators must know

- `POST /service` blocks until the session URL is ready (up to `timeout`, default 3 minutes). Set the client HTTP timeout above 3 minutes.
- Omitting `workspaceName` (with `ephemeral: false`) creates a new persistent volume and a fresh clone on every call. Pass a stable `workspaceName` to reuse a persistent workspace.
- `ephemeral: true` is rejected with HTTP 400 for app definitions that have workspace-mounting sidecars. Use a workspace-backed launch there.
