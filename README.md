# GramalyzerBot

The shared Java 21 / Spring Boot backend behind the Gramalyzer admin website ([GramalyzerVibe](https://github.com/Halty323/GramalyzerVibe)) and the Telegram bot. It has two modules that share one database and one Telegram bot:

| Module | Package | What it does |
|---|---|---|
| **CRM workspace** | `workspace` | The website's internal REST API (`/api`): channels, the text-post queue, the delivery worker, observed subscriber history, demo data. |
| **Channel analytics** | `analytics`, `bot`, `channel`, `post`, `user`, `settings`, `scheduler` | The Telegram bot: works out **when to post** and **what to write about**, configured entirely in Telegram DMs, with Owner / Admin / Moderator roles, invites, weekly digests and "good time to post" nudges. |
| **Bridge** | `crm` | Keeps the two in step and serves the analysis to the website (`GET /api/channels/{id}/insights`). |

## Run it

With the UI from the sibling GramalyzerVibe checkout:

```sh
cd ../GramalyzerVibe
npm run dev      # demo profile: sample data, Telegram off
npm test
```

Or with a local JDK 21 and Maven:

```sh
mvn test
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

The demo profile never starts Telegram polling or sends posts. It uses a separate H2 database under `.data/demo`. Its sample channels (`design`, `tech`, `makers`) get sample analytics the first time the website asks for their insights.

### Docker

One image holds the backend and the website: Spring serves the built website, so everything is on port 8080. Check out GramalyzerVibe next to this repository, as for local development. Then:

```sh
# demo: sample data, Telegram off, nothing to configure
docker compose -f compose.demo.yaml up --build          # → http://localhost:8080

# live: PostgreSQL + bot + website
cp .env.example .env                                    # set POSTGRES_PASSWORD, GRAMALYZER_API_TOKEN, and the Telegram settings
docker compose up -d --build                            # → http://localhost:8080
docker compose logs -f app                              # first start: look for "/claim CODE" if BOT_ADMINS is empty
```

* **Data:** the live database lives in the `db-data` volume; the demo's H2 files live in `demo-data`.
* **Port:** it is bound to `127.0.0.1` only. To expose it, put a reverse proxy with HTTPS in front. Spring honours `X-Forwarded-*` headers, so the session cookie is marked `Secure` behind HTTPS.
* **Updates:** `git pull` in both checkouts, then `docker compose up -d --build`. Flyway migrates the database on start.
* **Tests:** the image build skips them (`-DskipTests`); run `mvn test` or CI before deploying.

### Live configuration (environment variables)

| Variable | Required | Meaning |
|---|---|---|
| `GRAMALYZER_API_TOKEN` | yes, outside demo | Token the website signs in with |
| `TELEGRAM_BOT_ENABLED` | – | `true` starts long polling, the posting worker and the analytics jobs |
| `TELEGRAM_BOT_TOKEN` | when enabled | From @BotFather. **Never commit it.** |
| `BOT_ADMINS` | – | Who may open `/admin` in the bot: comma-separated `@usernames` and/or numeric user ids, e.g. `@halty,123456789`. A username takes effect when that person next messages the bot. If empty, the log prints a one-time `/claim CODE` at startup. (`BOT_ADMIN_IDS` still works as the old name.) |
| `BOT_PUBLIC_REGISTRATION` | – | `true` (default): anyone may connect a channel; can be switched later in `/admin` |
| `BOT_DEFAULT_TIMEZONE` | – | Timezone for newly connected channels until someone sets one (default `UTC`) |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | – | PostgreSQL in production. The default is a local H2 file. |

Then add the bot to your channel as an admin. To let the website's queue publish, give the bot **Post messages**. For comment counts, also add it to the linked discussion group. Open the bot in a private chat and tap **Connect my channel**, or just add it: the person who adds it becomes the channel's Owner in the bot.

## How the two modules fit together

* **One Telegram bot.** `bot.TelegramBot` is the only update consumer. Every update goes first to `CrmBridge.observe`, which handles channel discovery and channel posts for the website. Then `UpdateRouter` runs the analytics bot. `bot.BotRegistration` starts polling with an explicit `allowed_updates` list. The Spring starter's default polls with an empty list, which never delivers `message_reaction_count`, the update the analytics depend on.
* **One channel, two rows.** The website's `workspace_channels` (string ids) and the bot's `channels` describe the same Telegram chat. They are matched by chat id and linked through `channels.workspace_channel_id`. Linking happens when the bot is added, when someone connects through the bot, or on the first insights request. A channel connected only through the bot is also registered in the CRM.
* **Shared timezone.** Changing it on the website updates the bot. Picking one in the bot updates the website. The other settings belong to one side each: the queue's pause, signature and notifications to the website; the analysis window, metric, digest and nudges to the bot.
* **Posts the website publishes are analysed too.** Telegram doesn't send a bot updates about its own channel messages. So after each delivery, `PostingWorker` publishes a `WorkspaceEvents.PostPublished` event, and the bridge adds the post to the analysis.
* **Separate subscriber histories.** The website keeps its daily `channel_daily_metrics`. The bot keeps finer-grained `subscriber_snapshots` for its growth view.
* The workspace package never imports analytics code. It only publishes Spring events (`WorkspaceEvents`).

## Internal API

All under `/api`, with the same auth as before (session cookie from `POST /api/login`, or `Authorization: Bearer $GRAMALYZER_API_TOKEN`; no auth in demo). Errors are `{"error": "..."}`.

| Method | Path | |
|---|---|---|
| GET | `/api/health` | unchanged |
| POST | `/api/login` | unchanged |
| GET | `/api/workspace` | unchanged |
| PATCH | `/api/channels/{id}` | unchanged. A `timezone` change now also reaches the bot. |
| POST / PATCH / DELETE | `/api/posts[/{id}]` | unchanged |
| **GET** | **`/api/channels/{id}/insights`** | **new:** the analysis for one channel (`ChannelInsights` in `shared/types.ts`) |

`status` is `ready`, `learning` or `not_connected`:

* `learning`: fewer than 20 usable posts. Only `counts`, `postsNeeded`, `cadence` and `audience` are filled.
* `not_connected`: the bot has no data for this channel yet.

When `ready`, the response also has:

* `bestTimes`, `experiments`, and a 7×24 `heatmap` (Monday first, in the channel's timezone)
* `suggestions`: the next real date-time of each best slot, ready to use as a publishing time
* `content`: lift by format, length, link / no link, hashtag topic, keyword and posts-per-day
* `topPosts` and `audience` (subscriber trend)

"Lift" is a multiplier of the channel's typical engagement.

## What the analysis can and can't see

* **Telegram does not give bots view counts.** "Engagement" therefore means reactions + 3 × comments (switchable to reactions or comments only in the bot). Comments need the discussion group linked and the bot in it.
* **Bots can't read history.** Collection starts when the bot is added. Owners can back-fill with a Telegram Desktop export (`/import` in the bot, JSON, ≤ 20 MB).
* Posts younger than 24 h are ignored until their reactions settle; albums count once. Results are shrunk toward "typical" when samples are small, and every answer carries a sample size and confidence.

## Layout

```
workspace/   CRM REST API, queue, delivery worker, demo workspace       (was here before)
crm/         bridge between the two + the insights endpoint
analytics/   pure Java: engine, report, text features, scheduling rules (unit-tested)
bot/         TelegramBot, BotRegistration, UpdateMapper/Router, BotApi (all Telegram calls)
bot/ui, bot/screen, bot/flow   the bot's screens and conversations
channel/ post/ user/ settings/ scheduler/   entities, services, background jobs
```

Telegram library types appear only in `BotApi`, `UpdateMapper`, `TelegramBot`, `BotRegistration`, `CrmBridge.observe` and `workspace/LiveTelegramGateway`.

## Notes

* **The original source archive contained a hard-coded bot token. Revoke it in @BotFather** and use a new one via `TELEGRAM_BOT_TOKEN`. The old token remains in the archive and git history.
* Migration `V3__channel_analytics.sql` adds the analytics tables and extends `users`. It is written to run on both PostgreSQL and H2 (one `ADD COLUMN` per statement; no `TEXT` columns, which H2 maps to CLOB).
* The sample `/random` command and its "Get random number" keyboard were removed; `/start` now opens the analytics bot's welcome screen.
* Lombok is declared as an annotation processor (needed on JDK 23+). After pulling, run `mvn clean test`.
