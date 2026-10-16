package com.halty.gramalyzerbot.channel;

/** Who may do what to whom inside a channel's team. Pure logic, no I/O. */
public final class TeamRules {

    private TeamRules() {}

    /** May {@code actor} invite someone as {@code role}? Owners can invite admins and moderators, admins only moderators. */
    public static boolean canInvite(Role actor, Role role) {
        if (role == Role.OWNER) return false;
        return role == Role.ADMIN ? actor.can(Permission.MANAGE_ADMINS) : actor.can(Permission.INVITE_MODERATORS);
    }

    /** May {@code actor} remove {@code target}? Only people strictly below you. */
    public static boolean canRemove(Role actor, Role target) {
        return target != Role.OWNER && actor.level() > target.level() && actor.can(Permission.INVITE_MODERATORS);
    }

    /** Promote moderator → admin, or demote admin → moderator: owner only. */
    public static boolean canChangeRole(Role actor, Role target, Role newRole) {
        if (!actor.can(Permission.MANAGE_ADMINS)) return false;
        if (target == Role.OWNER || newRole == Role.OWNER) return false;
        return target != newRole;
    }
}
