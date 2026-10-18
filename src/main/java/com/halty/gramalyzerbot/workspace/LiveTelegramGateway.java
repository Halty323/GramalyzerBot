package com.halty.gramalyzerbot.workspace;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMemberCount;
import static com.halty.gramalyzerbot.workspace.Models.*;

@Component
@ConditionalOnProperty(name="telegram.bot.enabled",havingValue="true")
public class LiveTelegramGateway implements TelegramGateway {
    private final TelegramClient client;
    public LiveTelegramGateway(TelegramClient client) {this.client=client;}
    public long publish(Channel channel,Post post) throws Exception {
        String text=post.body()+(channel.signature().isBlank()?"":"\n\n"+channel.signature());
        if(text.length()>4096) throw new IllegalArgumentException("Message and signature exceed Telegram's limit.");
        return client.execute(SendMessage.builder().chatId(channel.telegramChatId()).text(text).build()).getMessageId();
    }
    public long subscribers(long chatId) throws Exception {return client.execute(GetChatMemberCount.builder().chatId(chatId).build());}
    public PhotoRef photo(long chatId) throws Exception {
        var photo=client.execute(GetChat.builder().chatId(chatId).build()).getPhoto();
        return photo==null?null:new PhotoRef(photo.getBigFileUniqueId(),photo.getBigFileId());
    }
    /** The client builds the file URL, which contains the bot token, so it never leaves this process. */
    public byte[] download(String fileId) throws Exception {
        try(var in=client.downloadFileAsStream(client.execute(GetFile.builder().fileId(fileId).build()))) {return in.readAllBytes();}
    }
}
