FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY management-server/pom.xml management-server/pom.xml
COPY portal/pom.xml portal/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -q -pl portal -am dependency:go-offline
COPY portal/src portal/src
COPY portal/frontend/package.json portal/frontend/package-lock.json portal/frontend/vite.config.mjs portal/frontend/
COPY portal/frontend/src portal/frontend/src
RUN mvn -q -pl portal -am -DskipTests package

FROM eclipse-temurin:25.0.3_9-jre
RUN groupadd --system --gid 10001 aisdlc && useradd --system --uid 10001 --gid 10001 --create-home --home-dir /home/aisdlc aisdlc
WORKDIR /app
COPY --chown=aisdlc:aisdlc --from=build /workspace/portal/target/portal-*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=25 -Djava.io.tmpdir=/tmp"
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
