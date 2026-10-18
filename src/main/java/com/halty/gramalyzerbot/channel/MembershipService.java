package com.halty.gramalyzerbot.channel;

import com.halty.gramalyzerbot.common.UserFacingException;
import com.halty.gramalyzerbot.user.User;
import com.halty.gramalyzerbot.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Team management for a channel: roles, invites and notification preferences. */
@Service
@RequiredArgsConstructor
public class MembershipService {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ChannelMemberRepository members;
    private final InviteRepository invites;
    private final UserRepository users;
    private final ChannelRepository channels;
    private final AuditService audit;

    public record MemberRow(ChannelMember member, User user) {}

    public record Joined(Channel channel, Role role, boolean alreadyMember) {}

    /** Bot administrators act as owners of every channel (support / moderation of the bot itself). */
    public Optional<Role> roleOf(Channel ch, User u) {
        if (u.isBotAdmin()) return Optional.of(Role.OWNER);
        return members.findByChannelIdAndUserId(ch.getId(), u.getId()).map(ChannelMember::getRole);
    }

    public Role require(Channel ch, User u, Permission p) {
        Role r = roleOf(ch, u).orElseThrow(() -> new UserFacingException("err.no_access"));
        if (!r.can(p)) throw new UserFacingException("err.needs_role", p.minimum());
        return r;
    }

    public Optional<ChannelMember> membership(Channel ch, User u) {
        return members.findByChannelIdAndUserId(ch.getId(), u.getId());
    }

    @Transactional
    public ChannelMember add(Channel ch, User u, Role role, Long addedBy) {
        Optional<ChannelMember> existing = members.findByChannelIdAndUserId(ch.getId(), u.getId());
        if (existing.isPresent()) {
            ChannelMember m = existing.get();
            if (role.level() > m.getRole().level()) {
                m.setRole(role);
                members.save(m);
            }
            return m;
        }
        return members.save(new ChannelMember(ch.getId(), u.getId(), role, addedBy));
    }

    /**
     * Someone who proved they administer the channel on Telegram connects it. They become its owner unless
     * the channel already has one here – then they need an invite.
     */
    @Transactional
    public Role claim(Channel ch, User u) {
        Optional<ChannelMember> existing = members.findByChannelIdAndUserId(ch.getId(), u.getId());
        if (existing.isPresent()) return existing.get().getRole();
        boolean hasOwner = members.findAllByChannelId(ch.getId()).stream().anyMatch(m -> m.getRole() == Role.OWNER);
        if (hasOwner)
            throw new UserFacingException("err.already_managed");
        members.save(new ChannelMember(ch.getId(), u.getId(), Role.OWNER, null));
        audit.record(ch.getId(), u.getId(), "channel.connect", u.displayName() + " connected the channel");
        return Role.OWNER;
    }

    public List<MemberRow> team(Channel ch) {
        List<ChannelMember> ms = members.findAllByChannelId(ch.getId());
        Map<Long, User> byId = new HashMap<>();
        for (User u : users.findAllById(ms.stream().map(ChannelMember::getUserId).toList())) byId.put(u.getId(), u);
        List<MemberRow> rows = new ArrayList<>();
        for (ChannelMember m : ms) {
            User u = byId.get(m.getUserId());
            if (u != null) rows.add(new MemberRow(m, u));
        }
        rows.sort(Comparator.comparingInt((MemberRow r) -> -r.member().getRole().level())
                .thenComparing(r -> r.member().getAddedAt()));
        return rows;
    }

    @Transactional
    public void changeRole(Channel ch, User actor, long targetUserId, Role newRole) {
        Role actorRole = require(ch, actor, Permission.INVITE_MODERATORS);
        ChannelMember target = members.findByChannelIdAndUserId(ch.getId(), targetUserId)
                .orElseThrow(() -> new UserFacingException("err.person_gone"));
        if (!TeamRules.canChangeRole(actorRole, target.getRole(), newRole))
            throw new UserFacingException("err.cant_change_role");
        Role old = target.getRole();
        target.setRole(newRole);
        members.save(target);
        audit.record(ch.getId(), actor.getId(), newRole.level() > old.level() ? "member.promote" : "member.demote",
                nameOf(targetUserId) + ": " + old.label() + " → " + newRole.label());
    }

    @Transactional
    public void remove(Channel ch, User actor, long targetUserId) {
        Role actorRole = require(ch, actor, Permission.INVITE_MODERATORS);
        ChannelMember target = members.findByChannelIdAndUserId(ch.getId(), targetUserId)
                .orElseThrow(() -> new UserFacingException("err.person_gone"));
        if (!TeamRules.canRemove(actorRole, target.getRole()))
            throw new UserFacingException("err.cant_remove");
        members.delete(target);
        audit.record(ch.getId(), actor.getId(), "member.remove", nameOf(targetUserId) + " (" + target.getRole().label() + ")");
    }

    @Transactional
    public void leave(Channel ch, User u) {
        ChannelMember m = members.findByChannelIdAndUserId(ch.getId(), u.getId())
                .orElseThrow(() -> new UserFacingException("err.not_on_team"));
        if (m.getRole() == Role.OWNER)
            throw new UserFacingException("err.owner_cant_leave");
        members.delete(m);
        audit.record(ch.getId(), u.getId(), "member.leave", u.displayName() + " left the team");
        if (Objects.equals(u.getActiveChannelId(), ch.getId())) {
            u.setActiveChannelId(null);
            users.save(u);
        }
    }

    @Transactional
    public Invite createInvite(Channel ch, User actor, Role role) {
        Role actorRole = require(ch, actor, Permission.INVITE_MODERATORS);
        if (!TeamRules.canInvite(actorRole, role)) throw new UserFacingException("err.cant_invite", role);
        Invite inv = new Invite();
        inv.setToken(randomToken(20));
        inv.setChannelId(ch.getId());
        inv.setRole(role);
        inv.setCreatedBy(actor.getId());
        inv.setCreatedAt(Instant.now());
        inv.setExpiresAt(Instant.now().plus(Duration.ofHours(48)));
        inv.setMaxUses(1);
        invites.save(inv);
        audit.record(ch.getId(), actor.getId(), "invite.create", "Invite link for a " + role.label());
        return inv;
    }

    public List<Invite> openInvites(Channel ch) {
        Instant now = Instant.now();
        return invites.findAllByChannelIdAndRevokedFalse(ch.getId()).stream().filter(i -> i.usable(now)).toList();
    }

    @Transactional
    public void revokeInvite(Channel ch, User actor, long inviteId) {
        require(ch, actor, Permission.INVITE_MODERATORS);
        invites.findById(inviteId).filter(i -> i.getChannelId().equals(ch.getId())).ifPresent(i -> {
            i.setRevoked(true);
            invites.save(i);
            audit.record(ch.getId(), actor.getId(), "invite.revoke", "Invite link revoked");
        });
    }

    @Transactional
    public Joined accept(String token, User user) {
        Invite inv = invites.findByToken(token).orElseThrow(() -> new UserFacingException("err.invite_invalid"));
        Channel ch = channels.findById(inv.getChannelId()).orElseThrow(() -> new UserFacingException("err.channel_gone"));
        Optional<ChannelMember> existing = members.findByChannelIdAndUserId(ch.getId(), user.getId());
        if (existing.isPresent()) return new Joined(ch, existing.get().getRole(), true);
        if (!inv.usable(Instant.now())) throw new UserFacingException("err.invite_expired");
        inv.setUses(inv.getUses() + 1);
        invites.save(inv);
        members.save(new ChannelMember(ch.getId(), user.getId(), inv.getRole(), inv.getCreatedBy()));
        audit.record(ch.getId(), user.getId(), "member.join", user.displayName() + " joined as " + inv.getRole().label());
        return new Joined(ch, inv.getRole(), false);
    }

    @Transactional
    public void setNotify(Channel ch, User u, Boolean digest, Boolean nudges) {
        ChannelMember m = members.findByChannelIdAndUserId(ch.getId(), u.getId())
                .orElseThrow(() -> new UserFacingException("err.join_first"));
        if (digest != null) m.setNotifyDigest(digest);
        if (nudges != null) m.setNotifyNudges(nudges);
        members.save(m);
    }

    public List<User> recipients(Channel ch, boolean nudges) {
        List<ChannelMember> ms = nudges ? members.findAllByChannelIdAndNotifyNudgesTrue(ch.getId())
                : members.findAllByChannelIdAndNotifyDigestTrue(ch.getId());
        return users.findAllById(ms.stream().map(ChannelMember::getUserId).toList()).stream().filter(u -> !u.isBanned()).toList();
    }

    private String nameOf(long userId) { return users.findById(userId).map(User::displayName).orElse("user " + userId); }

    static String randomToken(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
