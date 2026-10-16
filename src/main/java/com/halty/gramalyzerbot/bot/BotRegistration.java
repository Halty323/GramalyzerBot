package com.halty.gramalyzerbot.bot;

import com.halty.gramalyzerbot.config.BotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.DefaultGetUpdatesGenerator;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Starts long polling once the application is fully up.
 * <p>
 * This replaces the Spring starter's automatic registration of {@code SpringLongPollingBot} beans. The starter
 * polls with an empty {@code allowed_updates} list, which Telegram reads as "the default set" – and that set
 * does not contain {@code message_reaction_count}, the update the analytics are built on.
 */
@Component
@ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class BotRegistration {

    private final TelegramBotsLongPollingApplication polling;
    private final BotProperties props;
    private final TelegramBot bot;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        try {
            polling.registerBot(props.token(), () -> TelegramUrl.DEFAULT_URL,
                    new DefaultGetUpdatesGenerator(BotApi.ALLOWED_UPDATES), bot);
        } catch (TelegramApiException e) {
            throw new IllegalStateException("Could not start Telegram long polling", e);
        }
        log.info("Telegram long polling started (updates: {})", BotApi.ALLOWED_UPDATES);
    }
}
