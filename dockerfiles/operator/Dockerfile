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
WORKDIR /operator
COPY java/common ./common
COPY java/operator ./operator
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
    cd /operator/common/maven-conf && \
    mvn clean install --no-transfer-progress -Drevision=${APP_VERSION} $MAVEN_SENTRY_ARGS && \
    cd /operator/common/org.eclipse.theia.cloud.common && \
    mvn clean install --no-transfer-progress -Drevision=${APP_VERSION} $MAVEN_SENTRY_ARGS && \
    cd /operator/operator/org.eclipse.theia.cloud.operator && \
    mvn clean install --no-transfer-progress -Drevision=${APP_VERSION} $MAVEN_SENTRY_ARGS && \
    cd /operator/operator/org.eclipse.theia.cloud.defaultoperator && \
    mvn clean verify --no-transfer-progress -Drevision=${APP_VERSION} $MAVEN_SENTRY_ARGS

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
RUN mkdir /templates
WORKDIR /log-config
COPY java/operator/org.eclipse.theia.cloud.defaultoperator/log4j2.xml .
WORKDIR /operator
RUN curl -fsSL -o sentry-agent.zip https://github.com/getsentry/sentry-java/releases/download/8.63.0/sentry-opentelemetry-agent-8.63.0.zip && \
    unzip -j sentry-agent.zip -d . && \
    rm sentry-agent.zip && \
    apt-get purge -y --auto-remove curl unzip p11-kit
COPY --from=builder /operator/operator/org.eclipse.theia.cloud.defaultoperator/target/defaultoperator-${APP_VERSION}-jar-with-dependencies.jar ./operator.jar
COPY --from=builder /operator/operator/org.eclipse.theia.cloud.defaultoperator/sentry.properties ./sentry.properties

# If set to true, enables Sentry Java agent flags.
ENV SENTRY_ENABLE=false
# Default path for the baked-in Sentry properties file.
# This can still be overridden with a mounted file or env-based config if needed.
ENV SENTRY_PROPERTIES_FILE=/operator/sentry.properties

RUN cat > /entrypoint.sh <<'EOF_ENTRYPOINT' && chmod +x /entrypoint.sh
#!/bin/sh
set -eu

JAVA_OPTS=""
if [ "${SENTRY_ENABLE:-false}" = "true" ]; then
  JAVA_OPTS="$JAVA_OPTS -javaagent:/operator/sentry-opentelemetry-agent-8.63.0.jar"
  if [ -n "${SENTRY_PROPERTIES_FILE:-}" ] && [ -f "${SENTRY_PROPERTIES_FILE}" ]; then
    JAVA_OPTS="$JAVA_OPTS -Dsentry.properties.file=${SENTRY_PROPERTIES_FILE}"
  fi
fi

exec java $JAVA_OPTS \
  -Dlog4j2.configurationFile=/log-config/log4j2.xml \
  -jar ./operator.jar \
  "$@"
EOF_ENTRYPOINT

RUN groupadd -g 10001 theia-operator && useradd -r -m -u 10001 -g theia-operator -s /usr/sbin/nologin theia-operator
USER 10001

# to get more debug information from the kubernetes client itself, add -Dorg.slf4j.simpleLogger.defaultLogLevel=DEBUG below
ENTRYPOINT ["/entrypoint.sh"]
