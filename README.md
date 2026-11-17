# EduIDE Cloud

EduIDE Cloud is based on the Theia Cloud project. The goal of EduIDE Cloud is to simplify the deployment of Theia-based (and similar) products on Kubernetes. We follow a convention over configuration approach allowing users to get started fast. At the same time, we aim for extensibility allowing developers to customize all aspects of the kubernetes deployment as required.

For more information about the upstream project, see [theia-cloud.io](https://theia-cloud.io/).

## Feedback, Help and Support

If you encounter any problems feel free to [open an issue](https://github.com/EduIDE/EduIDE-Cloud/issues/new/choose) on this repo.
EduIDE Cloud is a fork of [Theia Cloud](https://github.com/eclipse-theia/theia-cloud); the upstream project has its own [website](https://theia-cloud.io/) and support channels, which do not cover this fork.

## Components

Theia Cloud consists of the following components.

### Kubernetes Custom Resource Definitions and Operator

Theia Cloud brings simple custom resource definitions (CRDs) that allow to specify the required configuration, like the docker image of the Theia-based product.\
A Java-based operator will listen for the creation, modification, and deletion of custom resources based on those CRDs and will manage the application.\
See [Architecture.md](documentation/Architecture.md) for more information on the architecture.

### Theia Cloud Service

This REST Service acts as the API for creating and stopping Theia-based products for an authenticated user as well as providing additional information.\
The Theia Cloud service creates, modifies, and deletes the custom resources the operator listens to.

#### Scaling admin endpoints

Scaling operations for `AppDefinition` resources are exposed through admin endpoints under `/service/admin/appdefinition`.
They are off by default and answer with 404 until `theia.cloud.admin.api.enabled` (environment variable `THEIA_CLOUD_ADMIN_API_ENABLED`) is `true`; the chart sets it from `service.adminApiEnabled`.
Access is restricted to callers presenting the `X-Admin-Api-Token` header with the token from `theia.cloud.admin.api.token`.
In Helm-based deployments, provide this token through a pre-created Kubernetes Secret referenced by `service.adminApiTokenSecret`.

### Reusable JS/UI components

We offer a common ts library for the API of the Theia Cloud service, which may be reused by clients to write custom dashboards.

We plan to offer reusable ui components in the future as well.

## Versioning

From version 0.9.0 onwards, every component in this repository (and the helm chart repo) will follow the same version number. This change will make it clearer which versions of different components are compatible with each other.

### Release Types

- **Releases:** Releases are cut manually with the `release.yml` workflow (see below) and published as `X.Y.Z` images. Use these for deployments.
- **Main builds:** `build.yml` builds the three images on every push to `main` and publishes `latest` and `latest-<sha7>` tags. Pull requests publish `pr-<N>`. These are for testing and contributing, not for deployments.

The [helm charts](https://github.com/EduIDE/EduIDE-Helm) reference the compatible versions through the `versions.cloud` value.

### Release a new version

Releases are manual. Merge a release pull request that moves the `CHANGELOG.md` entries under the new version, then run:

```sh
gh workflow run release.yml -f version=X.Y.Z
```

`release.yml` drafts the GitHub Release, builds the three images as `X.Y.Z`, attaches `theia-cloud-monitor-X.Y.Z.vsix`, verifies them and then publishes the release, which creates the `vX.Y.Z` tag. Use `X.Y.Z-rc.N` for a prerelease. The `release` skill (`.claude/skills/release/SKILL.md`) walks through all of it.

## Building

All components are deployed as docker images and may be built with docker. See [Building.md](documentation/Building.md) for more information. We offer prebuilt images ready to use.

## Installation

The charts live in [EduIDE-Helm](https://github.com/EduIDE/EduIDE-Helm) and are published as OCI artifacts to `ghcr.io/eduide/charts`:

- `eduide-cluster` is installed once per cluster and owns the CRDs, the conversion webhook, the ClusterRoles and the cert-manager issuers.
- `eduide` is installed once per environment and owns the operator, the REST service, the landing page and the routes.

```bash
helm install eduide-cluster oci://ghcr.io/eduide/charts/eduide-cluster --version <version> -n eduide-system --create-namespace
helm install eduide oci://ghcr.io/eduide/charts/eduide --version <version> -n <namespace> -f my-values.yaml
```

See the EduIDE-Helm README for the available values.

### Cluster prerequisites (Gateway API / Envoy Gateway)

Theia Cloud now configures session traffic using Gateway API resources (`HTTPRoute` + `Gateway`).

- Gateway API CRDs must be installed on the cluster.
- Envoy Gateway must be installed and used as the Gateway controller (GatewayClass should match your Helm value `gateway.className`, default: `envoy`).
- The operator sets `X-Forwarded-Uri` using Envoy runtime syntax (`%REQ(:PATH)%`) in route rules; on non-Envoy controllers this value may be treated as a literal string.

For setup details, see [documentation/Install.md](documentation/Install.md).

### Uninstall

```bash
helm uninstall eduide -n <namespace>
helm uninstall eduide-cluster -n eduide-system
```
