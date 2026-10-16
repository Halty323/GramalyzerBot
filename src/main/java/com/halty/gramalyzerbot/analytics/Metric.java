package com.halty.gramalyzerbot.analytics;

/**
 * What "doing well" means. The Bot API does not expose view counts, so the signals available are
 * reactions and (when a discussion group is linked) comments.
 */
public enum Metric {
    ENGAGEMENT("Balanced", "reactions + 3 × comments"),
    REACTIONS("Reactions", "reactions only"),
    COMMENTS("Comments", "comments only");

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
        };
    }
}
