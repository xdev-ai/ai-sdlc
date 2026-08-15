FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY management-server/pom.xml management-server/pom.xml
COPY portal/pom.xml portal/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -q -pl management-server -am dependency:go-offline
COPY management-server/src management-server/src
RUN mvn -q -pl management-server -am -DskipTests package

FROM eclipse-temurin:25.0.3_9-jre
WORKDIR /app
COPY --from=build /workspace/management-server/target/management-server-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
