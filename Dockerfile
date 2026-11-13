# syntax=docker/dockerfile:1

# ---- Build stage: compile and package with the Maven wrapper -------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Resolve dependencies first so they are cached until pom.xml or the wrapper changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
# Tests and the quality gate run in CI / locally (./mvnw -B verify), not in the image build.
RUN ./mvnw -B -q -DskipTests package \
    && java -Djarmode=tools -jar target/ecommerce-inventory-*.jar extract --layers --launcher --destination target/extracted

# ---- Runtime stage: slim JRE 21, non-root user -----------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

RUN addgroup -S inventory && adduser -S -G inventory -H -s /sbin/nologin inventory

# Spring Boot layers, least to most frequently changed, for better image layer caching.
COPY --from=build --chown=inventory:inventory /workspace/target/extracted/dependencies/ ./
COPY --from=build --chown=inventory:inventory /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build --chown=inventory:inventory /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=inventory:inventory /workspace/target/extracted/application/ ./

USER inventory
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -q -O /dev/null http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
