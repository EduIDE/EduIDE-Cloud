# Architecture Overview

The Theia Cloud Operator listens for changes to custom resources inside the cluster. With those custom resources clients may trigger the creation/deletion/handling of workspaces and sessions. The Operator is responsible for handling all things that are related to the Kubernetes-Resources for workspaces and sessions. All access is authenticated via an Authenticator.

External systems (product UI or integration) launch sessions by calling the REST service. See [External Session Launch Integration](./ExternalIntegration.md) for the launch contract and how launch data reaches the session container.

![Theia Cloud Diagram](theia-cloud.png "Theia Cloud")

## Operator Java Overview

The Java operator is created via a dependency injection module.
We provide a library (`org.eclipse.theia.cloud.operator`), as well as a default implementation (`org.eclipse.theia.cloud.defaultoperator`) for the operator.
The library can be used to create an operator customized to your use cases.
The default implementation is a production-ready operator that handles most use cases on most cloud providers out of the box and is used in our demos.
If customization is needed, it also serves as an example of how to utilize the operator library.

The following diagram sketches the basic idea.

![Operator Diagram](operator.png "Operator")

### Operator configuration options

| Option              | Type                                                            | Used for                                                                              |
| ------------------- | --------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| --keycloak          | boolean                                                         | Whether sessions will be created with a reverse proxy authenticating against keycloak |
| --cloudProvider     | null, K8S, MINIKUBE                                             | Cloud Provider specific configs                                                       |
| --bandwidthLimiter  | null, K8SANNOTATION, WONDERSHAPER, K8SANNOTATIONANDWONDERSHAPER | How to limit ingress/egress bandwidth                                                 |
| --wondershaperImage | string                                                          | The image of the wondershaper init container                                          |
| --sessionsPerUser   | number                                                          | The number of active sessions a user is allowed to have                               |
| --appId             | string                                                          | The id of the application which sends requests to the REST service                    |
| --sessionNodeSelector | comma-separated key=value pairs                               | Node labels every session pod (lazy, prewarmed and sidecar) is pinned to via nodeSelector, e.g. `aet.cit.tum.de/pool=pve`. Empty leaves the pod spec unchanged; a malformed pair stops the operator at startup |
|                     | the following options are not fully supported at the moment:    |
| --eagerStart        | boolean                                                         | Whether sessions will be created before there is a user to speed up starts            |

## Custom Resources

### App Definition

A App Definition describing a specific type of a session.\
(Not all properties may be supported by any operator configuration)

| Property       | Type    | Used for                                                                      |
| -------------- | ------- | ----------------------------------------------------------------------------- |
| name           | string  | Used to identify the app definition                                           |
| image          | string  | The container image launched in every session based on this app definition    |
| port           | integer | port to expose                                                                |
| host           | string  | Domain where the sessions will be available                                   |
| ingressname    | string  | Name of the ingress where the rules for sessions will be added                |
| minInstances   | integer | Instances that should be started eagerly without an existing user             |
| maxInstances   | integer | Upper bound for number of sessions that will be launched.                     |
| targetIdle     | integer | Free prewarmed instances kept ready; the pool refills after every claim and discards released instances. Unset keeps `minInstances` as the pool size. Written by the pool scaler |
| timeout        | object  | Sessions will timeout after the according to the specified strategy and limit |
| requestsMemory | string  | K8s memory requests                                                           |
| requestsCpu    | string  | K8s CPU requests                                                              |
| limitsMemory   | string  | K8s memory limits                                                             |
| limitsCpu      | string  | K8s CPU limits                                                                |
| downlinkLimit  | integer | Downlink bandwidth limit (kilobits per second)                                |
| uplinkLimit    | integer | Uplink bandwidth limit (kilobits per second)                                  |

#### Warm pool with targetIdle

With `targetIdle` set and `--eagerStart`, the operator keeps that many unclaimed instances, never fewer than `minInstances` instances in total and never more than `maxInstances`.
A claim hands out a ready instance first, the longest waiting one, and only then a starting one.
Releasing a session deletes its instance, including sidecars and PVC, so nobody gets an instance someone used before.
The leader reconciles every targetIdle pool every 30 seconds and after each claim and release, which also replaces instances that are outdated, incomplete or deleted by hand.

When a session's pod can not start because the namespace quota or the nodes are full, the free instances of all targetIdle pools are deleted and not refilled until no session waits any more.
A session that still waits after 45 seconds with no free instance left gets error 555 `CLUSTER_CAPACITY_EXHAUSTED`, which the service returns to the landing page.

The operator's `/metrics` endpoint (`--metricsPort`) exports `eduide_pool_instances{state}`, `eduide_pool_target_idle`, `eduide_session_claims_total{result}`, `eduide_session_rejected_total{reason}`, `eduide_pool_instances_evicted_total` and the histogram `eduide_pool_instance_ready_seconds`, all with the label `app_definition`.
The service additionally exports the launch duration as the histogram `application_eduide_session_startup_seconds` with the labels `app_definition`, `namespace` and `outcome`.

### Workspace

A workspace is a cross-section for a specific application definition, user, and storage.

| Property      | Type   | Used for                                                           |
| ------------- | ------ | ------------------------------------------------------------------ |
| name          | string | Used to identify the workspace                                     |
| label         | string | A human-readable identifier for the workspace                      |
| appDefinition | string | The app definition name on which this workspace is based           |
| user          | string | The user ID that created that workspace                            |
| storage       | string | The name of the persistent storage that is used for this workspace |

### Session

A concrete session associated with a user for a given workspace.

| Property      | Type    | Used for                                                                      |
| ------------- | ------- | ----------------------------------------------------------------------------- |
| name          | string  | Used to identify the session                                                  |
| workspace     | string  | The name of the associated workspace                                          |
| appDefinition | string  | The app definition name on which this session is based                        |
| user          | string  | The user ID based on which AuthN/Z will be done                               |
| url           | string  | The Operator may fill this field with the URL where the session is available  |
| error         | string  | The Operator may fill this field with an error message if there was a problem |
| lastActivity  | integer | The timestamp of the last user activity within this session                   |

## Used technologies in Cluster

Our default implementation is using these technologies:

- oauth2-proxy as a reverse proxy to handle trafic to the running session container <https://oauth2-proxy.github.io/oauth2-proxy/>
- keycloak for authentication/authorization <https://www.keycloak.org/>

## Session Deployment

A session deployment consists of two containers. The first container is the IDE. The second container is the oauth2-proxy which acts as a reverse-proxy for the IDE. The oauth2-proxy is further configured via three config maps. The first map simply adds some template HTMLs for login and error cases. The second config map has the configuration for authenticating against KeyCloak. Finally the third configmap is used to update the allowed email addresses which are accepted. Via this third map we restrict access to the user defined in the Session resource.
