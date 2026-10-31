#!/bin/sh
# Runs Gramalyzer Studio on a virtual screen and serves it to the browser (noVNC on port 6080).
# Studio is restarted if it ever exits. On `docker stop` the server is asked to refresh every channel once more, the
# same as when the app's window closes on a desktop.
set -u

size="${STUDIO_SIZE:-1440x900}"
export GRAMALYZER_STUDIO_SIZE="$size"
rm -f /tmp/.X0-lock /tmp/.X11-unix/X0

Xvfb :0 -screen 0 "${size}x24" -nolisten tcp -noreset >/dev/null 2>&1 &
xvfb=$!
for _ in $(seq 1 50); do [ -e /tmp/.X11-unix/X0 ] && break; sleep 0.1; done

auth="-nopw"
if [ -n "${STUDIO_VNC_PASSWORD:-}" ]; then
  x11vnc -storepasswd "$STUDIO_VNC_PASSWORD" "$HOME/.vncpass" >/dev/null 2>&1
  auth="-rfbauth $HOME/.vncpass"
fi
# shellcheck disable=SC2086
x11vnc -display :0 -localhost -rfbport 5900 -forever -shared -quiet -noxdamage $auth >/dev/null 2>&1 &
vnc=$!
# the page at / opens the viewer, scaled to the browser window, and connects at once
mkdir -p "$HOME/web" && cp -rs /usr/share/novnc/. "$HOME/web/" 2>/dev/null
cat > "$HOME/web/index.html" <<'HTML'
<!doctype html><meta charset="utf-8"><title>Gramalyzer Studio</title>
<meta http-equiv="refresh" content="0; url=vnc.html?autoconnect=1&resize=scale&reconnect=1&reconnect_delay=1000">
HTML
websockify --web "$HOME/web" 6080 localhost:5900 >/dev/null 2>&1 &
web=$!

token() {
  if [ -n "${GRAMALYZER_API_TOKEN:-}" ]; then printf '%s' "$GRAMALYZER_API_TOKEN"
  elif [ -n "${GRAMALYZER_API_TOKEN_FILE:-}" ] && [ -r "$GRAMALYZER_API_TOKEN_FILE" ]; then cat "$GRAMALYZER_API_TOKEN_FILE"
  fi
}

stopping=0
studio=""
stop() {
  stopping=1
  t="$(token)"
  curl -fsS -m 5 -X POST ${t:+-H "Authorization: Bearer $t"} "${GRAMALYZER_SERVER%/}/api/refresh?reason=studio-close" >/dev/null 2>&1 \
    && echo "Asked the server to refresh every channel" || echo "Server not reachable; it refreshes on its own next start"
  [ -n "$studio" ] && kill "$studio" 2>/dev/null
  kill "$web" "$vnc" "$xvfb" 2>/dev/null
  exit 0
}
trap stop TERM INT

echo "Gramalyzer Studio is on http://localhost:6080 (server: $GRAMALYZER_SERVER)"
while [ "$stopping" -eq 0 ]; do
  gramalyzer-studio &
  studio=$!
  wait "$studio"
  code=$?
  [ "$stopping" -eq 1 ] && break
  echo "Studio exited ($code), starting it again"
  sleep 2
done
