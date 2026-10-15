package com.halty.gramalyzerbot.bot.handler;

import com.halty.gramalyzerbot.user.User;
import com.halty.gramalyzerbot.user.UserRepositoryHandler;
import com.halty.gramalyzerbot.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.telegram.telegrambots.meta.api.objects.Update;



@Component
@ConditionalOnProperty(name="telegram.bot.enabled", havingValue="true")
@RequiredArgsConstructor
public class StartHandler {

    private final TelegramClient telegramClient;
    private final UserService userService;
    private final UserRepositoryHandler userRepositoryHandler;

    public void handle(Update update) {
        Long chatId = update.getMessage().getChatId();
        var from = update.getMessage().getFrom();

        User user = userService.getOrCreate(from.getId(), from.getUserName(), from.getFirstName());

        userRepositoryHandler.saveAs(user, update);

        KeyboardRow row = new KeyboardRow();
        row.add(KeyboardButton.builder()
                .text("Get random number")
                .build());

        ReplyKeyboardMarkup keyboard = ReplyKeyboardMarkup.builder()
                .keyboardRow(row)
                .resizeKeyboard(true)
                .build();

        SendPhoto message = SendPhoto.builder()
                .chatId(chatId)
                .photo(new InputFile("AgACAgIAAxkDAAOHarlXotrERhZ4-Eq5TfV3bdwErzQAAhsoaxvoushJ6wYzrsP-O-gBAAMCAAN3AAM9BA"))
                .caption("Welcome to Gramalyzer Bot, " + from.getFirstName() + "!")
                .replyMarkup(keyboard)
                .build();
        try {
            telegramClient.execute(message);

        } catch (Exception e) {
            throw new RuntimeException("Failed to send a response", e);
        }
    }
}