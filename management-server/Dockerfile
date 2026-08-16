FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY management-server/pom.xml management-server/pom.xml
COPY portal/pom.xml portal/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -q -pl management-server -am dependency:go-offline
COPY management-server/src management-server/src
RUN mvn -q -pl management-server -am -DskipTests package

# Digest pin preserves Java 25 while taking the current Temurin runtime security rebuild.
FROM eclipse-temurin:25-jre@sha256:a214efa3200af4b657e41935799aa12d7aee3336fdb42eb505a0948f6ecdd983
RUN groupadd --system --gid 10001 aisdlc && useradd --system --uid 10001 --gid 10001 --create-home --home-dir /home/aisdlc aisdlc
WORKDIR /app
COPY --chown=aisdlc:aisdlc --from=build /workspace/management-server/target/management-server-*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=25 -Djava.io.tmpdir=/tmp"
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
