package com.halty.gramalyzerbot.bot;

import com.halty.gramalyzerbot.crm.CrmBridge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.List;

/**
 * The single consumer of Telegram updates, shared by the CRM workspace and the analytics bot.
 * Each update is first shown to the CRM ({@link CrmBridge#observe}: channel discovery and channel posts), then
 * handed to {@link UpdateRouter}, which does all the analytics bot's work.
 * <p>
 * Only exists when {@code telegram.bot.enabled=true}; the demo profile and the tests never poll Telegram.
 * {@link BotRegistration} connects it to Telegram.
 */
@Component
@ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class TelegramBot implements LongPollingUpdateConsumer {

    private final UpdateMapper mapper;
    private final UpdateRouter router;
    private final CrmBridge crm;

    @Override
    public void consume(List<Update> updates) {
        for (Update update : updates) {
            // Two independent consumers: a failure in one must never starve the other, or the rest of the batch.
            try {
                crm.observe(update);
            } catch (Throwable t) {
                log.error("CRM could not process update {}", update.getUpdateId(), t);
            }
            try {
                mapper.map(update).ifPresent(router::route);
            } catch (Throwable t) {
                log.error("Failed to process update {}", update.getUpdateId(), t);
            }
        }
    }
}
