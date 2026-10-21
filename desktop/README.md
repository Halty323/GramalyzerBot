# Gramalyzer Studio

A native desktop app for Windows, macOS and Linux. It does everything the website does, and adds tools for writing posts and AI built into each step. It is written in Rust with [iced](https://iced.rs).

Studio is a **client of the Gramalyzer backend** in this repository. It talks to the same `/api` with the same `GRAMALYZER_API_TOKEN` as the website. Your channels, posts and analytics stay on your server. The AI features call an AI provider directly from your computer.

| Page | What it is for |
|---|---|
| **Dashboard** | Subscribers, views, engagement and the queue. It shows the channel's audience curve and its best next posting slot. |
| **Studio** | Write posts in three panes. The **library** has filters and search. The **editor** checks the 4096-character limit (signature included) and shows the word count and reading time. You can schedule a post, jump +1h or +1d, or pick the best slot. The **AI copilot** can draft from a brief, improve, give a stronger hook, shorten, expand, fix grammar, add hashtags, suggest titles, change the tone or translate. A live Telegram-style preview sits alongside. |
| **Planner** | The week as a calendar, with the bot's best slots marked in your timezone. The **AI planner** writes a batch of complete posts from what works on the channel and places them in the next best slots. You pick which ones to keep, and they are saved as drafts. |
| **Insights** | Best times to post, a weekly heatmap, which formats, lengths, hashtags and links work, and the strongest posts. **AI review** explains the numbers. **Write more like these** sends you to the assistant. |
| **AI assistant** | A chat that knows the channel's analytics, queue and watchlist. It can give you ideas, a series, a 7-day plan or a strategy. Any answer can be opened in the Studio. |
| **Channel viewer** | The feed and stats of any of your channels, or of any public `@username`. **AI breakdown** looks at what that channel does well. |
| **Watchlist** | Track public channels, sort them by growth, ER or posting rate, see sparklines, and **compare with AI**. |
| **Channels / Settings** | Pause or resume a channel, its timezone and signature. Also the server connection, the AI provider, your brand voice and the language for AI drafts. |

The interface is translated into all 12 of the bot's languages: en, ru, uk, hi, id, pt, es, ar, fa, uz, tr and vi. Arabic and Persian are laid out right to left. It has light and dark themes. Shortcuts: **Ctrl+N** new post, **Ctrl+S** save, **Ctrl+R** refresh.

## Run it

You need a recent stable Rust (`rustup update`; the crate uses edition 2024) and a running backend. For a quick look, start the demo backend from the repository root:

```sh
mvn spring-boot:run -Dspring-boot.run.profiles=demo     # sample data, Telegram off, no token
```

Then:

```sh
cd desktop
cargo run --release
```

On first start, Studio asks for the server address and the access token. That is the backend's `GRAMALYZER_API_TOKEN`; leave it empty for the demo profile. You can also pass both in the environment:

```sh
GRAMALYZER_SERVER=https://gramalyzer.example.com GRAMALYZER_API_TOKEN=… cargo run --release
```

Settings are saved as JSON in the user's config folder, readable only by that user on Unix:

* Linux: `~/.config/gramalyzerstudio/`
* macOS: `~/Library/Application Support/dev.Gramalyzer.Gramalyzer-Studio/`
* Windows: `%APPDATA%\Gramalyzer\Gramalyzer Studio\config\`

The `GRAMALYZER_STUDIO_CONFIG` variable points it at another file.

### Building for each platform

```sh
cargo build --release          # target/release/gramalyzer-desktop(.exe)
```

* **Linux** needs the usual windowing libraries: `libxkbcommon-x11-0`, `libwayland-client0` and `libvulkan1` or Mesa. On Debian/Ubuntu: `sudo apt install libxkbcommon-x11-0 libwayland-client0 mesa-vulkan-drivers`. Without a GPU, iced falls back to a software renderer.
* **Windows** builds natively with MSVC. To cross-compile from Linux, run `rustup target add x86_64-pc-windows-gnu`, install `gcc-mingw-w64-x86-64`, then `cargo build --release --target x86_64-pc-windows-gnu`. Release builds open without a console window.
* **macOS**: `cargo build --release` on a Mac. To make an `.app` bundle you can use a tool such as `cargo-bundle`.

Run the tests with `cargo test`. They check that every language has every string, the same placeholders and the plural forms its grammar needs. They also check the AI plan parser and the prompts.

## AI providers

Studio works with any **OpenAI-compatible** API (`POST /chat/completions` with streaming, and `GET /models`). Pick a preset under **Settings → AI provider**, paste a key and press **Test**. Requests go straight from your computer to the provider. The key is stored only in the local config file.

These free tiers work with Studio. They were checked in October 2026 and change often.

| Provider | Free tier | Default model | Get a key |
|---|---|---|---|
| **Groq** (default) | No card. 30 requests/min, 1,000/day, 8K tokens/min, 200K tokens/day. Very fast. | `openai/gpt-oss-120b` | [console.groq.com/keys](https://console.groq.com/keys) |
| **Google Gemini** | Free key with a Google account. Each model has its own daily quota. Outside the EU/UK, free prompts may be used to improve Google's models. | `gemini-flash-latest` | [aistudio.google.com/apikey](https://aistudio.google.com/apikey) |
| **OpenRouter** | Models ending in `:free` cost nothing. 20 requests/min and 50/day, or 1,000/day after a one-time $10 top-up. | `meta-llama/llama-3.3-70b-instruct:free` | [openrouter.ai/keys](https://openrouter.ai/keys) |
| **Cerebras** | Free trial, no card. 5 requests/min, 1M tokens/day. | `gpt-oss-120b` | [cloud.cerebras.ai](https://cloud.cerebras.ai/) |
| **Mistral** | Free "Experiment" plan with phone verification. Prompts may be used for training. | `mistral-small-latest` | [console.mistral.ai](https://console.mistral.ai/api-keys) |
| **GitHub Models** | Free with a GitHub account, using a token with `models:read`. About 15 requests/min and 150/day on most models. | `openai/gpt-4.1-mini` | [github.com/settings/personal-access-tokens](https://github.com/settings/personal-access-tokens) |
| **Ollama** | Runs models on your own machine: free, private, no limits. Needs a decent CPU or GPU. | `llama3.2` | [ollama.com/download](https://ollama.com/download) |
| **Custom** | Any other compatible endpoint, such as OpenAI, DeepSeek, Together or LM Studio. | — | — |

Sources: the providers' own rate-limit pages ([Groq](https://console.groq.com/docs/rate-limits), [Gemini](https://ai.google.dev/gemini-api/docs/rate-limits), [OpenRouter](https://openrouter.ai/docs/api-reference/limits), [Cerebras](https://inference-docs.cerebras.ai/support/rate-limits), [GitHub Models](https://docs.github.com/en/github-models/use-github-models/prototyping-with-ai-models#rate-limits)).

Free tiers can log prompts, so don't send anything confidential. When one provider hits its limit, switch to another in Settings.

Every request carries the channel's context, so posts stay on the channel's subject and sound like it. The model sees the channel's name and description, six of its own recent posts in full (its most engaging first, then the newest), its house style in numbers (typical length, emoji, links, the hashtags it uses), what is already drafted or scheduled, its analytics (best hours, which formats, lengths and hashtags work) and your brand voice. Drafts are written in the language of the channel's posts unless you pick one under **Settings → Writing**. Prompts tell the model that Telegram posts are plain text (no Markdown) with a 4096-character limit. The prompts are in `src/prompts.rs`.

## Why iced

Studio is drawn by a native Rust toolkit, not a web view. iced shapes text through cosmic-text. That gives correct Arabic and Persian joining, right-to-left text and Devanagari conjuncts, which the website's languages need and which immediate-mode toolkits such as egui still handle poorly. iced renders on the GPU with wgpu and falls back to a software renderer. It follows the Elm architecture (state, messages, update and view), which keeps a 9-page app manageable. The app is a single executable of about 25 MB, with its fonts built in: Inter, Noto Sans Arabic, Noto Sans Devanagari and the Lucide icons.

## Layout

```
src/main.rs        window, fonts, entry point
src/app.rs         state, messages and update: everything the app does
src/view.rs        shell: sidebar, top bar, login, toasts
src/pages/         one module per page
src/api.rs         backend client (/api, Bearer token)
src/ai.rs          OpenAI-compatible client: streaming SSE, model list, provider presets
src/prompts.rs     prompt builders with the channel's analytics as context
src/charts.rs      canvas charts: audience line, weekly heatmap, sparklines
src/theme.rs       light/dark design tokens and widget styles
src/i18n.rs        12 languages, CLDR plural rules, number and date formats
locales/*.json     translations
assets/fonts/      bundled fonts (SIL OFL, ISC)
```

Studio lives in `desktop/` inside the backend repository so the API and its client change together. It has no build dependency on the Java code, so it can move to its own repository whenever that becomes more convenient.
