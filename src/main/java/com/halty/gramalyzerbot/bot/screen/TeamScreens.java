package com.halty.gramalyzerbot.bot.screen;

import com.halty.gramalyzerbot.bot.ui.*;
import com.halty.gramalyzerbot.channel.*;
import com.halty.gramalyzerbot.channel.MembershipService.MemberRow;
import com.halty.gramalyzerbot.user.User;
import com.halty.gramalyzerbot.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Who's on the team, invitations and the activity log. */
@Component
@RequiredArgsConstructor
public class TeamScreens {

    private final MembershipService membership;
    private final AuditService audit;
    private final UserService users;

    public View home(Channel ch, User viewer, Role viewerRole) {
        long id = ch.getId();
        Instant now = Instant.now();
        View v = new View().line("👥 <b>Team</b> · " + Fmt.esc(ch.getTitle())).line(Fmt.rule());
        List<MemberRow> team = membership.team(ch);
        for (MemberRow r : team) {
            User u = r.user();
            String handle = u.getUsername() != null ? " (@" + Fmt.esc(u.getUsername()) + ")" : "";
            String you = u.getId().equals(viewer.getId()) ? " <i>– you</i>" : "";
            v.line(r.member().getRole().icon() + " <b>" + Fmt.esc(u.displayName()) + "</b>" + handle + " · " + r.member().getRole().label() + you);
        }
        List<Invite> invites = viewerRole.can(Permission.INVITE_MODERATORS) ? membership.openInvites(ch) : List.of();
        if (!invites.isEmpty()) {
            v.blank().line("<b>Open invites</b>");
            for (Invite i : invites) {
                long hours = Math.max(1, Duration.between(now, i.getExpiresAt()).toHours());
                v.line("🔗 " + i.getRole().label() + " invite · expires in " + hours + " h");
                v.row(Btn.cb("🗑 Revoke " + i.getRole().label() + " link", Cb.of("team:invrev", id, i.getId())));
            }
        }
        for (MemberRow r : team) {
            boolean manageable = !r.user().getId().equals(viewer.getId())
                    && (TeamRules.canRemove(viewerRole, r.member().getRole())
                    || TeamRules.canChangeRole(viewerRole, r.member().getRole(), r.member().getRole() == Role.ADMIN ? Role.MODERATOR : Role.ADMIN));
            if (manageable) v.row(Btn.cb("⚙️ " + Fmt.trunc(r.user().displayName(), 24) + " · " + r.member().getRole().label(), Cb.of("team:member", id, r.user().getId())));
        }
        v.rowIf(TeamRules.canInvite(viewerRole, Role.MODERATOR), Btn.cb("➕ Invite a moderator", Cb.of("team:inv", id, Role.MODERATOR.name())));
        v.rowIf(TeamRules.canInvite(viewerRole, Role.ADMIN), Btn.cb("➕ Invite an admin", Cb.of("team:inv", id, Role.ADMIN.name())));
        v.rowIf(viewerRole.can(Permission.CHANGE_SETTINGS), Btn.cb("📜 Activity log", Cb.of("team:log", id)));
        v.rowIf(viewerRole != Role.OWNER && team.stream().anyMatch(r -> r.user().getId().equals(viewer.getId())),
                Btn.cb("🚪 Leave this team", Cb.of("team:leave", id)));
        return v.row(Btn.cb("‹ Dashboard", Cb.of("nav:home", id)));
    }

    public View member(Channel ch, Role viewerRole, MemberRow target) {
        long id = ch.getId();
        long uid = target.user().getId();
        Role role = target.member().getRole();
        View v = new View().line("⚙️ <b>" + Fmt.esc(target.user().displayName()) + "</b>")
                .line(role.badge() + " · joined " + Fmt.ago(target.member().getAddedAt(), Instant.now()))
                .blank();
        if (role == Role.ADMIN) v.line("Admins can change settings, import data and manage moderators.");
        else v.line("Moderators can see every insight and tag or exclude posts, but can't change settings.");
        v.rowIf(TeamRules.canChangeRole(viewerRole, role, Role.ADMIN), Btn.cb("⬆️ Make admin", Cb.of("team:role", id, uid, Role.ADMIN.name())));
        v.rowIf(TeamRules.canChangeRole(viewerRole, role, Role.MODERATOR), Btn.cb("⬇️ Make moderator", Cb.of("team:role", id, uid, Role.MODERATOR.name())));
        v.rowIf(TeamRules.canRemove(viewerRole, role), Btn.cb("🚫 Remove from team", Cb.of("team:rm", id, uid)));
        return v.row(Btn.cb("‹ Team", Cb.of("team:home", id)));
    }

    public View invite(Channel ch, Invite inv, String botUsername) {
        String link = "https://t.me/" + botUsername + "?start=join_" + inv.getToken();
        String shareText = "Join the " + ch.getTitle() + " analytics team as " + inv.getRole().label().toLowerCase();
        String share = "https://t.me/share/url?url=" + enc(link) + "&text=" + enc(shareText);
        return new View().line("🔗 <b>Invite link ready</b>").blank()
                .line("Role: " + inv.getRole().badge())
                .line("Valid for <b>48 hours</b>, one person.")
                .blank()
                .line("<code>" + Fmt.esc(link) + "</code>")
                .row(Btn.link("📤 Share link", share))
                .row(Btn.cb("🗑 Revoke", Cb.of("team:invrev", ch.getId(), inv.getId())))
                .row(Btn.cb("‹ Team", Cb.of("team:home", ch.getId())));
    }

    public View activity(Channel ch) {
        View v = new View().line("📜 <b>Activity log</b> · " + Fmt.esc(ch.getTitle())).blank();
        List<AuditEntry> entries = audit.recent(ch.getId());
        if (entries.isEmpty()) v.line("Nothing yet.");
        Instant now = Instant.now();
        for (AuditEntry e : entries) {
            String who = e.getActorUserId() == null ? "System" : users.findById(e.getActorUserId()).map(User::displayName).orElse("Someone");
            v.line("• <b>" + Fmt.esc(who) + "</b> – " + Fmt.esc(e.getDetails() == null ? e.getAction() : e.getDetails()) + " <i>(" + Fmt.ago(e.getCreatedAt(), now) + ")</i>");
        }
        return v.row(Btn.cb("‹ Team", Cb.of("team:home", ch.getId())));
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }
}
