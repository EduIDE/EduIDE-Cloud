# AGENTS.md — EduIDE-Cloud

The operator, the REST service and the CRD conversion webhook. A fork of
`eclipse-theia/theia-cloud`, mid-rebrand.

`CLAUDE.md` is a symlink to this file, so every agent reads the same thing.

## Layout

```
java/common/maven-conf/                      the parent POM. Build this first, always.
java/common/org.eclipse.theia.cloud.common/  CRD models, K8s client wrappers, tracing
java/operator/…operator/                     the operator library
java/operator/…defaultoperator/              the runnable jar. This is the entry point.
java/service/…service/                       Quarkus REST API
java/conversion/…conversion/                 CRD conversion webhook (also Quarkus)
node/{common,e2e-tests,testing-page,monitor} TS client, Playwright tests, a CRA page, a VS Code extension
theia/extensions/{config-store,monitor-theia} Theia extensions
dockerfiles/{operator,service,conversion-webhook,wondershaper}
documentation/                               9 files, including the authoritative Building.md
```

There is **no landing page in this repo** — it was removed and lives in
EduIDE-Landing-Page.

## Building

**`cd java && mvn clean install` does not work.** There is no aggregator POM:
`java/pom.xml` does not exist and `maven-conf` declares no `<modules>`. Module
order is honoured by hand, and the Dockerfiles are the only complete
description of it:

```bash
cd java/common/maven-conf                        && mvn clean install -Drevision=1.2.0-SNAPSHOT
cd java/common/org.eclipse.theia.cloud.common    && mvn clean install -Drevision=1.2.0-SNAPSHOT
cd java/operator/org.eclipse.theia.cloud.operator && mvn clean install -Drevision=1.2.0-SNAPSHOT
cd java/operator/org.eclipse.theia.cloud.defaultoperator && mvn clean verify -Drevision=1.2.0-SNAPSHOT
```

`dockerfiles/operator/Dockerfile` and `dockerfiles/service/Dockerfile` are the
reference; keep them in step if you change module structure.

**Versions come from `-Drevision`.** Every POM declares
`<version>${revision}</version>`; `1.2.0-SNAPSHOT` in `maven-conf` is only the
default. `flatten-maven-plugin` resolves it at install time — without it the
installed POM keeps the literal `${revision}` and the next module cannot find
its parent. The Dockerfiles thread the same value into the jar filename, so
changing one without the other breaks the image build.

```bash
cd node && npm ci && npm run build     # workspaces: common, e2e-tests, testing-page (+ monitor)
cd theia && yarn build                 # yarn v1 + lerna
```

There is **no `npm run test`**. The Playwright suite is
`npm run ui-tests -w e2e-tests` and needs a live cluster.

## No CI runs the tests

`.github/workflows/` has three files: `build.yml` (three images, via the shared
org workflow), `tag-format.yml`, `auto-assign.yml`. **Nothing runs `mvn test`,
`npm run lint` or anything under `theia/`.** `dockerfiles/service/Dockerfile`
even builds with `-Dmaven.test.skip=true`.

A PR that breaks a Java test goes green. Run the tests yourself:

```bash
cd java/service/org.eclipse.theia.cloud.service && mvn verify
```

14 test classes over 208 source files, 11 of them in `service`. `operator` has
exactly one (`SidecarConfigTests`); `conversion` and `defaultoperator` have
none.

## Rules that are easy to get wrong

**Ephemeral sessions are rejected only when a sidecar MOUNTS THE WORKSPACE** —
not for every sidecar-enabled app definition. See `K8sUtil.java` and the three
tests that assert exactly this:

```
K8sUtilTests.launchEphemeralSession_allowsSidecarsWithoutWorkspaceMount
K8sUtilTests.launchEphemeralSession_rejectsSidecarsThatMountWorkspace
SessionResourceTests.start_ephemeralWithSidecarsWithoutWorkspaceMount_launchesEphemeralSession
```

A blanket 400 for any sidecar app definition breaks all three. `mountWorkspace`
and `SidecarConfig.requiresSharedWorkspace(appDef)` are the deciding inputs.

**Null-check Fabric8 list getters.** `getVolumes()`, `getVolumeMounts()` and
`getEnv()` return null on a fresh spec. `K8sResourceFactory` and
`SidecarResourceFactory` do this correctly; `LazySessionHandler` and
`AddedHandlerUtil` have unguarded calls that work only because the specs they
touch happen to be populated. Follow the factories.

**Find the Theia container with
`TheiaCloudPersistentVolumeUtil.getTheiaContainer(podSpec, appDefSpec)`.** Never
match by container name.

**Both YAML templates and Fabric8 builders are in use.** The Theia workload is
built from templates in `operator/src/main/resources/template*.yaml`; sidecars
are built with builders in `SidecarResourceFactory`. Neither is the repo-wide
rule — match whichever subsystem you are in.

**Routing is Gateway API `HTTPRoute`, not Ingress**, despite the class being
called `IngressManager`. One route per session.

**Logging differs by module.** `common` and `operator` use Log4j 2
(`LOGGER = LogManager.getLogger(...)`); `service` uses JBoss Logging with an
instance field `logger` on `BaseResource`. Use `formatLogMessage(correlationId,
…)` from `LogMessageUtil` either way, and never log secrets — there is a
`SensitiveDataSerializer` for exactly this.

**Tracing is `Tracing.*`.** `SentryHelper` was removed and no longer exists.
`Tracing.childSpan(parentSpan, "op", "desc")` is the form real code uses;
finish with `Tracing.finishSuccess(span)` or `Tracing.finishError(span, e)`.

**Sidecar setup is best-effort.** If `createSidecars()` returns false the
handler warns and continues. Config comes from `AppDefinitionSpec.getSidecars()`
only — there is no `options["langserver-image"]` fallback. For eager sessions,
sidecar Deployment and Service are created **before** the Theia deployment so
DNS resolves at pod startup, and releasing an eager session restarts the sidecar
pods rather than deleting them.

## Things that look live and are not

- `feature/external-ls-v2` — five months stale, 87 commits adrift, unmerged.
  There are 80+ remote branches; `git branch -r` tells you nothing about what is
  active.
- `terraform/` — self-declared unmaintained, points at upstream Docker Hub images.
- `demo/` and `demo/dockerfiles/` — four more images that no CI builds.
- `wondershaper` — a Debian init container, not a service.
- A root `package-lock.json` with **no root `package.json`**, pinning one
  package. `npm` at the repo root will misbehave.
- `README.md`, `SECURITY.md` and the issue templates still point at
  `eclipse-theia/theia-cloud`, and `maven-conf` still publishes to that org's
  package registry with the profile `activeByDefault`. The Sentry config is
  already TUM's. Fork residue, mid-migration.

`.vscode/` is gitignored but five files are tracked anyway, including
`.vscode/java-formatter.xml` — the actual Java formatter definition — and
`launch.json` with Minikube debug configs. Edits there show as modified, not
untracked.

## Conventions

- Java 21. EPL-2.0 header on every file, though ~20 lack one and nothing
  enforces it.
- Test classes end in `Tests`, never `Test`. All 14 follow this.
- Operator uses Guice `@Inject`; service uses Jakarta CDI `@Inject`.
- Prettier config is duplicated at `theia/.prettierrc.js` and
  `node/.prettierrc.js` — change both.
- `theia/package.json` pins `inversify` via `resolutions`; bumping Theia deps
  without it breaks DI.
- Release tags are `vX.Y.Z`; the image tag is the same string without the `v`.
