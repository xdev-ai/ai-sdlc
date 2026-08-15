FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY management-server/pom.xml management-server/pom.xml
COPY portal/pom.xml portal/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -q -pl portal -am dependency:go-offline
COPY portal/src portal/src
RUN mvn -q -pl portal -am -DskipTests package

FROM eclipse-temurin:25.0.3_9-jre
WORKDIR /app
COPY --from=build /workspace/portal/target/portal-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
