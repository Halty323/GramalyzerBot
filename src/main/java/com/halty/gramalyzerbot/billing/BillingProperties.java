package com.halty.gramalyzerbot.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Paid plans in the bot, billed as 30-day Telegram Stars subscriptions.
 *
 * @param enabled      when false nobody is limited by a plan and {@code /plan} says everything is free (local runs,
 *                     the demo, self-hosted copies); compose.yaml switches it on for live servers
 * @param creatorStars monthly price of {@link Plan#CREATOR} in Stars
 * @param proStars     monthly price of {@link Plan#PRO} in Stars
 */
@ConfigurationProperties(prefix = "gramalyzer.billing")
public record BillingProperties(boolean enabled, Integer creatorStars, Integer proStars) {

    public BillingProperties {
        if (creatorStars == null) creatorStars = 250;
        if (proStars == null) proStars = 750;
        if (creatorStars < 1 || proStars < 1 || creatorStars > 10_000 || proStars > 10_000)
            throw new IllegalArgumentException("gramalyzer.billing: prices must be between 1 and 10000 Stars");
    }

    /** The monthly price in Stars; 0 for {@link Plan#FREE}. */
    public int stars(Plan p) {
        return switch (p) {
            case FREE -> 0;
            case CREATOR -> creatorStars;
            case PRO -> proStars;
        };
    }
}
