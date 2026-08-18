FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY management-server/pom.xml management-server/pom.xml
COPY portal/pom.xml portal/pom.xml
COPY sdk/pom.xml sdk/pom.xml
# The cache mount belongs on BOTH Maven steps. A BuildKit cache mount is not part of the image layer, so the
# dependency:go-offline above warmed a directory that the package step below could not see: /root/.m2 was empty
# again and every build re-downloaded the whole dependency set. The prefetch step accomplished nothing, and the
# integration job started timing out at 420s with a single 339s package step.
RUN --mount=type=cache,target=/root/.m2 mvn -q -pl portal -am dependency:go-offline
COPY portal/src portal/src
COPY portal/frontend/package.json portal/frontend/package-lock.json portal/frontend/vite.config.mjs portal/frontend/
COPY portal/frontend/src portal/frontend/src
RUN --mount=type=cache,target=/root/.m2 mvn -q -pl portal -am -DskipTests package

# Pinned OpenTelemetry Java agent. The digest is verified at build time so a compromised or truncated download
# fails the build instead of shipping an unverified agent into a governed runtime.
FROM alpine:3.24 AS otel-agent
ARG OTEL_AGENT_VERSION=2.30.0
ARG OTEL_AGENT_SHA256=9d6bc2ad8dd8fb7f730984988e57b8ac0a82d81c7b3b8ae795378718733a509d
RUN apk add --no-cache curl \
    && curl -sSfLo /tmp/opentelemetry-javaagent.jar \
       "https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${OTEL_AGENT_VERSION}/opentelemetry-javaagent-${OTEL_AGENT_VERSION}.jar" \
    && echo "${OTEL_AGENT_SHA256}  /tmp/opentelemetry-javaagent.jar" | sha256sum -c -


# The minimal Alpine runtime omits the vulnerable Debian Pebble artifact; digest pin preserves Java 25.
FROM eclipse-temurin:25-jre-alpine-3.23@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0
RUN apk upgrade --no-cache \
    && addgroup -S -g 10001 aisdlc \
    && adduser -S -D -h /home/aisdlc -u 10001 -G aisdlc aisdlc
WORKDIR /app
COPY --chown=aisdlc:aisdlc --from=build /workspace/portal/target/portal-*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=25 -Djava.io.tmpdir=/tmp"
COPY --from=otel-agent --chown=root:root --chmod=0444 /tmp/opentelemetry-javaagent.jar /opt/opentelemetry/opentelemetry-javaagent.jar
COPY --chown=root:root --chmod=0555 infra/observability/entrypoint-with-optional-agent.sh /usr/local/bin/aisdlc-entrypoint
USER 10001:10001
EXPOSE 8080
ENV AISDLC_TELEMETRY_SERVICE_NAME=ai-sdlc-portal
ENTRYPOINT ["/usr/local/bin/aisdlc-entrypoint"]
