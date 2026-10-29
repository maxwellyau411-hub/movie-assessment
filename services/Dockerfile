# syntax=docker/dockerfile:1
# Shared image recipe for both backend services. Build context: repository root.
#   docker build -f services/Dockerfile --build-arg SERVICE=media-service .
ARG SERVICE

FROM eclipse-temurin:21.0.12.1_1-jdk-noble AS build
ARG SERVICE
WORKDIR /workspace

# Optional TLS-inspection CA for dependency downloads (empty by default; never in the runtime image).
RUN --mount=type=secret,id=extra_ca,required=false \
    if [ -s /run/secrets/extra_ca ]; then \
      keytool -importcert -noprompt -cacerts -storepass changeit -alias extra-build-ca -file /run/secrets/extra_ca; \
    fi

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY contracts contracts
COPY services services
# Tests need Docker (Testcontainers) and run outside the image build: ./gradlew build
# sharing=locked: both service images use this cache; Gradle's own file locks don't work across concurrent builds.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon --console=plain ":services:${SERVICE}:bootJar"

FROM eclipse-temurin:21.0.12.1_1-jre-noble
ARG SERVICE
RUN useradd --system --uid 10001 --home-dir /app app \
 && mkdir -p /app /var/lib/app \
 && chown app /var/lib/app
WORKDIR /app
COPY --from=build /workspace/services/${SERVICE}/build/libs/${SERVICE}.jar /app/app.jar
USER app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
