package com.halty.gramalyzerbot.bot.flow;

import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.bot.event.Events;
import com.halty.gramalyzerbot.bot.screen.*;
import com.halty.gramalyzerbot.bot.ui.*;
import com.halty.gramalyzerbot.channel.*;
import com.halty.gramalyzerbot.common.UserFacingException;
import com.halty.gramalyzerbot.i18n.Lang;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.post.DemoService;
import com.halty.gramalyzerbot.post.ImportService;
import com.halty.gramalyzerbot.post.PostService;
import com.halty.gramalyzerbot.settings.BotSettings;
import com.halty.gramalyzerbot.tracking.TrackingException;
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
    private final WatchFlow watch;
    private final BillingFlow billing;
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
        } catch (TrackingException e) {
            gateway.send(m.chatId(), View.of("🤷 " + WatchScreens.error(e)));
        } catch (RuntimeException e) {
            log.error("Failed to handle a private message from {}", m.userId(), e);
            gateway.send(m.chatId(), View.of(Tr.t("flow.error")));
        }
    }

    private void dispatch(User u, Events.PrivateMessage m) {
        String text = m.text() == null ? "" : m.text().trim();
        long chat = m.chatId();
        if (text.startsWith("/")) {
            command(u, m, text);
            return;
        }
        Optional<Menu.Action> button = Menu.match(text);
        if (button.isPresent()) {
            users.clearState(u);
            switch (button.get()) {
                case OVERVIEW -> showHome(u, chat);
                case TIMES -> withChannel(u, chat, (ch, role) -> gateway.send(chat, insights.times(ch, ch.getWindowDays(), "main")));
                case CONTENT -> withChannel(u, chat, (ch, role) -> gateway.send(chat, insights.content(ch, ch.getWindowDays(), "main")));
                case SETTINGS -> showSettings(u, chat);
                case WATCHLIST -> watch.showList(u, chat);
            }
            return;
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
        if (WatchFlow.looksLikeChannelLink(text)) {
            watch.track(u, chat, text);
            return;
        }
        gateway.send(chat, View.of(Tr.t("flow.didnt_catch")));
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
                gateway.sendWithKeyboard(chat, Tr.t("flow.menu_ready"), Menu.keyboard());
                showHome(u, chat);
            }
            case "overview", "dashboard" -> { users.clearState(u); showHome(u, chat); }
            case "times" -> withChannel(u, chat, (ch, role) -> gateway.send(chat, insights.times(ch, ch.getWindowDays(), "main")));
            case "content" -> withChannel(u, chat, (ch, role) -> gateway.send(chat, insights.content(ch, ch.getWindowDays(), "main")));
            case "top" -> withChannel(u, chat, (ch, role) -> gateway.send(chat, insights.top(ch, role, 0)));
            case "settings" -> showSettings(u, chat);
            case "team" -> withChannel(u, chat, (ch, role) -> gateway.send(chat, team.home(ch, u, role)));
            case "channels" -> gateway.send(chat, start.channels(channels.forUser(u), u.getActiveChannelId(), u.isBotAdmin()));
            case "watchlist", "watch", "tracked" -> { users.clearState(u); watch.showList(u, chat); }
            case "track" -> {
                if (arg.isBlank()) watch.prompt(u, chat);
                else watch.track(u, chat, arg);
            }
            case "untrack" -> watch.untrack(u, chat, arg);
            case "language", "lang" -> {
                users.clearState(u);
                gateway.send(chat, start.languages(Tr.lang()));
            }
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
            case "plan", "plans", "premium", "upgrade", "subscription" -> { users.clearState(u); billing.showHome(u, chat); }
            case "paysupport" -> billing.support(u, chat, arg);
            case "refund" -> {
                if (!u.isBotAdmin()) gateway.send(chat, View.of(Tr.t("flow.unknown_command")));
                else billing.refund(u, chat, arg);
            }
            case "help" -> gateway.send(chat, start.help(access.active(u).isPresent()));
            case "cancel" -> {
                users.clearState(u);
                gateway.send(chat, View.of(Tr.t("flow.cancelled")));
            }
            case "admin" -> {
                if (!u.isBotAdmin()) gateway.send(chat, View.of(Tr.t("flow.unknown_command")));
                else gateway.send(chat, admin.home());
            }
            case "claim" -> claim(u, chat, arg);
            default -> gateway.send(chat, View.of(Tr.t("flow.unknown_command")));
        }
    }

    private void start(User u, long chat, String payload) {
        if (payload.startsWith("join_")) {
            joinViaInvite(u, chat, payload.substring("join_".length()));
            return;
        }
        users.clearState(u);
        if (access.active(u).isPresent()) {
            gateway.sendWithKeyboard(chat, Tr.t("flow.welcome_back", Fmt.esc(u.displayName())), Menu.keyboard());
            showHome(u, chat);
            return;
        }
        sendWelcomePhoto(u, chat);
        gateway.send(chat, start.welcome());
    }

    private void sendWelcomePhoto(User u, long chat) {
        String caption = start.welcomeCaption(u);
        String cached = botSettings.get(BotSettings.WELCOME_PHOTO).orElse(null);
        Optional<String> fileId = gateway.sendPhoto(chat, cached, PrivateFlow::welcomeImage, "welcome.jpg", caption, Menu.keyboard());
        if (fileId.isPresent()) {
            if (!fileId.get().equals(cached)) botSettings.set(BotSettings.WELCOME_PHOTO, fileId.get());
        } else {
            gateway.sendWithKeyboard(chat, caption, Menu.keyboard());
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
            gateway.send(chat, View.of(Tr.t("flow.unknown_command")));
            return;
        }
        users.makeBotAdmin(u);
        gateway.send(chat, View.of(Tr.t("flow.claimed")));
    }

    private void joinViaInvite(User u, long chat, String token) {
        MembershipService.Joined j = membership.accept(token, u);
        users.setActiveChannel(u, j.channel().getId());
        gateway.sendWithKeyboard(chat, j.alreadyMember()
                ? Tr.t("flow.already_member", Fmt.esc(j.channel().getTitle()))
                : Tr.t("flow.joined", Fmt.esc(j.channel().getTitle()), Labels.badge(j.role())), Menu.keyboard());
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
                gateway.send(chat, View.of(Tr.t("flow.settings_admins")));
                membership.membership(ch, u).ifPresent(m -> gateway.send(chat, settingsScreens.notifications(ch, m)));
            }
        });
    }

    private void openDemo(User u, long chat) {
        gateway.typing(chat);
        Channel ch = demo.createFor(u);
        users.setActiveChannel(u, ch.getId());
        gateway.send(chat, View.of(Tr.t("flow.demo_ready")));
        gateway.send(chat, home.render(ch, Role.OWNER));
    }

    private void withChannel(User u, long chat, BiConsumer<Channel, Role> action) {
        Optional<Channel> ch = access.active(u);
        if (ch.isEmpty()) {
            View v = View.of(Tr.t("flow.no_channel"));
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
                else gateway.send(chat, View.of(Tr.t("flow.send_username")));
            }
            case UserState.AWAIT_TRACK -> watch.track(u, chat, text);
            case UserState.AWAIT_TOPIC -> topicInput(u, chat, p.arg(), text);
            case UserState.AWAIT_BAN -> banInput(u, chat, text);
            case UserState.AWAIT_IMPORT -> gateway.send(chat, View.of(Tr.t("flow.send_file")));
            default -> {
                users.clearState(u);
                gateway.send(chat, View.of(Tr.t("flow.didnt_catch_short")));
            }
        }
    }

    private void timezoneInput(User u, long chat, long channelId, String text) {
        Channel ch = access.require(u, channelId, Permission.CHANGE_SETTINGS);
        List<ZoneId> zones = TimeZones.resolve(text);
        if (zones.isEmpty()) {
            gateway.send(chat, View.of(Tr.t("flow.tz_unknown", Fmt.esc(Fmt.trunc(text, 40)))));
            return;
        }
        if (zones.size() > 1) {
            gateway.send(chat, settingsScreens.timezoneChoices(ch, zones));
            return;
        }
        Channel saved = settingsService.setTimezone(ch, u, zones.get(0));
        users.clearState(u);
        gateway.send(chat, View.of(Tr.t("flow.tz_set", Fmt.esc(TimeZones.label(zones.get(0), java.time.Instant.now())))));
        gateway.send(chat, settingsScreens.home(saved, access.roleOf(u, saved)));
    }

    private void topicInput(User u, long chat, String arg, String text) {
        String[] ids = arg.split(":");
        Channel ch = access.require(u, Long.parseLong(ids[0]), Permission.MODERATE_POSTS);
        boolean clear = text.equals("-");
        posts.setTopic(ch, Long.parseLong(ids[1]), clear ? "" : text);
        audit.record(ch.getId(), u.getId(), "post.tag", clear ? "Cleared a post's topic" : "Tagged a post #" + Fmt.trunc(text, 40));
        users.clearState(u);
        gateway.send(chat, View.of(Tr.t(clear ? "flow.topic_cleared" : "flow.topic_saved")));
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
            gateway.send(chat, View.of(Tr.t("flow.ban_not_number")));
            return;
        }
        Optional<User> existing = users.findByTelegramId(id);
        if (existing.isPresent() && existing.get().isBotAdmin()) {
            users.clearState(u);
            gateway.send(chat, View.of(Tr.t("flow.ban_admin")));
            return;
        }
        Optional<User> target = users.setBanned(id, true);
        users.clearState(u);
        gateway.send(chat, target.map(admin::user).orElse(View.of(Tr.t("flow.ban_unknown"))));
    }

    // ------------------------------------------------------------------ connecting a channel by name or forward

    private void connectChannel(User u, long chat, String ref) {
        gateway.typing(chat);
        Optional<BotApi.ChatInfo> info = ref.startsWith("@") ? gateway.chat(ref) : gateway.chat(Long.parseLong(ref));
        if (info.isEmpty()) {
            if (ref.startsWith("@")) watch.track(u, chat, ref); // not a channel the bot is in: maybe it's someone else's public channel
            else gateway.send(chat, View.of(Tr.t("flow.cant_find")));
            return;
        }
        BotApi.ChatInfo c = info.get();
        if (!"channel".equals(c.type())) {
            gateway.send(chat, View.of(Tr.t("flow.not_a_channel", Fmt.esc(c.type()))));
            return;
        }
        Optional<String> botStatus = gateway.memberStatus(c.id(), gateway.botId());
        if (botStatus.isEmpty() || !"administrator".equals(botStatus.get())) {
            View v = View.of(Tr.t("flow.not_admin_yet", Fmt.esc(c.title())));
            v.row(Btn.link(Tr.t("btn.add_to_channel"), "https://t.me/" + gateway.botUsername() + "?startchannel=true"));
            if (c.username() != null) {
                v.line(Tr.t("watch.not_admin_track"));
                v.row(Btn.cb(Tr.t("watch.btn.just_track"), Cb.of("wl:addu", c.username())));
            }
            gateway.send(chat, v);
            return;
        }
        Optional<String> userStatus = gateway.memberStatus(c.id(), u.getTelegramId());
        if (userStatus.isEmpty() || !(userStatus.get().equals("creator") || userStatus.get().equals("administrator"))) {
            View v = View.of(Tr.t("flow.only_admins", Fmt.esc(c.title())));
            if (c.username() != null) v.row(Btn.cb(Tr.t("watch.btn.just_track"), Cb.of("wl:addu", c.username())));
            gateway.send(chat, v);
            return;
        }
        connector.connect(u, c.id(), c.title(), c.username());
    }

    // ------------------------------------------------------------------ history import

    private void document(User u, Events.PrivateMessage m) {
        long chat = m.chatId();
        Optional<UserService.Pending> p = users.pending(u);
        if (p.isEmpty() || !UserState.AWAIT_IMPORT.equals(p.get().state())) {
            gateway.send(chat, View.of(Tr.t("flow.import_first")));
            return;
        }
        Channel ch = access.require(u, Long.parseLong(p.get().arg()), Permission.IMPORT_DATA);
        Events.DocumentInfo doc = m.document();
        if (doc.size() > MAX_IMPORT_BYTES) {
            gateway.send(chat, View.of(Tr.t("flow.import_too_big")));
            return;
        }
        if (doc.name() != null && !doc.name().toLowerCase(Locale.ROOT).endsWith(".json")) {
            gateway.send(chat, View.of(Tr.t("flow.import_not_json")));
            return;
        }
        users.clearState(u);
        gateway.send(chat, View.of(Tr.t("flow.importing")));
        Lang lang = Tr.lang();
        worker.submit(() -> {
            Tr.use(lang);
            runImport(u, chat, ch, doc);
        });
    }

    private void runImport(User u, long chat, Channel ch, Events.DocumentInfo doc) {
        try (InputStream in = gateway.download(doc.fileId())) {
            ImportService.Result r = importer.importExport(ch, u, in);
            gateway.send(chat, View.of(Tr.t("flow.imported", Fmt.num(r.total()), Fmt.num(r.created()), Fmt.num(r.updated())))
                    .line(Tr.t("flow.imported_hint"))
                    .row(Btn.cb(Tr.t("btn.open_dashboard"), Cb.of("nav:home", ch.getId()))));
        } catch (UserFacingException e) {
            gateway.send(chat, View.of("⚠️ " + e.getMessage()));
        } catch (IOException e) {
            log.warn("Import failed", e);
            gateway.send(chat, View.of(Tr.t("flow.import_unreadable", Fmt.esc(String.valueOf(e.getMessage())))));
        } catch (RuntimeException e) {
            log.error("Import crashed", e);
            gateway.send(chat, View.of(Tr.t("flow.import_crashed")));
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
