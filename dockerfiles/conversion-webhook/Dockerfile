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
WORKDIR /conversion
COPY java/common ./common
COPY java/conversion ./conversion
RUN --mount=type=cache,target=/root/.m2 \
    cd /conversion/common/maven-conf && \
    mvn clean install --no-transfer-progress -Drevision=${APP_VERSION} && \
    cd /conversion/common/org.eclipse.theia.cloud.common && \
    mvn clean install --no-transfer-progress -Drevision=${APP_VERSION} && \
    cd /conversion/conversion/org.eclipse.theia.cloud.conversion && \
    mvn clean package -Drevision=${APP_VERSION} -Dmaven.test.skip=true -Dquarkus.package.type=uber-jar --no-transfer-progress

# Java4Shell (CVE-2026-114938): run on OpenJDK 27.0.1.1 (glibc, no Alpine build exists). Switch back to a vendor LTS build once one ships the fix.
FROM debian:13.7-slim
ARG TARGETARCH
ARG JDK_VERSION=27.0.1.1
ARG JDK_SHA256_AMD64=82172b79a4e4a8546e2538c525b63e020f419922a5d30f9873bbb832ce95637b
ARG JDK_SHA256_ARM64=7b851f4cdf55ce48dc33584349177aa271df0bbd0ff5711a6e7fcd812ef5a90a
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl p11-kit && rm -rf /var/lib/apt/lists/* && \
    case "$TARGETARCH" in \
      amd64) JDK_ARCH=x64; JDK_SHA256="$JDK_SHA256_AMD64" ;; \
      arm64) JDK_ARCH=aarch64; JDK_SHA256="$JDK_SHA256_ARM64" ;; \
      *) echo "Unsupported TARGETARCH: $TARGETARCH" >&2; exit 1 ;; \
    esac && \
    curl -fsSL -o /tmp/jdk.tar.gz "https://download.java.net/java/GA/jdk${JDK_VERSION}/c3a705caf5044be8aa0b09e62be69b20/4/GPL/openjdk-${JDK_VERSION}_linux-${JDK_ARCH}_bin.tar.gz" && \
    echo "${JDK_SHA256}  /tmp/jdk.tar.gz" | sha256sum -c - && \
    mkdir -p /opt/java && tar -xzf /tmp/jdk.tar.gz -C /opt/java --strip-components=1 && \
    rm -rf /tmp/jdk.tar.gz /opt/java/jmods /opt/java/lib/src.zip /opt/java/include /opt/java/man && \
    trust extract --format=java-cacerts --filter=ca-anchors --purpose=server-auth --overwrite /opt/java/lib/security/cacerts && \
    apt-get purge -y --auto-remove curl p11-kit
ENV JAVA_HOME=/opt/java
ENV PATH=/opt/java/bin:$PATH
ARG APP_VERSION=1.2.0-SNAPSHOT
WORKDIR /conversion
COPY --from=builder /conversion/conversion/org.eclipse.theia.cloud.conversion/target/conversion-webhook-${APP_VERSION}-runner.jar ./conversion-webhook.jar

ENV CERT_RELOAD_PERIOD=604800

RUN groupadd -g 10001 webhook && useradd -r -m -u 10001 -g webhook -s /usr/sbin/nologin webhook
USER 10001

ENTRYPOINT ["java", "-Dquarkus.http.ssl.certificate.reload-period=${CERT_RELOAD_PERIOD}", "-jar", "./conversion-webhook.jar"]
CMD [ "" ]
