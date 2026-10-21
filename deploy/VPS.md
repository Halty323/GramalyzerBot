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
5. `cp .env.example .env` and set at least `POSTGRES_PASSWORD`, `GRAMALYZER_API_TOKEN` (`openssl rand -hex 32`), `TELEGRAM_BOT_ENABLED=true`, `TELEGRAM_BOT_TOKEN`, `BOT_ADMINS`, and add `DOMAIN=your.domain`.
6. Start it: `docker compose -f compose.yaml -f compose.vps.yaml up -d --build`. The first build takes several minutes.
7. Open `https://your.domain` and sign in with the API token. `docker compose logs -f app` shows startup; if `BOT_ADMINS` is empty, look for the `/claim CODE` line.

## Updating

```
git -C ../GramalyzerVibe pull && git pull
docker compose -f compose.yaml -f compose.vps.yaml up -d --build
```

Flyway migrates the database on start.

## Backups

The data lives in the `db-data` Docker volume. A nightly dump is enough:

```
docker compose exec -T db pg_dump -U gramalyzer gramalyzer | gzip > ~/gramalyzer-$(date +%F).sql.gz
```

Add it to cron and copy the files off the server now and then (or use the provider's snapshots).

## Notes

* Keep `.env` private; it holds the bot token and the API token.
* The live PostgreSQL setup has not been run in production yet (see the README), so watch `docker compose logs app` after the first start.
* If the server is low on memory, add a 2 GB swap file.
