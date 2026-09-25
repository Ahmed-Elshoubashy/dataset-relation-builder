# ---- build: JDK 21
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null   # cache dependencies in their own layer
COPY src ./src
RUN ./gradlew --no-daemon -q installDist

# ---- run: Java 21 runtime
FROM eclipse-temurin:21-jre

# tesseract makes the "Local OCR" option work; curl is for the health check
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /src/build/install/entity-grapgh-resolver /opt/app

RUN useradd --create-home app \
    && mkdir -p /app/data \
    && chown app:app /app/data
USER app
WORKDIR /app

# compose.yaml mounts a laptop folder at its own path and sets ERKG_BROWSE_ROOT / ERKG_DATA_ROOT;
# the graph, OCR cache and extracted archive members live in /app/data.
ENV ERKG_WORK_DIR=/app/data \
    PORT=8765
VOLUME /app/data
EXPOSE 8765

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s \
    CMD curl -fs http://127.0.0.1:8765/api/stats > /dev/null || exit 1

ENTRYPOINT ["/opt/app/bin/entity-grapgh-resolver"]
CMD ["serve"]
