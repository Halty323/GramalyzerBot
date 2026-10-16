package com.halty.gramalyzerbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * @param token              bot token from @BotFather (set TELEGRAM_BOT_TOKEN, never commit it)
 * @param adminIds           Telegram user ids that are bot administrators (BOT_ADMIN_IDS=1,2)
 * @param publicRegistration initial value of "anyone may connect a channel"; changeable later from /admin
 * @param defaultTimezone    timezone given to newly connected channels until the owner sets one
 */
@ConfigurationProperties(prefix = "telegram.bot")
public record BotProperties(String token, List<Long> adminIds, Boolean publicRegistration, String defaultTimezone) {

    public BotProperties {
        adminIds = adminIds == null ? List.of() : List.copyOf(adminIds);
        if (publicRegistration == null) publicRegistration = true;
        if (defaultTimezone == null || defaultTimezone.isBlank()) defaultTimezone = "UTC";
    }
}
