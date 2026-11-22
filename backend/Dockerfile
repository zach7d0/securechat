# syntax=docker/dockerfile:1
# One Dockerfile for every backend service. The reactor is built ONCE in the shared "build" stage
# (identical across services, so BuildKit reuses it); each image then takes only its own jar.
FROM maven:3.9-eclipse-temurin-21-noble AS build
WORKDIR /src
COPY pom.xml .
COPY platform platform
COPY services services
RUN --mount=type=cache,target=/root/.m2 mvn -q -B package -DskipTests

FROM build AS extract
ARG SERVICE
RUN cp services/${SERVICE}/target/${SERVICE}-0.1.0-SNAPSHOT.jar /app.jar \
 && java -Djarmode=tools -jar /app.jar extract --layers --launcher --destination /layers

FROM eclipse-temurin:21-jre-noble
ARG SERVICE
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=extract /layers/dependencies/ ./
COPY --from=extract /layers/spring-boot-loader/ ./
COPY --from=extract /layers/snapshot-dependencies/ ./
COPY --from=extract /layers/application/ ./
USER app
ENV SERVICE_NAME=${SERVICE} LOG_FORMAT=ecs
# JVM flags come from JAVA_TOOL_OPTIONS (compose sets it); no secrets on the command line.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
