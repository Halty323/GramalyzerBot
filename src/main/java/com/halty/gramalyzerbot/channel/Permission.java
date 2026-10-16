package com.halty.gramalyzerbot.channel;

/** What a team member may do on a channel. Each permission needs a minimum role. */
public enum Permission {
    VIEW(Role.MODERATOR),
    MODERATE_POSTS(Role.MODERATOR),
    CHANGE_SETTINGS(Role.ADMIN),
    INVITE_MODERATORS(Role.ADMIN),
    IMPORT_DATA(Role.ADMIN),
    MANAGE_ADMINS(Role.OWNER),
    RESET_DATA(Role.OWNER),
    DISCONNECT(Role.OWNER);

    private final Role minimum;

    Permission(Role minimum) { this.minimum = minimum; }

    public Role minimum() { return minimum; }
}
