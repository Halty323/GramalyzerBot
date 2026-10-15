package com.halty.gramalyzerbot.bot;

import com.halty.gramalyzerbot.bot.handler.RandomHandler;
import com.halty.gramalyzerbot.bot.handler.StartHandler;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.List;

@Component
@ConditionalOnProperty(name="telegram.bot.enabled", havingValue="true")
@RequiredArgsConstructor
public class TelegramBot implements SpringLongPollingBot, LongPollingUpdateConsumer {

    private final StartHandler startHandler;
    private final RandomHandler randomHandler;
    private final com.halty.gramalyzerbot.workspace.WorkspaceService workspace;

    @Value("${telegram.bot.token}")
    private String token;

    @Override
    public String getBotToken() {
        return token;
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(@NonNull List<Update> updates) {
        for (Update update : updates) {
            handleUpdate(update);
        }
    }

    private void handleUpdate(@NonNull Update update) {
        if (update.hasMyChatMember()) {
            var membership = update.getMyChatMember();
            var chat = membership.getChat();
            if ("channel".equals(chat.getType())) {
                var member = membership.getNewChatMember();
                boolean canPost = member instanceof org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator admin && Boolean.TRUE.equals(admin.getCanPostMessages());
                workspace.registerChannel(chat.getId(), chat.getTitle(), chat.getUserName(), canPost);
            }
            return;
        }
        if (update.hasChannelPost()) {
            var post = update.getChannelPost();
            workspace.channelPost(post.getChatId(), post.getMessageId(), post.getText() != null ? post.getText() : post.getCaption(), post.getDate());
            return;
        }
        if (!update.hasMessage() || !update.getMessage().hasText() || update.getMessage().getFrom() == null) return;

        String text = update.getMessage().getText();
        if (text.equals("/start")) {
            startHandler.handle(update);
        } else if (text.equals("/random") || text.equals("Get random number")) {
            randomHandler.handle(update);
        }
    }
}