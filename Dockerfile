# syntax=docker/dockerfile:1
# One recipe for every service of the mono-repo:
#   docker build --build-arg MODULE=user-service -t event-platform/user-service .
# Tests are not run here: they need Docker themselves (Testcontainers) and run in CI before any image is built.

ARG JAVA_VERSION=21

# Java bytecode is the same on every platform: compile once, natively, then assemble a runtime per platform.
FROM --platform=$BUILDPLATFORM eclipse-temurin:${JAVA_VERSION}-jdk AS build
# The Maven wrapper downloads and unpacks Maven itself. Installed before ARG MODULE: one cached layer for all modules.
RUN apt-get update && apt-get install --yes --no-install-recommends curl unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace

# Maven goes into an image layer shared by every module, outside the cache mount used below: parallel builds
# on a cold cache would otherwise race to unpack the same distribution into /root/.m2/wrapper.
ENV MAVEN_USER_HOME=/opt/maven-wrapper
COPY .mvn/ .mvn/
COPY mvnw ./
RUN ./mvnw --batch-mode --version > /dev/null

# Parallel builds share the local repository (cache mount): file locks keep concurrent downloads safe.
ENV MAVEN_ARGS="-Daether.syncContext.named.factory=file-lock -Daether.syncContext.named.nameMapper=file-gav"

ARG MODULE
RUN test -n "${MODULE}" || (echo "Build argument MODULE is required" && exit 1)

# POMs first: the dependency download stays cached until a pom.xml changes.
COPY pom.xml ./
COPY discovery-server/pom.xml discovery-server/
COPY api-gateway/pom.xml api-gateway/
COPY user-service/pom.xml user-service/
COPY event-service/pom.xml event-service/
COPY registration-service/pom.xml registration-service/
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --quiet --projects "${MODULE}" --also-make dependency:go-offline

COPY ${MODULE}/src ${MODULE}/src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --quiet --projects "${MODULE}" --also-make package -DskipTests \
    && java -Djarmode=tools -jar "${MODULE}"/target/"${MODULE}"-*.jar \
        extract --layers --launcher --destination /workspace/extracted

FROM eclipse-temurin:${JAVA_VERSION}-jre AS runtime
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app

# Least to most frequently changed: a code change only rebuilds and ships the last layer.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER app
# Size the heap from the container memory limit, and restart cleanly rather than limp on after an OOM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
