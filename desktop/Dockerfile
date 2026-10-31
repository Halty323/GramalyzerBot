# syntax=docker/dockerfile:1.7
#
# The Studio container: Gramalyzer Studio (the desktop app) on a virtual screen, shown in any browser through noVNC.
#
#   docker build -t gramalyzer-studio desktop/      (compose.yaml in the repository root does this for you)
#   docker run -p 127.0.0.1:6080:6080 -e GRAMALYZER_SERVER=http://host.docker.internal:8080 gramalyzer-studio
#   → http://localhost:6080
#
# Studio draws with its built-in software renderer, so no GPU is needed. For a native window use the binaries
# instead (desktop/README.md).

# ---------------------------------------------------------------- build
FROM rust:1-bookworm AS build
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY src ./src
COPY assets ./assets
COPY locales ./locales
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/src/target \
    cargo build --release --locked && cp target/release/gramalyzer-desktop /gramalyzer-studio

# ---------------------------------------------------------------- runtime
FROM ubuntu:24.04
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      xvfb x11vnc novnc python3-websockify curl ca-certificates \
      libxkbcommon-x11-0 libx11-xcb1 libxcursor1 libxrandr2 libxi6 \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --create-home --home-dir /home/studio studio \
 && mkdir -p /data && chown studio /data
COPY --from=build /gramalyzer-studio /usr/local/bin/gramalyzer-studio
COPY --chmod=755 docker/start.sh /usr/local/bin/start-studio
ENV DISPLAY=:0 \
    GRAMALYZER_STUDIO_CONFIG=/data/config.json \
    GRAMALYZER_SERVER=http://gramalyzer:8080 \
    STUDIO_SIZE=1440x900
USER studio
EXPOSE 6080
# Studio's settings: AI provider, keys, language, theme
VOLUME /data
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD curl -fsS http://127.0.0.1:6080/vnc.html || exit 1
ENTRYPOINT ["/usr/local/bin/start-studio"]
