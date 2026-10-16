package com.halty.gramalyzerbot.bot.flow;

import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.bot.event.Events;
import com.halty.gramalyzerbot.bot.screen.*;
import com.halty.gramalyzerbot.bot.ui.*;
import com.halty.gramalyzerbot.channel.*;
import com.halty.gramalyzerbot.common.UserFacingException;
import com.halty.gramalyzerbot.post.DemoService;
import com.halty.gramalyzerbot.post.ImportService;
import com.halty.gramalyzerbot.post.PostService;
import com.halty.gramalyzerbot.settings.BotSettings;
import com.halty.gramalyzerbot.user.User;
import com.halty.gramalyzerbot.user.UserService;
import com.halty.gramalyzerbot.user.UserState;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/** Everything people type, tap on the bottom keyboard, forward or upload in a private chat with the bot. */
@Component
@RequiredArgsConstructor
@Slf4j
public class PrivateFlow {

    private static final Duration PROMPT_TTL = Duration.ofMinutes(15);
    private static final long MAX_IMPORT_BYTES = 20L * 1024 * 1024;
    private static final Pattern HANDLE = Pattern.compile("^@?([A-Za-z][A-Za-z0-9_]{3,31})$");

    private final Sessions sessions;
    private final UserService users;
    private final Access access;
    private final ChannelService channels;
    private final MembershipService membership;
    private final SettingsService settingsService;
    private final ChannelConnector connector;
    private final StartScreens start;
    private final HomeScreen home;
    private final InsightScreens insights;
    private final SettingsScreens settingsScreens;
    private final TeamScreens team;
    private final AdminScreens admin;
    private final DemoService demo;
    private final ImportService importer;
    private final PostService posts;
    private final AuditService audit;
    private final BotSettings botSettings;
    private final BotApi gateway;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "import-worker");
        t.setDaemon(true);
        return t;
    });
    private final String claimCode = randomCode();

    @PostConstruct
    void announceClaimCode() {
        if (gateway.enabled() && !users.anyBotAdmin())
            log.warn("No bot admin is configured. To become the admin, send this to the bot in a private chat:  /claim {}", claimCode);
    }

    // ------------------------------------------------------------------ entry point

    public void handle(Events.PrivateMessage m) {
        Optional<User> opt = sessions.open(m.userId(), m.username(), m.firstName(), m.lang(), m.chatId());
        if (opt.isEmpty()) return;
        User u = opt.get();
        try {
            dispatch(u, m);
        } catch (UserFacingException e) {
            gateway.send(m.chatId(), View.of(e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Failed to handle a private message from {}", m.userId(), e);
            gateway.send(m.chatId(), View.of("😵 Something went wrong on my side. Please try again in a moment."));
        }
    }

    private void dispatch(User u, Events.PrivateMessage m) {
        String text = m.text() == null ? "" : m.text().trim();
        long chat = m.chatId();
        if (text.startsWith("/")) {
            command(u, m, text);
            return;
        }
        switch (text) {
            case Menu.OVERVIEW -> { users.clearState(u); showHome(u, chat); return; }
            case Menu.TIMES -> { users.clearState(u); withChannel(u, chat, (ch, role) -> gateway.send(chat, insights.times(ch, ch.getWindowDays(), "main"))); return; }
            case Menu.CONTENT -> { users.clearState(u); withChannel(u, chat, (ch, role) -> gateway.send(chat, insights.content(ch, ch.getWindowDays(), "main"))); return; }
            case Menu.SETTINGS -> { users.clearState(u); showSettings(u, chat); return; }
            default -> { }
        }
        if (m.document() != null) {
            document(u, m);
            return;
        }
        if (m.forward() != null) {
            sessions.requireMayCreate(u);
            connectChannel(u, chat, String.valueOf(m.forward().chatId()));
            return;
        }
        Optional<UserService.Pending> pending = users.pending(u);
        if (pending.isPresent()) {
            stateInput(u, m, pending.get(), text);
            return;
        }
        if (HANDLE.matcher(text).matches()) {
            sessions.requireMayCreate(u);
            connectChannel(u, chat, "@" + text.replaceFirst("^@", ""));
            return;
        }
        gateway.send(chat, View.of("🤔 I didn't catch that. Use the menu below, or /help to see what I can do."));
    }

    // ------------------------------------------------------------------ commands

    private void command(User u, Events.PrivateMessage m, String text) {
        String[] parts = text.split("\\s+", 2);
        String cmd = parts[0].substring(1).toLowerCase(Locale.ROOT);
        int at = cmd.indexOf('@');
        if (at >= 0) cmd = cmd.substring(0, at);
        String arg = parts.length > 1 ? parts[1].trim() : "";
        long chat = m.chatId();

        switch (cmd) {
            case "start" -> start(u, chat, arg);
            case "menu" -> {
                users.clearState(u);
                gateway.sendWithKeyboard(chat, "📱 Menu ready – use the buttons below.", Menu.KEYBOARD);
                showHome(u, chat);
            }
            case "overview", "dashboard" -> { users.clearState(u); showHome(u, chat); }
            case "times" -> withChannel(u, chat, (ch, role) -> gateway.send(chat, insights.times(ch, ch.getWindowDays(), "main")));
            case "content" -> withChannel(u, chat, (ch, role) -> gateway.send(chat, insights.content(ch, ch.getWindowDays(), "main")));
            case "top" -> withChannel(u, chat, (ch, role) -> gateway.send(chat, insights.top(ch, role, 0)));
            case "settings" -> showSettings(u, chat);
            case "team" -> withChannel(u, chat, (ch, role) -> gateway.send(chat, team.home(ch, u, role)));
            case "channels" -> gateway.send(chat, start.channels(channels.forUser(u), u.getActiveChannelId(), u.isBotAdmin()));
            case "connect" -> {
                sessions.requireMayCreate(u);
                users.setState(u, UserState.AWAIT_CONNECT, "", PROMPT_TTL);
                gateway.send(chat, start.connect());
            }
            case "import" -> withChannel(u, chat, (ch, role) -> {
                membership.require(ch, u, Permission.IMPORT_DATA);
                users.setState(u, UserState.AWAIT_IMPORT, String.valueOf(ch.getId()), Duration.ofMinutes(30));
                gateway.send(chat, settingsScreens.importPrompt(ch));
            });
            case "demo" -> {
                sessions.requireMayCreate(u);
                openDemo(u, chat);
            }
            case "help" -> gateway.send(chat, start.help(access.active(u).isPresent()));
            case "cancel" -> {
                users.clearState(u);
                gateway.send(chat, View.of("👌 Cancelled."));
            }
            case "admin" -> {
                if (!u.isBotAdmin()) gateway.send(chat, View.of("🤔 I don't know that command. Try /help."));
                else gateway.send(chat, admin.home());
            }
            case "claim" -> claim(u, chat, arg);
            default -> gateway.send(chat, View.of("🤔 I don't know that command. Try /help."));
        }
    }

    private void start(User u, long chat, String payload) {
        if (payload.startsWith("join_")) {
            joinViaInvite(u, chat, payload.substring("join_".length()));
            return;
        }
        users.clearState(u);
        if (access.active(u).isPresent()) {
            gateway.sendWithKeyboard(chat, "👋 Welcome back, <b>" + Fmt.esc(u.displayName()) + "</b>!", Menu.KEYBOARD);
            showHome(u, chat);
            return;
        }
        sendWelcomePhoto(u, chat);
        gateway.send(chat, start.welcome());
    }

    private void sendWelcomePhoto(User u, long chat) {
        String caption = start.welcomeCaption(u);
        String cached = botSettings.get(BotSettings.WELCOME_PHOTO).orElse(null);
        Optional<String> fileId = gateway.sendPhoto(chat, cached, PrivateFlow::welcomeImage, "welcome.jpg", caption, Menu.KEYBOARD);
        if (fileId.isPresent()) {
            if (!fileId.get().equals(cached)) botSettings.set(BotSettings.WELCOME_PHOTO, fileId.get());
        } else {
            gateway.sendWithKeyboard(chat, caption, Menu.KEYBOARD);
        }
    }

    private static InputStream welcomeImage() {
        try {
            return new ClassPathResource("startimage1.jpg").getInputStream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void claim(User u, long chat, String code) {
        if (users.anyBotAdmin() || !claimCode.equals(code)) {
            gateway.send(chat, View.of("🤔 I don't know that command. Try /help."));
            return;
        }
        users.makeBotAdmin(u);
        gateway.send(chat, View.of("🛠 <b>You're now the bot admin.</b> Open /admin any time."));
    }

    private void joinViaInvite(User u, long chat, String token) {
        MembershipService.Joined j = membership.accept(token, u);
        users.setActiveChannel(u, j.channel().getId());
        gateway.sendWithKeyboard(chat, j.alreadyMember()
                ? "👋 You're already on the team for <b>" + Fmt.esc(j.channel().getTitle()) + "</b>."
                : "🎉 <b>You joined " + Fmt.esc(j.channel().getTitle()) + "</b> as " + j.role().badge() + ".", Menu.KEYBOARD);
        gateway.send(chat, home.render(j.channel(), j.role()));
    }

    // ------------------------------------------------------------------ screens

    private void showHome(User u, long chat) {
        withChannel(u, chat, (ch, role) -> {
            gateway.typing(chat);
            gateway.send(chat, home.render(ch, role));
        });
    }

    private void showSettings(User u, long chat) {
        withChannel(u, chat, (ch, role) -> {
            if (role.can(Permission.CHANGE_SETTINGS)) {
                gateway.send(chat, settingsScreens.home(ch, role));
            } else {
                gateway.send(chat, View.of("🔒 Channel settings are for admins. Here are your own notification settings:"));
                membership.membership(ch, u).ifPresent(m -> gateway.send(chat, settingsScreens.notifications(ch, m)));
            }
        });
    }

    private void openDemo(User u, long chat) {
        gateway.typing(chat);
        Channel ch = demo.createFor(u);
        users.setActiveChannel(u, ch.getId());
        gateway.send(chat, View.of("🎮 <b>Sample channel ready.</b> It's fake data with realistic patterns – explore freely."));
        gateway.send(chat, home.render(ch, Role.OWNER));
    }

    private void withChannel(User u, long chat, BiConsumer<Channel, Role> action) {
        Optional<Channel> ch = access.active(u);
        if (ch.isEmpty()) {
            View v = View.of("📭 You haven't connected a channel yet.");
            v.rows(start.welcome().rows());
            gateway.send(chat, v);
            return;
        }
        action.accept(ch.get(), access.roleOf(u, ch.get()));
    }

    // ------------------------------------------------------------------ typed answers to prompts

    private void stateInput(User u, Events.PrivateMessage m, UserService.Pending p, String text) {
        long chat = m.chatId();
        switch (p.state()) {
            case UserState.AWAIT_TIMEZONE -> timezoneInput(u, chat, Long.parseLong(p.arg()), text);
            case UserState.AWAIT_CONNECT -> {
                java.util.regex.Matcher hm = HANDLE.matcher(text);
                if (hm.matches()) connectChannel(u, chat, "@" + hm.group(1));
                else gateway.send(chat, View.of("Send the channel's <code>@username</code>, or forward me any post from it. /cancel to stop."));
            }
            case UserState.AWAIT_TOPIC -> topicInput(u, chat, p.arg(), text);
            case UserState.AWAIT_BAN -> banInput(u, chat, text);
            case UserState.AWAIT_IMPORT -> gateway.send(chat, View.of("📎 Please send the <code>result.json</code> file as a document. /cancel to stop."));
            default -> {
                users.clearState(u);
                gateway.send(chat, View.of("🤔 I didn't catch that. Use the menu below, or /help."));
            }
        }
    }

    private void timezoneInput(User u, long chat, long channelId, String text) {
        Channel ch = access.require(u, channelId, Permission.CHANGE_SETTINGS);
        List<ZoneId> zones = TimeZones.resolve(text);
        if (zones.isEmpty()) {
            gateway.send(chat, View.of("🤷 I couldn't find <b>" + Fmt.esc(Fmt.trunc(text, 40)) + "</b>. Try a big city nearby (<code>Berlin</code>) or an offset like <code>UTC+2</code>."));
            return;
        }
        if (zones.size() > 1) {
            gateway.send(chat, settingsScreens.timezoneChoices(ch, zones));
            return;
        }
        Channel saved = settingsService.setTimezone(ch, u, zones.get(0));
        users.clearState(u);
        gateway.send(chat, View.of("✅ Timezone set to <b>" + Fmt.esc(TimeZones.label(zones.get(0), java.time.Instant.now())) + "</b>."));
        gateway.send(chat, settingsScreens.home(saved, access.roleOf(u, saved)));
    }

    private void topicInput(User u, long chat, String arg, String text) {
        String[] ids = arg.split(":");
        Channel ch = access.require(u, Long.parseLong(ids[0]), Permission.MODERATE_POSTS);
        boolean clear = text.equals("-");
        posts.setTopic(ch, Long.parseLong(ids[1]), clear ? "" : text);
        audit.record(ch.getId(), u.getId(), "post.tag", clear ? "Cleared a post's topic" : "Tagged a post #" + Fmt.trunc(text, 40));
        users.clearState(u);
        gateway.send(chat, View.of(clear ? "✅ Topic cleared." : "✅ Topic saved."));
        gateway.send(chat, insights.top(ch, access.roleOf(u, ch), 0));
    }

    private void banInput(User u, long chat, String text) {
        if (!u.isBotAdmin()) {
            users.clearState(u);
            return;
        }
        long id;
        try {
            id = Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            gateway.send(chat, View.of("That doesn't look like a numeric Telegram ID. Try again or /cancel."));
            return;
        }
        Optional<User> existing = users.findByTelegramId(id);
        if (existing.isPresent() && existing.get().isBotAdmin()) {
            users.clearState(u);
            gateway.send(chat, View.of("Bot admins can't be banned."));
            return;
        }
        Optional<User> target = users.setBanned(id, true);
        users.clearState(u);
        gateway.send(chat, target.map(admin::user).orElse(View.of("I don't know anyone with that ID.")));
    }

    // ------------------------------------------------------------------ connecting a channel by name or forward

    private void connectChannel(User u, long chat, String ref) {
        gateway.typing(chat);
        Optional<BotApi.ChatInfo> info = ref.startsWith("@") ? gateway.chat(ref) : gateway.chat(Long.parseLong(ref));
        if (info.isEmpty()) {
            gateway.send(chat, View.of("🤷 I can't find that channel. Check the spelling, and make sure I've been added as an admin."));
            return;
        }
        BotApi.ChatInfo c = info.get();
        if (!"channel".equals(c.type())) {
            gateway.send(chat, View.of("That's not a channel (it's a " + Fmt.esc(c.type()) + "). I analyse channels."));
            return;
        }
        Optional<String> botStatus = gateway.memberStatus(c.id(), gateway.botId());
        if (botStatus.isEmpty() || !"administrator".equals(botStatus.get())) {
            View v = View.of("⚠️ I'm not an admin of <b>" + Fmt.esc(c.title()) + "</b> yet. Add me first, then try again.");
            v.row(Btn.link("➕ Add me to a channel", "https://t.me/" + gateway.botUsername() + "?startchannel=true"));
            gateway.send(chat, v);
            return;
        }
        Optional<String> userStatus = gateway.memberStatus(c.id(), u.getTelegramId());
        if (userStatus.isEmpty() || !(userStatus.get().equals("creator") || userStatus.get().equals("administrator"))) {
            gateway.send(chat, View.of("🔒 Only admins of <b>" + Fmt.esc(c.title()) + "</b> can connect it."));
            return;
        }
        connector.connect(u, c.id(), c.title(), c.username());
    }

    // ------------------------------------------------------------------ history import

    private void document(User u, Events.PrivateMessage m) {
        long chat = m.chatId();
        Optional<UserService.Pending> p = users.pending(u);
        if (p.isEmpty() || !UserState.AWAIT_IMPORT.equals(p.get().state())) {
            gateway.send(chat, View.of("📎 To import history, open /import first so I know which channel the file belongs to."));
            return;
        }
        Channel ch = access.require(u, Long.parseLong(p.get().arg()), Permission.IMPORT_DATA);
        Events.DocumentInfo doc = m.document();
        if (doc.size() > MAX_IMPORT_BYTES) {
            gateway.send(chat, View.of("📦 That file is over 20 MB, which is the most Telegram lets bots download. Export a shorter date range and try again."));
            return;
        }
        if (doc.name() != null && !doc.name().toLowerCase(Locale.ROOT).endsWith(".json")) {
            gateway.send(chat, View.of("That doesn't look like JSON. In Telegram Desktop choose format <b>JSON</b> and send <code>result.json</code>."));
            return;
        }
        users.clearState(u);
        gateway.send(chat, View.of("⏳ Importing… I'll message you when it's done."));
        worker.submit(() -> runImport(u, chat, ch, doc));
    }

    private void runImport(User u, long chat, Channel ch, Events.DocumentInfo doc) {
        try (InputStream in = gateway.download(doc.fileId())) {
            ImportService.Result r = importer.importExport(ch, u, in);
            gateway.send(chat, View.of("✅ <b>Imported " + Fmt.num(r.total()) + " posts</b> (" + Fmt.num(r.created()) + " new, " + Fmt.num(r.updated()) + " merged with ones I already had).")
                    .line("Posts younger than a day are skipped in the analysis until their reactions settle.")
                    .row(Btn.cb("📊 Open dashboard", Cb.of("nav:home", ch.getId()))));
        } catch (UserFacingException e) {
            gateway.send(chat, View.of("⚠️ " + e.getMessage()));
        } catch (IOException e) {
            log.warn("Import failed", e);
            gateway.send(chat, View.of("⚠️ I couldn't read that file: " + Fmt.esc(String.valueOf(e.getMessage()))));
        } catch (RuntimeException e) {
            log.error("Import crashed", e);
            gateway.send(chat, View.of("😵 The import failed unexpectedly. Nothing was lost – please try again."));
        }
    }

    private static String randomCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return sb.toString();
    }
}
