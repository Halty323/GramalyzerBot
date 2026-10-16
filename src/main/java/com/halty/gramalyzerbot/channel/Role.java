package com.halty.gramalyzerbot.channel;

/** A person's role on one channel. Higher level = more power. */
public enum Role {
    MODERATOR(1, "Moderator", "🔎"),
    ADMIN(2, "Admin", "🛡"),
    OWNER(3, "Owner", "👑");

    private final int level;
    private final String label;
    private final String icon;

    Role(int level, String label, String icon) {
        this.level = level;
        this.label = label;
        this.icon = icon;
    }

    public int level() { return level; }

    public String label() { return label; }

    public String icon() { return icon; }

    public boolean atLeast(Role other) { return level >= other.level; }

    public boolean can(Permission p) { return atLeast(p.minimum()); }

    public String badge() { return icon + " " + label; }
}
