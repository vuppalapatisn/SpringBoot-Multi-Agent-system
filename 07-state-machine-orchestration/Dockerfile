# syntax=docker/dockerfile:1
#
# 07 State Machine Orchestration -- port 8087
#
# The build context is just this directory. Each project declares spring-boot-starter-parent
# with an empty <relativePath/>, so it builds standalone without the aggregator pom.
#
#   docker build -t agentic/state-machine-orchestration .
#   docker run --rm -p 8087:8087 -e ANTHROPIC_API_KEY agentic/state-machine-orchestration
#
# ANTHROPIC_API_KEY is required at runtime and is deliberately NOT baked into the image.
# application.yml resolves it with no default, so a container started without it fails fast
# and loudly rather than starting up and failing at the first model call.

# ---------------------------------------------------------------- build stage
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build

# The pom is copied alone first so the dependency layer is invalidated only when dependencies
# actually change. A source-only edit then rebuilds without re-resolving Maven.
COPY pom.xml ./
RUN mvn -B -ntp -DskipTests dependency:go-offline \
 || echo "partial pre-fetch: the package step will resolve whatever is missing"

COPY src ./src

# Tests run in CI (mvn verify), not here. A Docker build should not be the only place the suite
# executes, and skipping keeps image builds fast and their output reproducible.
RUN mvn -B -ntp -DskipTests package

# Spring Boot layered extraction, verified against Boot 4.0.8: the layers are dependencies,
# spring-boot-loader, snapshot-dependencies and application. Splitting them means a code change
# only invalidates the last, thin layer instead of re-pushing every jar.
RUN java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination /build/extracted

# -------------------------------------------------------------- runtime stage
FROM eclipse-temurin:21-jre-alpine AS runtime

LABEL org.opencontainers.image.title="07 State Machine Orchestration" \
      org.opencontainers.image.description="Persisted FSM with durable approvals, expiry timers, reconciliation and saga compensation." \
      org.opencontainers.image.source="https://github.com/vuppalapatisn/SpringBoot-Multi-Agent-system" \
      org.opencontainers.image.licenses="Apache-2.0"

# Non-root. An agentic service holds provider credentials and calls out to the world; least
# authority applies to the container as much as it does to the agents inside it.
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app

# H2 writes to ./data. Declared as a volume because durability is the property this project
# exists to demonstrate: a restart must not lose a pending approval, and neither should
# docker run --rm.
RUN mkdir -p /app/data && chown app:app /app/data
VOLUME ["/app/data"]

# Copied most-stable layer first, so the layers that rarely change are cached furthest down.
COPY --from=build --chown=app:app /build/extracted/dependencies/ ./
COPY --from=build --chown=app:app /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /build/extracted/application/ ./

USER app
EXPOSE 8087

# MaxRAMPercentage rather than a fixed -Xmx: the JVM reads the cgroup limit, so one image
# behaves sensibly under any --memory setting. ExitOnOutOfMemoryError makes a dead JVM
# restartable rather than a zombie that fails health checks forever.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# busybox wget ships in the Alpine base, so this needs no extra package.
HEALTHCHECK --interval=30s --timeout=3s --start-period=45s --retries=3 \
  CMD wget -q --spider http://localhost:8087/actuator/health || exit 1

# exec inside the shell so the JVM becomes PID 1 and receives SIGTERM directly, which is
# what makes Spring Boot's graceful shutdown work in a container. The shell is only there to
# expand JAVA_OPTS.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]