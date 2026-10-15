package com.halty.gramalyzerbot.bot.handler;

import com.halty.gramalyzerbot.user.User;
import com.halty.gramalyzerbot.user.UserRepositoryHandler;
import com.halty.gramalyzerbot.user.UserService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import java.time.Instant;
import java.util.random.RandomGenerator;

@Component
@ConditionalOnProperty(name="telegram.bot.enabled", havingValue="true")
@RequiredArgsConstructor
public class RandomHandler {
    private final TelegramClient telegramClient;
    private final UserService userService;
    private final UserRepositoryHandler userRepositoryHandler;

    public void     handle(@NonNull Update update) {
        Long chatId = update.getMessage().getChatId();
        var from = update.getMessage().getFrom();
        // Pass correct arguments and delegate persistence entirely to UserService
        User user = userService.getOrCreate(from.getId(), from.getUserName(), from.getFirstName());

        userRepositoryHandler.saveAs(user, update);

        RandomGenerator randomGenerator = RandomGenerator.getDefault();
        int randomNumber = (int) (randomGenerator.nextDouble() * 100);

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("Random number: " + randomNumber + "! Sehr Gut!")
                .build();

        try {
            telegramClient.execute(message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send a response", e);
        }
    }
}
