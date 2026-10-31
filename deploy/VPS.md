# Running Gramalyzer on a small VPS

One cheap server runs everything: PostgreSQL, the backend, the Telegram bot (long polling) and the website, plus Caddy for HTTPS. Nothing sleeps, so the posting queue and channel tracking keep running.

## What to rent

Any Linux VPS with **2 GB RAM**, 1–2 vCPU and 20 GB disk (Ubuntu 24.04 or Debian 12). 1 GB is too tight for the JVM plus PostgreSQL. Pick a provider that accepts your card and has a data centre in Europe. Expect roughly €4–6 a month.

## One-time setup

1. Point a domain (or a free subdomain, e.g. from DuckDNS) at the server's IP.
2. Install Docker: `curl -fsSL https://get.docker.com | sh`.
3. Open ports 80 and 443 (`ufw allow 80,443/tcp` and `ufw allow OpenSSH`, then `ufw enable`). Don't open 8080 or 5432.
4. Clone both repos side by side:
   ```
   git clone https://github.com/Halty323/GramalyzerBot.git
   git clone https://github.com/Halty323/GramalyzerVibe.git
   cd GramalyzerBot
   ```
5. `cp .env.example .env` and set at least `POSTGRES_PASSWORD`, `GRAMALYZER_API_TOKEN` (`openssl rand -hex 32`; a public server should not rely on the generated one), `TELEGRAM_BOT_ENABLED=true`, `TELEGRAM_BOT_TOKEN`, `BOT_ADMINS`, and add `DOMAIN=your.domain`.
6. Start it: `docker compose -f compose.yaml -f compose.postgres.yaml -f compose.vps.yaml up -d --build gramalyzer caddy`. The first build takes several minutes.
7. Open `https://your.domain` and sign in with the API token. `docker compose logs -f gramalyzer` shows startup; if `BOT_ADMINS` is empty, look for the `/claim CODE` line.

Paid plans in the bot (Telegram Stars) are on with this setup. Set `BILLING_ENABLED=false` in `.env` to give everyone unlimited use, or change the prices with `BILLING_CREATOR_STARS` / `BILLING_PRO_STARS`. Earned Stars show up in the bot's balance in Telegram; withdrawals go through Fragment (1,000 Stars minimum).

## Updating

```
git -C ../GramalyzerVibe pull && git pull
docker compose -f compose.yaml -f compose.postgres.yaml -f compose.vps.yaml up -d --build gramalyzer caddy
```

Flyway migrates the database on start.

## Backups

The data lives in the `db-data` Docker volume. A nightly dump is enough:

```
docker compose exec -T db pg_dump -U gramalyzer gramalyzer | gzip > ~/gramalyzer-$(date +%F).sql.gz
```

Add it to cron and copy the files off the server now and then (or use the provider's snapshots).

Studio isn't started on the server (it is a local app); run it on your computer against `https://your.domain` with the binaries or `docker compose up studio` and `STUDIO_SERVER=https://your.domain` plus the token in `.env`.

## Notes

* Keep `.env` private; it holds the bot token and the API token.
* The live PostgreSQL setup has not been run in production yet (see the README), so watch `docker compose logs gramalyzer` after the first start.
* If the server is low on memory, add a 2 GB swap file.
