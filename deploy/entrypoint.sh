#!/bin/sh
# Starts the Gramalyzer container (backend, Telegram bot and website) with nothing to configure:
#  * no GRAMALYZER_API_TOKEN set: one is made on the first start and kept in the keys volume, which the Studio
#    container reads too, so Studio signs in by itself. Show it with: docker compose exec gramalyzer cat /app/keys/api-token
#  * a TELEGRAM_BOT_TOKEN switches the bot on unless TELEGRAM_BOT_ENABLED says otherwise.
set -eu

keys="${GRAMALYZER_KEYS_DIR:-/app/keys}"
if [ "${SPRING_PROFILES_ACTIVE:-}" != "demo" ]; then
  if [ -z "${GRAMALYZER_API_TOKEN:-}" ]; then
    if [ ! -s "$keys/api-token" ]; then
      (umask 077 && head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$keys/api-token")
      echo "Created a workspace access token. Show it with: docker compose exec gramalyzer cat $keys/api-token"
    fi
    GRAMALYZER_API_TOKEN="$(cat "$keys/api-token")"
    export GRAMALYZER_API_TOKEN
  elif [ -w "$keys" ]; then
    # share the token from .env with Studio
    (umask 077 && printf '%s' "$GRAMALYZER_API_TOKEN" > "$keys/api-token")
  fi
fi

# compose passes unset settings as empty strings, which Spring can't read as true/false
if [ -z "${TELEGRAM_BOT_ENABLED:-}" ]; then
  if [ -n "${TELEGRAM_BOT_TOKEN:-}" ]; then TELEGRAM_BOT_ENABLED=true; else TELEGRAM_BOT_ENABLED=false; fi
  export TELEGRAM_BOT_ENABLED
fi
for name in GRAMALYZER_API_TOKEN TELEGRAM_BOT_TOKEN BOT_ADMINS; do
  eval "value=\${$name:-}"
  [ -z "$value" ] && unset "$name"
done

# exec: Java gets docker's SIGTERM itself and shuts down in order (last channel refresh, backup, then the database).
# A JVM out of memory exits instead of limping on, and the restart policy brings it back.
exec java -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -jar /app/app.jar "$@"
