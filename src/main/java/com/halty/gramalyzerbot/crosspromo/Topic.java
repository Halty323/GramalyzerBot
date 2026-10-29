package com.halty.gramalyzerbot.crosspromo;

/** What a channel is about, for finding cross-promo partners. Topics in the same group are close enough to swap. */
public enum Topic {
    TECH(1), SCIENCE(1), GAMING(1),
    BUSINESS(2), FINANCE(2), MARKETING(2),
    DESIGN(3), ART(3), PHOTO(3),
    EDUCATION(4), PARENTING(4),
    NEWS(5),
    LIFESTYLE(6), TRAVEL(6), FOOD(6), HEALTH(6), FASHION(6),
    ENTERTAINMENT(7), MUSIC(7), SPORTS(7), MOVIES(7),
    OTHER(0);

    private final int group;

    Topic(int group) { this.group = group; }

    /** 1 for the same topic, 0.5 for a related one, 0 otherwise ("other" is related to nothing but itself). */
    public double closeness(Topic other) {
        if (this == other) return 1;
        return group != 0 && group == other.group ? 0.5 : 0;
    }

    public static Topic parse(String s) {
        try {
            return s == null ? OTHER : valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
    }
}
