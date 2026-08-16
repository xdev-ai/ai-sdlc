FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY management-server/pom.xml management-server/pom.xml
COPY portal/pom.xml portal/pom.xml
COPY sdk/pom.xml sdk/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -q -pl portal -am dependency:go-offline
COPY portal/src portal/src
COPY portal/frontend/package.json portal/frontend/package-lock.json portal/frontend/vite.config.mjs portal/frontend/
COPY portal/frontend/src portal/frontend/src
RUN mvn -q -pl portal -am -DskipTests package

# The minimal Alpine runtime omits the vulnerable Debian Pebble artifact; digest pin preserves Java 25.
FROM eclipse-temurin:25-jre-alpine-3.23@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0
RUN apk upgrade --no-cache \
    && addgroup -S -g 10001 aisdlc \
    && adduser -S -D -h /home/aisdlc -u 10001 -G aisdlc aisdlc
WORKDIR /app
COPY --chown=aisdlc:aisdlc --from=build /workspace/portal/target/portal-*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=25 -Djava.io.tmpdir=/tmp"
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
