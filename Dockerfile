# syntax=docker/dockerfile:1.7
#
# One image with the backend (this repo) and the website (GramalyzerVibe). Spring serves the built website
# itself, so the whole app is a single container on port 8080.
#
# The website checkout is passed in as the named build context "vibe" – compose.yaml does that for you:
#   docker build --build-context vibe=../GramalyzerVibe -t gramalyzer .

# ---------------------------------------------------------------- website
FROM node:24-bookworm-slim AS frontend
WORKDIR /web
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
COPY --from=vibe package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY --from=vibe index.html tsconfig.json vite.config.ts ./
COPY --from=vibe src ./src
COPY --from=vibe shared ./shared
COPY --from=vibe scripts ./scripts
COPY --from=vibe tests ./tests
# type-checks, then builds into dist/
RUN npm run build

# ---------------------------------------------------------------- backend
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /src
COPY pom.xml ./
COPY src ./src
# Tests run outside the image build (mvn test / CI); see README.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package && cp target/gramalyzerbot-*.jar /app.jar

# ---------------------------------------------------------------- runtime
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home-dir /app gramalyzer \
 && mkdir -p /app/.data && chown gramalyzer /app/.data
WORKDIR /app
COPY --from=backend /app.jar ./app.jar
COPY --from=frontend /web/dist ./web
ENV HOST=0.0.0.0 \
    PORT=8080 \
    GRAMALYZER_WEB_ROOT=file:/app/web/
USER gramalyzer
EXPOSE 8080
# H2 files of the demo profile (the live setup uses PostgreSQL)
VOLUME /app/.data
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
