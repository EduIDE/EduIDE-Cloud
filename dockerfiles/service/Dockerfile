FROM eclipse-temurin:21-jdk AS builder
ARG APP_VERSION=1.2.0-SNAPSHOT
# Accept APT_HTTP_PROXY build-arg for caching
ARG APT_HTTP_PROXY
# Configure apt proxy if provided (hybrid: works on ARC and GitHub runners)
RUN if [ -n "$APT_HTTP_PROXY" ]; then \
      echo "Acquire::http::Proxy \"$APT_HTTP_PROXY\";" > /etc/apt/apt.conf.d/01proxy; \
      # Force HTTP repositories when using proxy (Noble defaults to HTTPS which apt-cacher-ng cannot cache)
      sed -i 's|https://|http://|g' /etc/apt/sources.list.d/*.sources 2>/dev/null || true; \
      sed -i 's|https://|http://|g' /etc/apt/sources.list 2>/dev/null || true; \
      echo "Using APT Proxy: $APT_HTTP_PROXY with HTTP repositories"; \
    else \
      echo "No APT Proxy set, using direct connection"; \
    fi
RUN apt-get update && apt-get install -y maven
WORKDIR /service
COPY java/common ./common
COPY java/service ./service
RUN --mount=type=secret,id=SENTRY_AUTH_TOKEN \
    --mount=type=cache,target=/root/.m2 \
    SENTRY_AUTH_TOKEN_VALUE="$(cat /run/secrets/SENTRY_AUTH_TOKEN 2>/dev/null || true)" && \
    MAVEN_SENTRY_ARGS="" && \
    if [ -n "$SENTRY_AUTH_TOKEN_VALUE" ]; then \
      export SENTRY_AUTH_TOKEN="$SENTRY_AUTH_TOKEN_VALUE" && \
      MAVEN_SENTRY_ARGS="-Dsentry=true" && \
      echo "Sentry build integration enabled"; \
    else \
      echo "No SENTRY_AUTH_TOKEN provided, skipping Sentry build integration"; \
    fi && \
    cd /service/common/maven-conf && \
    mvn clean install --no-transfer-progress -Drevision=${APP_VERSION} $MAVEN_SENTRY_ARGS && \
    cd /service/common/org.eclipse.theia.cloud.common && \
    mvn clean install --no-transfer-progress -Drevision=${APP_VERSION} $MAVEN_SENTRY_ARGS && \
    cd /service/service/org.eclipse.theia.cloud.service && \
    mvn clean package -Drevision=${APP_VERSION} -Dquarkus.package.type=uber-jar --no-transfer-progress $MAVEN_SENTRY_ARGS

# Java4Shell (CVE-2026-114938): run on OpenJDK 27.0.1.1 (glibc, no Alpine build exists). Switch back to a vendor LTS build once one ships the fix.
FROM debian:13.7-slim
ARG TARGETARCH
ARG JDK_VERSION=27.0.1.1
ARG JDK_SHA256_AMD64=82172b79a4e4a8546e2538c525b63e020f419922a5d30f9873bbb832ce95637b
ARG JDK_SHA256_ARM64=7b851f4cdf55ce48dc33584349177aa271df0bbd0ff5711a6e7fcd812ef5a90a
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl p11-kit unzip && rm -rf /var/lib/apt/lists/* && \
    case "$TARGETARCH" in \
      amd64) JDK_ARCH=x64; JDK_SHA256="$JDK_SHA256_AMD64" ;; \
      arm64) JDK_ARCH=aarch64; JDK_SHA256="$JDK_SHA256_ARM64" ;; \
      *) echo "Unsupported TARGETARCH: $TARGETARCH" >&2; exit 1 ;; \
    esac && \
    curl -fsSL -o /tmp/jdk.tar.gz "https://download.java.net/java/GA/jdk${JDK_VERSION}/c3a705caf5044be8aa0b09e62be69b20/4/GPL/openjdk-${JDK_VERSION}_linux-${JDK_ARCH}_bin.tar.gz" && \
    echo "${JDK_SHA256}  /tmp/jdk.tar.gz" | sha256sum -c - && \
    mkdir -p /opt/java && tar -xzf /tmp/jdk.tar.gz -C /opt/java --strip-components=1 && \
    rm -rf /tmp/jdk.tar.gz /opt/java/jmods /opt/java/lib/src.zip /opt/java/include /opt/java/man && \
    trust extract --format=java-cacerts --filter=ca-anchors --purpose=server-auth --overwrite /opt/java/lib/security/cacerts
ENV JAVA_HOME=/opt/java
ENV PATH=/opt/java/bin:$PATH
ARG APP_VERSION=1.2.0-SNAPSHOT
WORKDIR /service
RUN curl -fsSL -o sentry-agent.zip https://github.com/getsentry/sentry-java/releases/download/8.63.0/sentry-opentelemetry-agent-8.63.0.zip && \
    unzip -j sentry-agent.zip -d . && \
    rm sentry-agent.zip && \
    apt-get purge -y --auto-remove curl unzip p11-kit
COPY --from=builder /service/service/org.eclipse.theia.cloud.service/target/service-${APP_VERSION}-runner.jar ./service.jar
COPY --from=builder /service/service/org.eclipse.theia.cloud.service/sentry.properties ./sentry.properties

ENV SERVICE_PORT=8081

ENV KEYCLOAK_ENABLE=true
# Keycloak config
ENV KEYCLOAK_SERVERURL=https://keycloak.url/auth/realms/TheiaCloud
ENV KEYCLOAK_CLIENTID=theia-cloud
ENV KEYCLOAK_CLIENTSECRET=publicbutoauth2proxywantsasecret
ENV KEYCLOAK_ADMIN_GROUP=theia-cloud/admin
ENV ADMIN_API_TOKEN=
# Optional. Unset means no audience check, no CORS and the default trust store.
ENV KEYCLOAK_AUDIENCE=
ENV KEYCLOAK_TRUSTSTORE_FILE=
ENV KEYCLOAK_TRUSTSTORE_PASSWORD=
# Comma separated origins that may call the API from a browser, e.g. the landing page.
ENV CORS_ORIGINS=
# Maximum active sessions per user. 0 means no limit.
ENV SESSIONS_PER_USER=0

# If set to true, enables Sentry Java agent flags.
ENV SENTRY_ENABLE=false
# Default path for the baked-in Sentry properties file.
# This can still be overridden with a mounted file or env-based config if needed.
ENV SENTRY_PROPERTIES_FILE=/service/sentry.properties

# Use SERVICE_AUTH_TOKEN if set, otherwise fall back to the deprecated APPID. There is no default: the service
# refuses to start without one.
# Secrets and optional settings go to the JVM through the environment, not as -D arguments, so they do not show
# up in the process list.
RUN cat > /entrypoint.sh <<'EOF_ENTRYPOINT' && chmod +x /entrypoint.sh
#!/bin/sh
set -eu

JAVA_OPTS=""
if [ "${SENTRY_ENABLE:-false}" = "true" ]; then
  JAVA_OPTS="$JAVA_OPTS -javaagent:/service/sentry-opentelemetry-agent-8.63.0.jar"
  if [ -n "${SENTRY_PROPERTIES_FILE:-}" ] && [ -f "${SENTRY_PROPERTIES_FILE}" ]; then
    JAVA_OPTS="$JAVA_OPTS -Dsentry.properties.file=${SENTRY_PROPERTIES_FILE}"
  fi
fi

export THEIA_CLOUD_SERVICE_AUTH_TOKEN="${SERVICE_AUTH_TOKEN:-${APPID:-}}"
export THEIA_CLOUD_ADMIN_API_TOKEN="${ADMIN_API_TOKEN:-}"
export THEIA_CLOUD_SESSIONS_PER_USER="${SESSIONS_PER_USER:-0}"
export QUARKUS_OIDC_CREDENTIALS_SECRET="${KEYCLOAK_CLIENTSECRET:-}"
if [ -n "${KEYCLOAK_AUDIENCE:-}" ]; then
  export QUARKUS_OIDC_TOKEN_AUDIENCE="${KEYCLOAK_AUDIENCE}"
fi
if [ -n "${KEYCLOAK_TRUSTSTORE_FILE:-}" ]; then
  # Quarkus TLS registry: a named configuration "oidc" that the OIDC client uses
  export QUARKUS_OIDC_TLS_TLS_CONFIGURATION_NAME=oidc
  case "${KEYCLOAK_TRUSTSTORE_FILE}" in
    *.jks|*.JKS) TRUSTSTORE_TYPE=JKS ;;
    *) TRUSTSTORE_TYPE=P12 ;;
  esac
  export "QUARKUS_TLS_OIDC_TRUST_STORE_${TRUSTSTORE_TYPE}_PATH=${KEYCLOAK_TRUSTSTORE_FILE}"
  export "QUARKUS_TLS_OIDC_TRUST_STORE_${TRUSTSTORE_TYPE}_PASSWORD=${KEYCLOAK_TRUSTSTORE_PASSWORD:-}"
fi
if [ -n "${CORS_ORIGINS:-}" ]; then
  export QUARKUS_HTTP_CORS_ENABLED=true
  export QUARKUS_HTTP_CORS_ORIGINS="${CORS_ORIGINS}"
fi

exec java $JAVA_OPTS \
  -Dquarkus.http.port="${SERVICE_PORT}" \
  -Dtheia.cloud.auth.admin.group="${KEYCLOAK_ADMIN_GROUP}" \
  -Dtheia.cloud.use.keycloak="${KEYCLOAK_ENABLE}" \
  -Dquarkus.oidc.auth-server-url="${KEYCLOAK_SERVERURL}" \
  -Dquarkus.oidc.client-id="${KEYCLOAK_CLIENTID}" \
  -jar ./service.jar \
  "$@"
EOF_ENTRYPOINT

EXPOSE 8081 9000
RUN groupadd -g 10001 service && useradd -r -m -u 10001 -g service -s /usr/sbin/nologin service
USER 10001

ENTRYPOINT ["/entrypoint.sh"]
