package com.halty.gramalyzerbot.analytics;

/**
 * What "doing well" means: reactions and (when a discussion group is linked) comments, or views. The Bot API does not
 * expose view counts, so views are only known for public channels, read from their web preview.
 */
public enum Metric {
    ENGAGEMENT("Balanced", "reactions + 3 × comments"),
    REACTIONS("Reactions", "reactions only"),
    COMMENTS("Comments", "comments only"),
    VIEWS("Views", "views after 24 hours");

    private final String label;
    private final String formula;

    Metric(String label, String formula) {
        this.label = label;
        this.formula = formula;
    }

    public String label() { return label; }

    public String formula() { return formula; }

    public double score(int reactions, int comments, boolean commentsKnown) {
        int c = commentsKnown ? comments : 0;
        return switch (this) {
            case ENGAGEMENT -> reactions + 3.0 * c;
            case REACTIONS -> reactions;
            case COMMENTS -> c;
            case VIEWS -> 0;
        };
    }

    /** The score of one post; with {@link #VIEWS}, only posts whose day-one views are known count. */
    public double score(Sample s) {
        return this == VIEWS ? (s.views() == null ? 0 : s.views()) : score(s.reactions(), s.comments(), s.commentsKnown());
    }

    /** Whether a post can be scored at all: views are unknown for private channels and posts read before tracking began. */
    public boolean scores(Sample s) { return this != VIEWS || s.views() != null; }
}
