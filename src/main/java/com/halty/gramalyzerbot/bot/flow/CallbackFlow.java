package com.halty.gramalyzerbot.bot.flow;

import com.halty.gramalyzerbot.analytics.Metric;
import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.bot.event.Events;
import com.halty.gramalyzerbot.bot.screen.*;
import com.halty.gramalyzerbot.bot.ui.*;
import com.halty.gramalyzerbot.channel.*;
import com.halty.gramalyzerbot.common.UserFacingException;
import com.halty.gramalyzerbot.growth.GrowthService;
import com.halty.gramalyzerbot.growth.PromoLink;
import com.halty.gramalyzerbot.i18n.Lang;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.post.AnalysisService;
import com.halty.gramalyzerbot.post.DemoService;
import com.halty.gramalyzerbot.post.PostService;
import com.halty.gramalyzerbot.settings.BotSettings;
import com.halty.gramalyzerbot.tracking.TrackedChannel;
import com.halty.gramalyzerbot.tracking.TrackingException;
import com.halty.gramalyzerbot.tracking.TrackingService;
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
    private final GrowthScreens growthScreens;
    private final GrowthService growth;
    private final SettingsScreens settingsScreens;
    private final TeamScreens team;
    private final AdminScreens admin;
    private final WatchFlow watch;
    private final BillingFlow billing;
    private final TrackingService tracking;
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
            gateway.answer(c.id(), Tr.t("toast.blocked"), true);
            return;
        }
        User u = opt.get();
        Toast toast;
        try {
            toast = dispatch(u, c, Cb.parse(c.data()));
        } catch (UserFacingException e) {
            toast = new Toast(e.getMessage().replaceAll("<[^>]+>", ""), true);
        } catch (TrackingException e) {
            toast = new Toast(WatchScreens.error(e), true);
        } catch (ArrayIndexOutOfBoundsException | IllegalArgumentException e) { // includes NumberFormatException
            log.debug("Stale or malformed button {}: {}", c.data(), e.toString());
            toast = new Toast(Tr.t("toast.stale"), true);
        } catch (RuntimeException e) {
            log.error("Failed to handle button {}", c.data(), e);
            toast = new Toast(Tr.t("toast.failed"), true);
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
            case "pr" -> promo(u, c, a);
            case "adm" -> adminAction(u, c, a);
            case "wl" -> watchlist(u, c, a);
            case "lang" -> language(u, c, a);
            case "bill" -> {
                String text = billing.callback(u, c.chatId(), c.messageId(), a);
                yield text == null ? Toast.NONE : new Toast(text, true); // about money: worth a dialog, not a passing toast
            }
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
            case "growth" -> show(c, growthScreens.growth(ch, a.getInt(3)));
            default -> throw new IllegalArgumentException("unknown nav " + a.get(1));
        }
        return Toast.NONE;
    }

    // ------------------------------------------------------------------ promo links

    private Toast promo(User u, Events.Callback c, Cb.Args a) {
        String action = a.get(1);
        Channel ch = access.require(u, a.getLong(2), action.equals("list") ? Permission.VIEW : Permission.CHANGE_SETTINGS);
        switch (action) {
            case "list" -> {
                users.clearState(u);
                show(c, growthScreens.promo(ch, access.roleOf(u, ch)));
            }
            case "new" -> {
                users.setState(u, UserState.AWAIT_PROMO, String.valueOf(ch.getId()), PROMPT_TTL);
                show(c, growthScreens.promoPrompt(ch));
            }
            case "rv" -> show(c, growthScreens.confirmRevoke(ch, growth.link(ch, a.getLong(3))));
            case "rvgo" -> {
                PromoLink l = growth.revoke(ch, a.getLong(3));
                audit.record(ch.getId(), u.getId(), "promo.revoke", "Revoked the promo link \"" + Fmt.trunc(l.getName(), 40) + "\"");
                show(c, growthScreens.promo(ch, access.roleOf(u, ch)));
                return Toast.of(Tr.t("toast.link_revoked"));
            }
            default -> throw new IllegalArgumentException("unknown pr " + action);
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
                if (ch.isEmpty()) return new Toast(Tr.t("toast.no_channel_yet"), true);
                show(c, home.render(ch.get(), access.roleOf(u, ch.get())));
            }
            case "demo" -> {
                sessions.requireMayCreate(u);
                gateway.typing(c.chatId());
                Channel ch = demo.createFor(u);
                users.setActiveChannel(u, ch.getId());
                show(c, home.render(ch, Role.OWNER));
                return Toast.of(Tr.t("toast.demo_ready"));
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
                return Toast.of(Tr.t("toast.tz_saved"));
            }
            case "win" -> show(c, settingsScreens.window(ch));
            case "winset" -> {
                show(c, settingsScreens.home(settings.setWindow(ch, u, a.getInt(3)), role));
                return Toast.of(Tr.t("toast.saved"));
            }
            case "met" -> show(c, settingsScreens.metric(ch));
            case "metset" -> {
                show(c, settingsScreens.home(settings.setMetric(ch, u, Metric.valueOf(a.get(3))), role));
                return Toast.of(Tr.t("toast.saved"));
            }
            case "dig" -> show(c, settingsScreens.digest(ch));
            case "dday" -> {
                show(c, settingsScreens.digest(settings.setDigestDay(ch, u, DayOfWeek.valueOf(a.get(3)))));
                return Toast.of(Tr.t("toast.saved"));
            }
            case "dhour" -> {
                show(c, settingsScreens.digest(settings.setDigestHour(ch, u, a.getInt(3))));
                return Toast.of(Tr.t("toast.saved"));
            }
            case "nud" -> show(c, settingsScreens.nudges(ch));
            case "lead" -> {
                show(c, settingsScreens.nudges(settings.setNudgeLead(ch, u, a.getInt(3))));
                return Toast.of(Tr.t("toast.saved"));
            }
            case "gap" -> {
                show(c, settingsScreens.nudges(settings.setNudgeGap(ch, u, a.getInt(3))));
                return Toast.of(Tr.t("toast.saved"));
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
            case "reset" -> show(c, settingsScreens.confirm(Tr.t("confirm.reset", Fmt.esc(ch.getTitle())),
                    Tr.t("confirm.reset_yes"), Cb.of("set:resetgo", id), Cb.of("set:home", id)));
            case "resetgo" -> {
                channels.resetData(ch);
                analysis.invalidate(id);
                audit.record(id, u.getId(), "data.reset", "All collected data was reset");
                show(c, settingsScreens.home(channels.get(id), role));
                return Toast.of(Tr.t("toast.data_reset"));
            }
            case "disc" -> show(c, settingsScreens.confirm(ch.isDemo() ? Tr.t("confirm.delete_demo") : Tr.t("confirm.disconnect", Fmt.esc(ch.getTitle())),
                    Tr.t(ch.isDemo() ? "confirm.delete_yes" : "confirm.disconnect_yes"), Cb.of("set:discgo", id), Cb.of("set:home", id)));
            case "discgo" -> {
                boolean demoChannel = ch.isDemo();
                long chatId = ch.getTelegramChatId();
                channels.deleteAll(ch);
                if (!demoChannel) gateway.leave(chatId);
                users.setActiveChannel(u, null);
                show(c, start.channels(channels.forUser(u), null, u.isBotAdmin()));
                return Toast.of(Tr.t(demoChannel ? "toast.deleted" : "toast.disconnected"));
            }
            default -> throw new IllegalArgumentException("unknown set " + action);
        }
        return Toast.NONE;
    }

    /** Looks up the channel's linked discussion group and confirms the bot can see it. */
    private String checkDiscussion(Channel ch, User actor) {
        Long linked = gateway.chat(ch.getTelegramChatId()).map(BotApi.ChatInfo::linkedChatId).orElse(null);
        if (linked == null) return Tr.t("com.no_group");
        Optional<String> status = gateway.memberStatus(linked, gateway.botId());
        if (status.isPresent() && (status.get().equals("administrator") || status.get().equals("member") || status.get().equals("creator"))) {
            settings.linkDiscussion(ch, actor, linked);
            return Tr.t("com.linked");
        }
        return Tr.t("com.not_in_group");
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
        return membership.membership(ch, u).orElseThrow(() -> new UserFacingException("err.join_first"));
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
                return Toast.of(Tr.t("toast.role_updated"));
            }
            case "rm" -> {
                MembershipService.MemberRow row = findMember(ch, a.getLong(3));
                show(c, settingsScreens.confirm(Tr.t("team.remove_q", Fmt.esc(row.user().displayName())),
                        Tr.t("team.remove_yes"), Cb.of("team:rmgo", id, row.user().getId()), Cb.of("team:member", id, row.user().getId())));
            }
            case "rmgo" -> {
                membership.remove(ch, u, a.getLong(3));
                show(c, team.home(ch, u, role));
                return Toast.of(Tr.t("toast.removed"));
            }
            case "inv" -> {
                Invite inv = membership.createInvite(ch, u, Role.valueOf(a.get(3)));
                show(c, team.invite(ch, inv, gateway.botUsername()));
            }
            case "invrev" -> {
                membership.revokeInvite(ch, u, a.getLong(3));
                show(c, team.home(ch, u, role));
                return Toast.of(Tr.t("toast.link_revoked"));
            }
            case "log" -> {
                membership.require(ch, u, Permission.CHANGE_SETTINGS);
                show(c, team.activity(ch));
            }
            case "leave" -> show(c, settingsScreens.confirm(Tr.t("team.leave_q", Fmt.esc(ch.getTitle())),
                    Tr.t("team.leave_yes"), Cb.of("team:leavego", id), Cb.of("team:home", id)));
            case "leavego" -> {
                membership.leave(ch, u);
                show(c, start.channels(channels.forUser(u), null, u.isBotAdmin()));
                return Toast.of(Tr.t("toast.left"));
            }
            default -> throw new IllegalArgumentException("unknown team " + a.get(1));
        }
        return Toast.NONE;
    }

    private MembershipService.MemberRow findMember(Channel ch, long userId) {
        return membership.team(ch).stream().filter(r -> r.user().getId() == userId).findFirst()
                .orElseThrow(() -> new UserFacingException("err.person_gone"));
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
                return Toast.of(Tr.t(nowExcluded ? "toast.excluded" : "toast.restored"));
            }
            case "topic" -> {
                posts.getFor(ch, postId); // make sure it exists
                users.setState(u, UserState.AWAIT_TOPIC, ch.getId() + ":" + postId, PROMPT_TTL);
                show(c, new View().line(Tr.t("flow.topic_title")).blank()
                        .line(Tr.t("flow.topic_hint"))
                        .line(Tr.t("flow.topic_clear"))
                        .row(Btn.cb(Tr.t("btn.cancel"), Cb.of("nav:top", ch.getId(), 0))));
            }
            default -> throw new IllegalArgumentException("unknown pm " + a.get(1));
        }
        return Toast.NONE;
    }

    // ------------------------------------------------------------------ bot admin

    private Toast adminAction(User u, Events.Callback c, Cb.Args a) {
        if (!u.isBotAdmin()) throw new UserFacingException("err.admins_only");
        switch (a.get(1)) {
            case "home" -> {
                users.clearState(u);
                show(c, admin.home());
            }
            case "users" -> show(c, admin.users(a.getInt(2)));
            case "user" -> show(c, admin.user(users.findByTelegramId(a.getLong(2)).orElseThrow(() -> new UserFacingException("err.unknown_user"))));
            case "ban", "unban" -> {
                boolean ban = a.get(1).equals("ban");
                User existing = users.findByTelegramId(a.getLong(2)).orElseThrow(() -> new UserFacingException("err.unknown_user"));
                if (existing.isBotAdmin()) throw new UserFacingException("err.cant_ban_admin");
                User target = users.setBanned(existing.getTelegramId(), ban).orElse(existing);
                show(c, admin.user(target));
                return Toast.of(Tr.t(ban ? "toast.banned" : "toast.unbanned"));
            }
            case "chs" -> show(c, admin.channels(a.getInt(2)));
            case "reg" -> {
                botSettings.setPublicRegistration(!botSettings.publicRegistration());
                show(c, admin.home());
                return Toast.of(Tr.t("toast.saved"));
            }
            case "banask" -> {
                users.setState(u, UserState.AWAIT_BAN, "", PROMPT_TTL);
                show(c, admin.banPrompt());
            }
            default -> throw new IllegalArgumentException("unknown adm " + a.get(1));
        }
        return Toast.NONE;
    }

    // ------------------------------------------------------------------ watchlist

    private Toast watchlist(User u, Events.Callback c, Cb.Args a) {
        String owner = TrackingService.owner(u);
        switch (a.get(1)) {
            case "list" -> {
                users.clearState(u);
                show(c, watch.list(u));
            }
            case "add" -> show(c, watch.askForChannel(u));
            case "show" -> show(c, watch.card(u, tracking.get(a.getLong(2)), null));
            case "top" -> show(c, watch.top(tracking.get(a.getLong(2))));
            case "ref" -> {
                gateway.typing(c.chatId());
                TrackedChannel ch = tracking.refresh(tracking.get(a.getLong(2)), true);
                show(c, watch.card(u, ch, null));
                return Toast.of(Tr.t("watch.refreshed"));
            }
            case "rm" -> {
                tracking.untrack(owner, tracking.get(a.getLong(2)).getUsername());
                show(c, watch.list(u));
                return Toast.of(Tr.t("watch.removed"));
            }
            case "addid", "addu" -> {
                gateway.typing(c.chatId());
                String username = a.get(1).equals("addid") ? tracking.get(a.getLong(2)).getUsername() : a.get(2);
                TrackedChannel ch = watch.add(u, username);
                show(c, watch.card(u, ch, null));
                return Toast.of(Tr.t("watch.added"));
            }
            default -> throw new IllegalArgumentException("unknown wl " + a.get(1));
        }
        return Toast.NONE;
    }

    // ------------------------------------------------------------------ language

    private Toast language(User u, Events.Callback c, Cb.Args a) {
        switch (a.get(1)) {
            case "home" -> show(c, start.languages(Tr.lang()));
            case "set" -> {
                String code = a.get(2).equals("auto") ? null : Lang.fromCode(a.get(2)).orElseThrow(IllegalArgumentException::new).code();
                users.setLanguage(u, code);
                Lang now = Lang.of(u);
                Tr.use(now);
                show(c, start.languages(now));
                // the bottom keyboard only changes with a new message
                gateway.sendWithKeyboard(c.chatId(), Tr.t("lang.saved", now.nativeName()), Menu.keyboard());
                return Toast.of("✅ " + now.label());
            }
            default -> throw new IllegalArgumentException("unknown lang " + a.get(1));
        }
        return Toast.NONE;
    }
}
