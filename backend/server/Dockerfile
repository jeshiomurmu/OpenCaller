# syntax=docker/dockerfile:1

# ---- Build stage: compile and assemble the distribution with the Gradle wrapper ----
FROM eclipse-temurin:17-jdk-noble AS build
WORKDIR /workspace

# Copy the build definition first so dependency resolution is cached separately from sources.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon --quiet dependencies > /dev/null

COPY src ./src
RUN ./gradlew --no-daemon installDist

# ---- Runtime stage: JRE only, non-root user ----
FROM eclipse-temurin:17-jre-noble AS runtime

ARG GIT_SHA=dev
ENV APP_VERSION=${GIT_SHA} \
    PORT=8080

RUN groupadd --system --gid 10001 opencaller \
    && useradd --system --uid 10001 --gid opencaller --no-create-home --shell /usr/sbin/nologin opencaller

WORKDIR /app
COPY --from=build /workspace/build/install/opencaller-server/ /app/

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/app/bin/opencaller-server"]
