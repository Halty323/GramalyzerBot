# GramalyzerBot

The shared Java 25 / Spring Boot backend behind the Gramalyzer admin website ([GramalyzerVibe](https://github.com/Halty323/GramalyzerVibe)) and the Telegram bot. It has two modules that share one database and one Telegram bot:

| Module | Package | What it does |
|---|---|---|
| **CRM workspace** | `workspace` | The website's internal REST API (`/api`): channels, the text-post queue, the delivery worker, observed subscriber history, demo data. |
| **Channel analytics** | `analytics`, `bot`, `channel`, `post`, `user`, `settings`, `scheduler` | The Telegram bot: works out **when to post** and **what to write about**, configured entirely in Telegram DMs, with Owner / Admin / Moderator roles, invites, weekly digests and "good time to post" nudges. |
| **Bridge** | `crm` | Keeps the two in step and serves the analysis and the channel viewer's feed to the website (`GET /api/channels/{id}/insights`, `/feed`). |
| **Public channel tracking** | `tracking` | Watchlists of public channels nobody had to add the bot to, for both the website and the bot: subscribers, average views, engagement rate, posting rhythm, top posts and subscriber history, read from Telegram's public web preview. |
| **Languages** | `i18n` | The bot speaks English, Russian, Ukrainian, Hindi, Indonesian, Portuguese, Spanish, Arabic, Persian, Uzbek, Turkish and Vietnamese. |

**Gramalyzer Studio**, a native desktop client for Windows, macOS and Linux written in Rust, lives in [`desktop/`](desktop/README.md). It uses the same API and token as the website and adds AI writing tools.

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
* **Port:** it is bound to `127.0.0.1` only. To expose it, put a reverse proxy with HTTPS in front, or use `compose.vps.yaml` (Caddy) as described in [deploy/VPS.md](deploy/VPS.md). Spring honours `X-Forwarded-*` headers, so the session cookie is marked `Secure` behind HTTPS.
* **Updates:** `git pull` in both checkouts, then `docker compose up -d --build`. Flyway migrates the database on start.
* **Tests:** the image build skips them (`-DskipTests`); run `mvn test` or CI before deploying.

### Configuration files

Built-in defaults live in `src/main/resources/gramalyzer-defaults.properties`, and every value there can be set through an environment variable. For your own settings, create `src/main/resources/application.yml`; it is git-ignored and overrides any default.

It is also left out of Docker images, so containers are configured only through `.env` and environment variables. Precedence, lowest first:

1. defaults
2. your `application.yml`, then `application-demo.yml` when the demo profile is active
3. environment variables
4. command-line arguments

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
| `TRACKING_REFRESH_ENABLED` | – | `true` (default): refresh watched public channels in the background. Switch it off where `t.me` can't be reached. |
| `TRACKING_REFRESH_EVERY` | – | How often each watched public channel is fetched again (default `PT2H`). Every visit adds that day to its subscriber history. |
| `TRACKING_MAX_PER_PERSON` / `TRACKING_MAX_WEB` | – | Watchlist sizes: per person in the bot (default 20; only while paid plans are off) and for the website (default 100) |
| `BILLING_ENABLED` | – | Paid plans in the bot (see below). Off by default for local runs and the demo; `compose.yaml` turns it on |
| `BILLING_CREATOR_STARS` / `BILLING_PRO_STARS` | – | Monthly prices in Telegram Stars (defaults 250 and 750). New subscriptions use the new price; running ones keep theirs |

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
| GET | `/api/channels/{id}/insights` | the analysis for one channel (`ChannelInsights` in `shared/types.ts`) |
| **GET** | **`/api/channels/{id}/feed?tz=`** | **new:** the channel's messages for the viewer (`ChannelFeed`). `source` says where they came from: `preview` (the public web preview, with views and reactions), `bot` (what the bot collected) or `workspace` (the website's own posts). Scheduled posts are always included. |
| GET | `/api/channels/{id}/photo` | the channel's Telegram profile photo (JPEG), fetched by the bot so its token stays on the server. 404 when the channel has none or the bot hasn't seen it. Cached for an hour, with an `ETag`. |
| **GET** | **`/api/tracked?tz=`** | **new:** the website's watchlist (`TrackedSummary[]`) |
| **POST** | **`/api/tracked`** | **new:** `{"username": "@durov"}` (also `t.me/…` links) adds a public channel and returns it (`TrackedDetail`, 201) |
| **GET** | **`/api/tracked/{username}?tz=`** | **new:** any public channel, watched or not: info, stats, top posts and the latest ~60 posts (`TrackedDetail`) |
| **POST** | **`/api/tracked/{username}/refresh`** | **new:** fetch it again now |
| **DELETE** | **`/api/tracked/{username}`** | **new:** remove it from the watchlist (204) |
| **GET** | **`/api/channels/{id}/growth?days=30`** | **new:** joins and leaves of an own channel per day, the posts that brought or lost the most people, and promo links (`ChannelGrowth`) |
| **POST** | **`/api/channels/{id}/promo-links`** | **new:** `{"name": "Shoutout in @friend", "cost": 20, "currency": "$"}` makes an invite link for one promo (needs the bot's "invite users via link" right) |
| **DELETE** | **`/api/channels/{id}/promo-links/{linkId}`** | **new:** revoke a promo link; the people who joined through it stay counted |

`tz` is an IANA zone (`Europe/Kyiv`) for the posting-hours histogram; UTC by default. Tracking errors also carry a `code` (`invalid_username`, `private_link`, `not_found`, `not_channel`, `unreachable`, `limit`, `not_tracked`) that the website translates.

`status` is `ready`, `learning` or `not_connected`:

* `learning`: fewer than 20 usable posts. Only `counts`, `postsNeeded`, `cadence` and `audience` are filled.
* `not_connected`: the bot has no data for this channel yet.

When `ready`, the response also has:

* `bestTimes`, `experiments`, and a 7×24 `heatmap` (Monday first, in the channel's timezone)
* `suggestions`: the next real date-time of each best slot, ready to use as a publishing time
* `content`: lift by format, length, link / no link, hashtag topic, keyword and posts-per-day
* `topPosts` and `audience` (subscriber trend)

"Lift" is a multiplier of the channel's typical engagement.

## Joins, leaves and promo links

An admin bot hears about every subscriber who joins or leaves an own channel (the `chat_member` update, requested in `allowed_updates`). The `growth` package keeps those events, with each person stored only as a one-way hash per channel.

* **Per post.** Each post gets the joins and leaves from its time until the next post (at most 24 h). The bot's 📈 Growth screen, the weekly digest and the website show the posts that brought the most people and those after which people left. Joins through promo links don't count towards posts.
* **Promo links.** `/promo` (or 🔗 on the Growth screen) makes a named invite link per ad or shoutout, e.g. `Shoutout in @techdaily 20$`. Telegram reports the link with each join, so the bot counts who joined through it, who stayed and the cost per subscriber who stayed.
* **Leave waves.** When at least 5 people (and 0.5% of the channel) leave within an hour and far more leave than join, team members with nudges on get one alert, at most every 12 hours.
* Telegram sends nothing from before the bot asked for these updates, so counting starts on deploy. Sample channels get a month of generated joins, leaves and three promo links.

## Public channel tracking

Anyone can keep an eye on a public channel without being its admin and without adding the bot: on the website (Channel viewer, Watchlist) or in the bot (`/track @username`, `/watchlist`, the 🔭 button, or just pasting a `t.me` link).

* **Where the numbers come from.** Telegram's public web preview (`t.me/s/<username>`) shows anyone a channel's title, description, subscriber count, media counters and its last ~20 posts with view counts and reactions. The first lookup also reads one older page. When the bot is running, `getChat` and `getChatMemberCount` fill in the exact subscriber count. Channels that hide their preview still show their size; posts and views stay empty.
* **History builds up over time.** Each refresh (every 2 hours while somebody watches the channel) updates the posts still on the preview and stores that day's subscribers and average views, so growth charts start from the day a channel is first watched.
* **Shared fetches.** A channel watched from the website and by several people in the bot is fetched once. Channels somebody only looked at are forgotten after 3 days.
* **What's computed:** average views of posts older than 12 h, ER (views ÷ subscribers), reactions per 100 views, posts per day, format mix, hashtags, busiest hours and weekdays, top posts, and 7/30-day subscriber change.
* In the demo profile, three made-up channels are on the website's watchlist so the viewer works without reaching Telegram.

## Paid plans (Telegram Stars)

With `BILLING_ENABLED=true` the bot sells monthly plans. Telegram requires Stars for digital goods inside bots, and no payment provider or BotFather setup is needed.

| Plan | Price | Own channels | Watchlist |
|---|---|---|---|
| Free | – | 1 | 3 |
| Creator | 250 ⭐ / 30 days | 3 | 10 |
| Pro | 750 ⭐ / 30 days | 10 | 25 |

* **Buying.** `/plan` (also the 💎 button under *Channels*) shows the plan, usage and offers. A button creates a Stars subscription invoice (`createInvoiceLink` with a 30-day `subscription_period`); Telegram renews it by itself and sends another successful payment each month. Cancel and resume are buttons on `/plan` (`editUserStarSubscription`). Buying another plan stops the old subscription.
* **What a plan limits.** Channels a person *owns* (demo channels don't count) and their own watchlist. Moderators and admins invited to a channel use its owner's plan. If a plan ends with more channels connected than it covers, the oldest ones keep working; the rest show a "paused" notice instead of insights and get no digests or nudges, while posts are still recorded. Bot admins have no limits.
* **Records.** Every payment is stored in `payments` with Telegram's charge id. A plan runs until `users.plan_until`, plus a day's grace for a late renewal unless renewal was cancelled; nothing has to run on a schedule.
* **Support and refunds.** `/paysupport` (Telegram requires it) lists the person's recent payments; `/paysupport <message>` forwards the message and those payments to the bot admins. Admins refund with `/refund <charge id>`; a refund of the current period ends the plan and stops its renewal. `/admin` shows payments and Stars from the last 30 days.

## Languages

The bot picks the language from the person's choice (🌐 Language, `/language`), then from their Telegram app's language, then English. Texts live in `src/main/resources/i18n/bot_<code>.properties`: Telegram HTML with `{0}` placeholders, plus plural forms per language (`.one`, `.few`, `.many`…). `I18nTest` checks that every language has every key with the same placeholders and HTML. The command menu is published in each language. To add a language, add it to `i18n.Lang` and copy `bot_en.properties`.

## What the analysis can and can't see

* **Telegram does not give bots view counts.** "Engagement" therefore means reactions + 3 × comments (switchable to reactions or comments only in the bot). Comments need the discussion group linked and the bot in it.
* **Bots can't read history.** Collection starts when the bot is added. Owners can back-fill with a Telegram Desktop export (`/import` in the bot, JSON, ≤ 20 MB).
* Posts younger than 24 h are ignored until their reactions settle; albums count once. Results are shrunk toward "typical" when samples are small, and every answer carries a sample size and confidence.

## Layout

```
workspace/   CRM REST API, queue, delivery worker, demo workspace       (was here before)
crm/         bridge between the two + the insights and feed endpoints
tracking/    public channel watchlists: preview parser and client, stats, REST API, refresh jobs
billing/     paid plans: limits, Stars payments and refunds
i18n/        the bot's languages (Lang, Tr, plural rules); texts in resources/i18n
analytics/   pure Java: engine, report, text features, scheduling rules (unit-tested)
bot/         TelegramBot, BotRegistration, UpdateMapper/Router, BotApi (all Telegram calls)
bot/ui, bot/screen, bot/flow   the bot's screens and conversations
channel/ post/ user/ settings/ scheduler/   entities, services, background jobs
```

Telegram library types appear only in `BotApi`, `UpdateMapper`, `TelegramBot`, `BotRegistration`, `CrmBridge.observe` and `workspace/LiveTelegramGateway`.

## Notes

* **The original source archive contained a hard-coded bot token. Revoke it in @BotFather** and use a new one via `TELEGRAM_BOT_TOKEN`. The old token remains in the archive and git history.
* Migration `V4__tracking.sql` adds the tracking tables and `users.language`.
* Migration `V3__channel_analytics.sql` adds the analytics tables and extends `users`. It is written to run on both PostgreSQL and H2 (one `ADD COLUMN` per statement; no `TEXT` columns, which H2 maps to CLOB).
* The sample `/random` command and its "Get random number" keyboard were removed; `/start` now opens the analytics bot's welcome screen.
* Lombok is declared as an annotation processor (needed on JDK 23+). After pulling, run `mvn clean test`.
