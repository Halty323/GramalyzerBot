package com.halty.gramalyzerbot.bot;

import com.halty.gramalyzerbot.bot.ui.Btn;
import com.halty.gramalyzerbot.bot.ui.View;
import com.halty.gramalyzerbot.config.BotProperties;
import com.halty.gramalyzerbot.i18n.Lang;
import com.halty.gramalyzerbot.i18n.Tr;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMemberCount;
import org.telegram.telegrambots.meta.api.methods.groupadministration.LeaveChat;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Every call the bot makes to Telegram goes through here. Failures are logged and turned into empty
 * results, so one blocked user or deleted message never breaks a whole update (or a scheduled job).
 * <p>
 * When polling is switched off ({@code telegram.bot.enabled=false}, e.g. the demo profile) there is no
 * Telegram client; every call then fails the same quiet way, so the rest of the application – the CRM's
 * REST API in particular – can use the analytics code without a bot token.
 */
@Component
@Slf4j
public class BotApi {

    /**
     * The update types the bot consumes. message_reaction_count must be requested explicitly, on every poll:
     * an empty list (the library's default) tells Telegram to fall back to its default set, which has no reactions.
     * See {@link BotRegistration}.
     */
    static final List<String> ALLOWED_UPDATES = List.of(
            "message", "callback_query", "channel_post", "edited_channel_post", "my_chat_member", "message_reaction_count");

    public record ChatInfo(long id, String type, String title, String username, Long linkedChatId, String description) {}

    /** Null when Telegram polling is disabled. */
    private final TelegramClient client;
    private final BotProperties props;
    private final HttpClient http = HttpClient.newHttpClient();
    private volatile String botUsername;
    private volatile Long botId;

    public BotApi(ObjectProvider<TelegramClient> client, BotProperties props) {
        this.client = client.getIfAvailable();
        this.props = props;
    }

    /** False when Telegram polling is switched off. */
    public boolean enabled() { return client != null; }

    private TelegramClient client() {
        if (client == null) throw new IllegalStateException("Telegram is disabled (telegram.bot.enabled=false)");
        return client;
    }

    /** The commands in Telegram's menu, in order; their descriptions are {@code cmd.<name>} in each language. */
    static final List<String> COMMANDS = List.of("menu", "overview", "times", "content", "top", "watchlist", "track", "settings",
            "team", "channels", "import", "language", "demo", "help", "cancel");

    /** Publishes the command menu in every language and learns who we are – after startup, so a slow Telegram never delays it. */
    @EventListener(ApplicationReadyEvent.class)
    void init() {
        if (client == null) return;
        for (Lang lang : Lang.values()) {
            try {
                List<BotCommand> commands = Tr.in(lang, () -> COMMANDS.stream().map(c -> new BotCommand(c, Tr.t("cmd." + c))).toList());
                var request = SetMyCommands.builder().commands(commands);
                // English is the default for every language without its own list
                if (lang != Lang.EN) request.languageCode(lang.code());
                client().execute(request.build());
            } catch (Exception e) {
                log.warn("Could not publish the {} command menu: {}", lang.code(), e.getMessage());
            }
        }
        botUsername();
    }

    // ------------------------------------------------------------------ identity

    public String botUsername() {
        if (botUsername == null) loadIdentity();
        return botUsername == null ? "" : botUsername;
    }

    public long botId() {
        if (botId == null) loadIdentity();
        return botId == null ? 0 : botId;
    }

    private synchronized void loadIdentity() {
        if (client == null) return;
        try {
            var me = client().execute(new GetMe());
            botUsername = me.getUserName();
            botId = me.getId();
        } catch (Exception e) {
            log.warn("getMe failed: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ sending

    public Optional<Integer> send(long chatId, View view) {
        try {
            var b = SendMessage.builder().chatId(String.valueOf(chatId)).text(view.html()).parseMode("HTML");
            if (!view.rows().isEmpty()) b.replyMarkup(inline(view));
            return Optional.of(client().execute(b.build()).getMessageId());
        } catch (Exception e) {
            log.debug("send to {} failed: {}", chatId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Sends a message that (re)sets the persistent keyboard at the bottom of the chat. */
    public Optional<Integer> sendWithKeyboard(long chatId, String html, List<List<String>> labels) {
        try {
            var b = SendMessage.builder().chatId(String.valueOf(chatId)).text(html).parseMode("HTML")
                    .replyMarkup(replyKeyboard(labels));
            return Optional.of(client().execute(b.build()).getMessageId());
        } catch (Exception e) {
            log.debug("send to {} failed: {}", chatId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Edits a message in place. If the message can't be edited any more (too old, deleted) the view is
     * sent as a new message instead, so a button press never ends in silence.
     */
    public void edit(long chatId, int messageId, View view) {
        try {
            var b = EditMessageText.builder().chatId(String.valueOf(chatId)).messageId(messageId)
                    .text(view.html()).parseMode("HTML");
            if (!view.rows().isEmpty()) b.replyMarkup(inline(view));
            client().execute(b.build());
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("message is not modified")) return;
            log.debug("edit {}:{} failed ({}), sending a new message", chatId, messageId, msg);
            send(chatId, view);
        }
    }

    /**
     * Sends a photo with a caption and the persistent keyboard. Pass a cached file id to avoid
     * re-uploading; returns the file id Telegram assigned so the caller can cache it.
     */
    public Optional<String> sendPhoto(long chatId, String cachedFileId, Supplier<InputStream> source, String fileName,
                                      String captionHtml, List<List<String>> replyKeyboard) {
        try {
            InputFile file = cachedFileId != null ? new InputFile(cachedFileId) : new InputFile(source.get(), fileName);
            var b = SendPhoto.builder().chatId(String.valueOf(chatId)).photo(file).caption(captionHtml).parseMode("HTML");
            if (replyKeyboard != null) b.replyMarkup(replyKeyboard(replyKeyboard));
            Message sent = client().execute(b.build());
            List<PhotoSize> sizes = sent.getPhoto();
            return sizes == null || sizes.isEmpty() ? Optional.empty() : Optional.of(sizes.get(sizes.size() - 1).getFileId());
        } catch (Exception e) {
            if (cachedFileId != null) {
                log.debug("cached photo failed, re-uploading: {}", e.getMessage());
                return sendPhoto(chatId, null, source, fileName, captionHtml, replyKeyboard);
            }
            log.debug("sendPhoto to {} failed: {}", chatId, e.getMessage());
            return Optional.empty();
        }
    }

    public void answer(String callbackId, String text, boolean alert) {
        try {
            var b = AnswerCallbackQuery.builder().callbackQueryId(callbackId);
            if (text != null) b.text(text).showAlert(alert);
            client().execute(b.build());
        } catch (Exception e) {
            log.debug("answerCallbackQuery failed: {}", e.getMessage());
        }
    }

    public void typing(long chatId) {
        try {
            client().execute(SendChatAction.builder().chatId(String.valueOf(chatId)).action("typing").build());
        } catch (Exception e) {
            log.debug("typing failed: {}", e.getMessage());
        }
    }

    public void delete(long chatId, int messageId) {
        try {
            client().execute(DeleteMessage.builder().chatId(String.valueOf(chatId)).messageId(messageId).build());
        } catch (Exception e) {
            log.debug("delete failed: {}", e.getMessage());
        }
    }

    public void leave(long chatId) {
        try {
            client().execute(LeaveChat.builder().chatId(String.valueOf(chatId)).build());
        } catch (Exception e) {
            log.debug("leave failed: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ lookups

    public Optional<ChatInfo> chat(long chatId) { return chat(String.valueOf(chatId)); }

    /** Accepts a numeric id or "@username". */
    public Optional<ChatInfo> chat(String idOrUsername) {
        try {
            var c = client().execute(GetChat.builder().chatId(idOrUsername).build());
            return Optional.of(new ChatInfo(c.getId(), c.getType(), c.getTitle(), c.getUserName(), c.getLinkedChatId(), c.getDescription()));
        } catch (Exception e) {
            log.debug("getChat {} failed: {}", idOrUsername, e.getMessage());
            return Optional.empty();
        }
    }

    /** "creator", "administrator", "member", "left", "kicked"… */
    public Optional<String> memberStatus(long chatId, long userId) {
        try {
            var m = client().execute(GetChatMember.builder().chatId(String.valueOf(chatId)).userId(userId).build());
            return Optional.ofNullable(m.getStatus());
        } catch (Exception e) {
            log.debug("getChatMember failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Whether the bot is an admin of the channel with the right to publish posts (needed by the CRM queue). */
    public boolean canPostTo(long chatId) {
        try {
            var m = client().execute(GetChatMember.builder().chatId(String.valueOf(chatId)).userId(botId()).build());
            return m instanceof ChatMemberAdministrator admin && Boolean.TRUE.equals(admin.getCanPostMessages());
        } catch (Exception e) {
            log.debug("getChatMember (self) failed: {}", e.getMessage());
            return false;
        }
    }

    public Optional<Integer> memberCount(long chatId) { return memberCount(String.valueOf(chatId)); }

    /** Accepts a numeric id or "@username"; public channels answer even when the bot isn't a member. */
    public Optional<Integer> memberCount(String idOrUsername) {
        try {
            return Optional.ofNullable(client().execute(GetChatMemberCount.builder().chatId(idOrUsername).build()));
        } catch (Exception e) {
            log.debug("getChatMemberCount failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Streams a file a user sent to the bot (Bot API limit: 20 MB). */
    public InputStream download(String fileId) throws IOException {
        try {
            var file = client().execute(GetFile.builder().fileId(fileId).build());
            URI uri = URI.create("https://api.telegram.org/file/bot" + props.token() + "/" + file.getFilePath());
            HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) throw new IOException("Telegram returned HTTP " + res.statusCode() + " for the file");
            return res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not download the file: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ keyboards

    private static InlineKeyboardMarkup inline(View view) {
        var b = InlineKeyboardMarkup.builder();
        for (List<Btn> row : view.rows()) {
            InlineKeyboardRow r = new InlineKeyboardRow();
            for (Btn btn : row) {
                var ib = InlineKeyboardButton.builder().text(btn.text());
                if (btn.isLink()) ib.url(btn.url());
                else ib.callbackData(btn.data());
                r.add(ib.build());
            }
            b.keyboardRow(r);
        }
        return b.build();
    }

    private static ReplyKeyboardMarkup replyKeyboard(List<List<String>> labels) {
        var b = ReplyKeyboardMarkup.builder();
        for (List<String> line : labels) {
            KeyboardRow row = new KeyboardRow();
            for (String label : line) row.add(KeyboardButton.builder().text(label).build());
            b.keyboardRow(row);
        }
        return b.resizeKeyboard(true).build();
    }
}
