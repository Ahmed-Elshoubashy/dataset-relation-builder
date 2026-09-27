# ---- build: JDK 21 with Gradle already installed (the same version as gradle/wrapper/gradle-wrapper.properties).
# The wrapper would download the Gradle distribution (~130 MB) from services.gradle.org on every fresh build,
# which is slow; the official image comes from Docker Hub instead, and much faster.
FROM gradle:9.0.0-jdk21 AS build
WORKDIR /src
COPY settings.gradle build.gradle ./
COPY src ./src
# The library jars live in a BuildKit cache that survives between builds: only the first build downloads them,
# later builds (even after build.gradle changes) only compile.
RUN --mount=type=cache,target=/root/.gradle \
    gradle --no-daemon --console=plain installDist

# ---- run: Java 21 runtime
FROM eclipse-temurin:21-jre

# tesseract makes the "Local OCR" option work; curl is for the health check
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /src/build/install/entity-graph-resolver /opt/app
COPY profiles /opt/app/profiles

RUN useradd --create-home app \
    && mkdir -p /app/data \
    && chown app:app /app/data
USER app
WORKDIR /app

# compose.yaml mounts a laptop folder at its own path and sets ERKG_BROWSE_ROOT / ERKG_DATA_ROOT;
# the graph, OCR cache and extracted archive members live in /app/data.
ENV ERKG_WORK_DIR=/app/data \
    ERKG_PROFILES_DIR=/opt/app/profiles \
    PORT=8765
VOLUME /app/data
EXPOSE 8765

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s \
    CMD curl -fs http://127.0.0.1:8765/api/stats > /dev/null || exit 1

ENTRYPOINT ["/opt/app/bin/entity-graph-resolver"]
