package com.halty.gramalyzerbot.bot.flow;

import com.halty.gramalyzerbot.analytics.Metric;
import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.bot.event.Events;
import com.halty.gramalyzerbot.bot.screen.*;
import com.halty.gramalyzerbot.bot.ui.*;
import com.halty.gramalyzerbot.channel.*;
import com.halty.gramalyzerbot.common.UserFacingException;
import com.halty.gramalyzerbot.post.AnalysisService;
import com.halty.gramalyzerbot.post.DemoService;
import com.halty.gramalyzerbot.post.PostService;
import com.halty.gramalyzerbot.settings.BotSettings;
import com.halty.gramalyzerbot.user.User;
import com.halty.gramalyzerbot.user.UserService;
import com.halty.gramalyzerbot.user.UserState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Handles every inline-button press. Screens are edited in place so the chat stays tidy; the callback is
 * always answered so the button never keeps spinning.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CallbackFlow {

    private static final Duration PROMPT_TTL = Duration.ofMinutes(15);

    private final Sessions sessions;
    private final UserService users;
    private final Access access;
    private final ChannelService channels;
    private final MembershipService membership;
    private final SettingsService settings;
    private final AuditService audit;
    private final PostService posts;
    private final AnalysisService analysis;
    private final DemoService demo;
    private final StartScreens start;
    private final HomeScreen home;
    private final InsightScreens insights;
    private final SettingsScreens settingsScreens;
    private final TeamScreens team;
    private final AdminScreens admin;
    private final BotSettings botSettings;
    private final BotApi gateway;

    /** What to tell the person once the action is done (a small toast, or a blocking alert for problems). */
    private record Toast(String text, boolean alert) {
        static final Toast NONE = new Toast(null, false);

        static Toast of(String text) { return new Toast(text, false); }
    }

    public void handle(Events.Callback c) {
        Optional<User> opt = sessions.open(c.userId(), c.username(), c.firstName(), c.lang(), c.chatId());
        if (opt.isEmpty()) {
            gateway.answer(c.id(), "🚫 You've been blocked.", true);
            return;
        }
        User u = opt.get();
        Toast toast;
        try {
            toast = dispatch(u, c, Cb.parse(c.data()));
        } catch (UserFacingException e) {
            toast = new Toast(e.getMessage().replaceAll("<[^>]+>", ""), true);
        } catch (ArrayIndexOutOfBoundsException | IllegalArgumentException e) { // includes NumberFormatException
            log.debug("Stale or malformed button {}: {}", c.data(), e.toString());
            toast = new Toast("That button is out of date – open the menu again.", true);
        } catch (RuntimeException e) {
            log.error("Failed to handle button {}", c.data(), e);
            toast = new Toast("Something went wrong. Please try again.", true);
        }
        gateway.answer(c.id(), toast.text(), toast.alert());
    }

    private void show(Events.Callback c, View v) { gateway.edit(c.chatId(), c.messageId(), v); }

    private Toast dispatch(User u, Events.Callback c, Cb.Args a) {
        return switch (a.get(0)) {
            case "nav" -> nav(u, c, a);
            case "ch" -> chan(u, c, a);
            case "help" -> {
                show(c, start.help(access.active(u).isPresent()));
                yield Toast.NONE;
            }
            case "set" -> set(u, c, a);
            case "me" -> me(u, c, a);
            case "team" -> teamAction(u, c, a);
            case "pm" -> postModeration(u, c, a);
            case "adm" -> adminAction(u, c, a);
            default -> throw new IllegalArgumentException("unknown action " + a.get(0));
        };
    }

    // ------------------------------------------------------------------ navigation

    private Toast nav(User u, Events.Callback c, Cb.Args a) {
        if (a.get(1).equals("active")) {
            Optional<Channel> ch = access.active(u);
            if (ch.isPresent()) show(c, home.render(ch.get(), access.roleOf(u, ch.get())));
            else show(c, start.welcome());
            return Toast.NONE;
        }
        Channel ch = access.require(u, a.getLong(2), Permission.VIEW);
        Role role = access.roleOf(u, ch);
        switch (a.get(1)) {
            case "home" -> {
                users.setActiveChannel(u, ch.getId());
                show(c, home.render(ch, role));
            }
            case "times" -> show(c, insights.times(ch, a.getInt(3), a.get(4)));
            case "content" -> show(c, insights.content(ch, a.getInt(3), a.get(4)));
            case "top" -> show(c, insights.top(ch, role, a.getInt(3)));
            case "excluded" -> show(c, insights.excluded(ch, role));
            case "growth" -> show(c, insights.growth(ch, a.getInt(3)));
            default -> throw new IllegalArgumentException("unknown nav " + a.get(1));
        }
        return Toast.NONE;
    }

    private Toast chan(User u, Events.Callback c, Cb.Args a) {
        switch (a.get(1)) {
            case "list" -> show(c, start.channels(channels.forUser(u), u.getActiveChannelId(), u.isBotAdmin()));
            case "pick" -> {
                Channel ch = access.require(u, a.getLong(2), Permission.VIEW);
                users.setActiveChannel(u, ch.getId());
                show(c, home.render(ch, access.roleOf(u, ch)));
            }
            case "connect" -> {
                sessions.requireMayCreate(u);
                users.setState(u, UserState.AWAIT_CONNECT, "", PROMPT_TTL);
                show(c, start.connect());
            }
            case "check" -> {
                Optional<Channel> ch = access.active(u);
                if (ch.isEmpty()) return new Toast("I don't see a channel yet. Add me as an admin first, or send the channel's @username.", true);
                show(c, home.render(ch.get(), access.roleOf(u, ch.get())));
            }
            case "demo" -> {
                sessions.requireMayCreate(u);
                gateway.typing(c.chatId());
                Channel ch = demo.createFor(u);
                users.setActiveChannel(u, ch.getId());
                show(c, home.render(ch, Role.OWNER));
                return Toast.of("🎮 Sample channel ready");
            }
            default -> throw new IllegalArgumentException("unknown ch " + a.get(1));
        }
        return Toast.NONE;
    }

    // ------------------------------------------------------------------ settings

    private Toast set(User u, Events.Callback c, Cb.Args a) {
        String action = a.get(1);
        long id = a.getLong(2);
        Permission needed = switch (action) {
            case "import" -> Permission.IMPORT_DATA;
            case "reset", "resetgo" -> Permission.RESET_DATA;
            case "disc", "discgo" -> Permission.DISCONNECT;
            default -> Permission.CHANGE_SETTINGS;
        };
        Channel ch = access.require(u, id, needed);
        Role role = access.roleOf(u, ch);
        switch (action) {
            case "home" -> {
                users.clearState(u);
                show(c, settingsScreens.home(ch, role));
            }
            case "tz" -> {
                users.setState(u, UserState.AWAIT_TIMEZONE, String.valueOf(id), PROMPT_TTL);
                show(c, settingsScreens.timezonePrompt(ch));
            }
            case "tzset" -> {
                ZoneId zone = ZoneId.of(a.rest(3));
                Channel saved = settings.setTimezone(ch, u, zone);
                users.clearState(u);
                show(c, settingsScreens.home(saved, role));
                return Toast.of("✅ Timezone saved");
            }
            case "win" -> show(c, settingsScreens.window(ch));
            case "winset" -> {
                show(c, settingsScreens.home(settings.setWindow(ch, u, a.getInt(3)), role));
                return Toast.of("✅ Saved");
            }
            case "met" -> show(c, settingsScreens.metric(ch));
            case "metset" -> {
                show(c, settingsScreens.home(settings.setMetric(ch, u, Metric.valueOf(a.get(3))), role));
                return Toast.of("✅ Saved");
            }
            case "dig" -> show(c, settingsScreens.digest(ch));
            case "dday" -> {
                show(c, settingsScreens.digest(settings.setDigestDay(ch, u, DayOfWeek.valueOf(a.get(3)))));
                return Toast.of("✅ Saved");
            }
            case "dhour" -> {
                show(c, settingsScreens.digest(settings.setDigestHour(ch, u, a.getInt(3))));
                return Toast.of("✅ Saved");
            }
            case "nud" -> show(c, settingsScreens.nudges(ch));
            case "lead" -> {
                show(c, settingsScreens.nudges(settings.setNudgeLead(ch, u, a.getInt(3))));
                return Toast.of("✅ Saved");
            }
            case "gap" -> {
                show(c, settingsScreens.nudges(settings.setNudgeGap(ch, u, a.getInt(3))));
                return Toast.of("✅ Saved");
            }
            case "com" -> show(c, settingsScreens.comments(ch, null));
            case "comchk" -> {
                String note = checkDiscussion(ch, u);
                show(c, settingsScreens.comments(channels.get(id), note));
            }
            case "import" -> {
                users.setState(u, UserState.AWAIT_IMPORT, String.valueOf(id), Duration.ofMinutes(30));
                show(c, settingsScreens.importPrompt(ch));
            }
            case "reset" -> show(c, settingsScreens.confirm("🧹 <b>Reset all collected data for " + Fmt.esc(ch.getTitle()) + "?</b>\n\nPosts, reactions and subscriber history are deleted. Settings and team stay.",
                    "🧹 Yes, reset", Cb.of("set:resetgo", id), Cb.of("set:home", id)));
            case "resetgo" -> {
                channels.resetData(ch);
                analysis.invalidate(id);
                audit.record(id, u.getId(), "data.reset", "All collected data was reset");
                show(c, settingsScreens.home(channels.get(id), role));
                return Toast.of("🧹 Data reset");
            }
            case "disc" -> show(c, settingsScreens.confirm(ch.isDemo()
                            ? "🗑 <b>Delete the sample channel?</b>"
                            : "🔌 <b>Disconnect " + Fmt.esc(ch.getTitle()) + "?</b>\n\nAll its data and its team are deleted, and I'll leave the channel.",
                    "Yes, " + (ch.isDemo() ? "delete" : "disconnect"), Cb.of("set:discgo", id), Cb.of("set:home", id)));
            case "discgo" -> {
                boolean demoChannel = ch.isDemo();
                long chatId = ch.getTelegramChatId();
                channels.deleteAll(ch);
                if (!demoChannel) gateway.leave(chatId);
                users.setActiveChannel(u, null);
                show(c, start.channels(channels.forUser(u), null, u.isBotAdmin()));
                return Toast.of(demoChannel ? "🗑 Deleted" : "🔌 Disconnected");
            }
            default -> throw new IllegalArgumentException("unknown set " + action);
        }
        return Toast.NONE;
    }

    /** Looks up the channel's linked discussion group and confirms the bot can see it. */
    private String checkDiscussion(Channel ch, User actor) {
        Long linked = gateway.chat(ch.getTelegramChatId()).map(BotApi.ChatInfo::linkedChatId).orElse(null);
        if (linked == null) return "⚠️ This channel has no discussion group linked yet.";
        Optional<String> status = gateway.memberStatus(linked, gateway.botId());
        if (status.isPresent() && (status.get().equals("administrator") || status.get().equals("member") || status.get().equals("creator"))) {
            settings.linkDiscussion(ch, actor, linked);
            return "✅ Linked! I'll count comments from now on.";
        }
        return "⚠️ A discussion group is linked, but I'm not in it yet. Add me as an admin there.";
    }

    private Toast me(User u, Events.Callback c, Cb.Args a) {
        Channel ch = access.require(u, a.getLong(2), Permission.VIEW);
        switch (a.get(1)) {
            case "home" -> { }
            case "dig" -> membership.setNotify(ch, u, !currentMember(ch, u).isNotifyDigest(), null);
            case "nud" -> membership.setNotify(ch, u, null, !currentMember(ch, u).isNotifyNudges());
            default -> throw new IllegalArgumentException("unknown me " + a.get(1));
        }
        show(c, settingsScreens.notifications(ch, currentMember(ch, u)));
        return a.get(1).equals("home") ? Toast.NONE : Toast.of("✅ Saved");
    }

    private ChannelMember currentMember(Channel ch, User u) {
        return membership.membership(ch, u).orElseThrow(() -> new UserFacingException("Join the team first to set notifications."));
    }

    // ------------------------------------------------------------------ team

    private Toast teamAction(User u, Events.Callback c, Cb.Args a) {
        Channel ch = access.require(u, a.getLong(2), Permission.VIEW);
        Role role = access.roleOf(u, ch);
        long id = ch.getId();
        switch (a.get(1)) {
            case "home" -> show(c, team.home(ch, u, role));
            case "member" -> {
                MembershipService.MemberRow row = findMember(ch, a.getLong(3));
                show(c, team.member(ch, role, row));
            }
            case "role" -> {
                membership.changeRole(ch, u, a.getLong(3), Role.valueOf(a.get(4)));
                show(c, team.home(ch, u, role));
                return Toast.of("✅ Role updated");
            }
            case "rm" -> {
                MembershipService.MemberRow row = findMember(ch, a.getLong(3));
                show(c, settingsScreens.confirm("🚫 <b>Remove " + Fmt.esc(row.user().displayName()) + " from the team?</b>",
                        "Yes, remove", Cb.of("team:rmgo", id, row.user().getId()), Cb.of("team:member", id, row.user().getId())));
            }
            case "rmgo" -> {
                membership.remove(ch, u, a.getLong(3));
                show(c, team.home(ch, u, role));
                return Toast.of("✅ Removed");
            }
            case "inv" -> {
                Invite inv = membership.createInvite(ch, u, Role.valueOf(a.get(3)));
                show(c, team.invite(ch, inv, gateway.botUsername()));
            }
            case "invrev" -> {
                membership.revokeInvite(ch, u, a.getLong(3));
                show(c, team.home(ch, u, role));
                return Toast.of("🗑 Link revoked");
            }
            case "log" -> {
                membership.require(ch, u, Permission.CHANGE_SETTINGS);
                show(c, team.activity(ch));
            }
            case "leave" -> show(c, settingsScreens.confirm("🚪 <b>Leave the team for " + Fmt.esc(ch.getTitle()) + "?</b>\n\nYou'll lose access until someone invites you again.",
                    "Yes, leave", Cb.of("team:leavego", id), Cb.of("team:home", id)));
            case "leavego" -> {
                membership.leave(ch, u);
                show(c, start.channels(channels.forUser(u), null, u.isBotAdmin()));
                return Toast.of("👋 You left the team");
            }
            default -> throw new IllegalArgumentException("unknown team " + a.get(1));
        }
        return Toast.NONE;
    }

    private MembershipService.MemberRow findMember(Channel ch, long userId) {
        return membership.team(ch).stream().filter(r -> r.user().getId() == userId).findFirst()
                .orElseThrow(() -> new UserFacingException("That person is no longer on the team."));
    }

    // ------------------------------------------------------------------ post moderation

    private Toast postModeration(User u, Events.Callback c, Cb.Args a) {
        Channel ch = access.require(u, a.getLong(2), Permission.MODERATE_POSTS);
        Role role = access.roleOf(u, ch);
        long postId = a.getLong(3);
        switch (a.get(1)) {
            case "excl" -> {
                boolean nowExcluded = posts.toggleExcluded(ch, postId);
                audit.record(ch.getId(), u.getId(), nowExcluded ? "post.exclude" : "post.restore",
                        (nowExcluded ? "Excluded" : "Restored") + " a post " + (nowExcluded ? "from" : "to") + " the analysis");
                if (a.get(4).equals("x")) show(c, insights.excluded(ch, role));
                else show(c, insights.top(ch, role, a.get(4).isEmpty() ? 0 : a.getInt(4)));
                return Toast.of(nowExcluded ? "🚫 Left out of the analysis" : "↩ Back in the analysis");
            }
            case "topic" -> {
                posts.getFor(ch, postId); // make sure it exists
                users.setState(u, UserState.AWAIT_TOPIC, ch.getId() + ":" + postId, PROMPT_TTL);
                show(c, new View().line("🏷 <b>Label this post</b>").blank()
                        .line("Send me a short topic, like <code>giveaway</code> or <code>review</code>. I'll group posts with the same topic.")
                        .line("Send <code>-</code> to clear the label.")
                        .row(Btn.cb("✖ Cancel", Cb.of("nav:top", ch.getId(), 0))));
            }
            default -> throw new IllegalArgumentException("unknown pm " + a.get(1));
        }
        return Toast.NONE;
    }

    // ------------------------------------------------------------------ bot admin

    private Toast adminAction(User u, Events.Callback c, Cb.Args a) {
        if (!u.isBotAdmin()) throw new UserFacingException("🔒 Bot admins only.");
        switch (a.get(1)) {
            case "home" -> {
                users.clearState(u);
                show(c, admin.home());
            }
            case "users" -> show(c, admin.users(a.getInt(2)));
            case "user" -> show(c, admin.user(users.findByTelegramId(a.getLong(2)).orElseThrow(() -> new UserFacingException("Unknown user."))));
            case "ban", "unban" -> {
                boolean ban = a.get(1).equals("ban");
                User existing = users.findByTelegramId(a.getLong(2)).orElseThrow(() -> new UserFacingException("Unknown user."));
                if (existing.isBotAdmin()) throw new UserFacingException("Bot admins can't be banned.");
                User target = users.setBanned(existing.getTelegramId(), ban).orElse(existing);
                show(c, admin.user(target));
                return Toast.of(ban ? "🚫 Banned" : "✅ Unbanned");
            }
            case "chs" -> show(c, admin.channels(a.getInt(2)));
            case "reg" -> {
                botSettings.setPublicRegistration(!botSettings.publicRegistration());
                show(c, admin.home());
                return Toast.of("✅ Saved");
            }
            case "banask" -> {
                users.setState(u, UserState.AWAIT_BAN, "", PROMPT_TTL);
                show(c, admin.banPrompt());
            }
            default -> throw new IllegalArgumentException("unknown adm " + a.get(1));
        }
        return Toast.NONE;
    }
}
