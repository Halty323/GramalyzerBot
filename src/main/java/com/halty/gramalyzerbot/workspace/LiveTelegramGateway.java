package com.halty.gramalyzerbot.workspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage;
import org.telegram.telegrambots.meta.api.methods.send.*;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMemberCount;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaVideo;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import static com.halty.gramalyzerbot.workspace.Models.*;

@Component
@ConditionalOnProperty(name="telegram.bot.enabled",havingValue="true")
public class LiveTelegramGateway implements TelegramGateway {
    private static final Logger log = LoggerFactory.getLogger(LiveTelegramGateway.class);
    private final TelegramClient client; private final WorkspaceMedia files;
    public LiveTelegramGateway(TelegramClient client,WorkspaceMedia files) {this.client=client;this.files=files;}
    /** Text, one picture/video/GIF with its caption, or an album (caption on the first item); then pins it if asked. Returns the (first) message id. */
    public long publish(Channel channel,Post post) throws Exception {
        String text=post.body()+(channel.signature().isBlank()?"":(post.body().isBlank()?"":"\n\n")+channel.signature());
        if(text.length()>(post.media().isEmpty()?4096:1024)) throw new IllegalArgumentException("Message and signature exceed Telegram's limit.");
        String chat=String.valueOf(channel.telegramChatId()); String caption=text.isBlank()?null:text; InlineKeyboardMarkup keyboard=keyboard(post.buttons());
        long id;
        if(post.media().isEmpty()) id=client.execute(SendMessage.builder().chatId(chat).text(text).replyMarkup(keyboard).disableNotification(post.silent()).build()).getMessageId();
        else if(post.media().size()==1) {
            MediaRef m=post.media().getFirst(); InputFile file=new InputFile(new ByteArrayInputStream(load(m)),m.name());
            id=switch(m.kind()) {
                case "video" -> client.execute(SendVideo.builder().chatId(chat).video(file).caption(caption).replyMarkup(keyboard).disableNotification(post.silent()).supportsStreaming(true).build()).getMessageId();
                case "animation" -> client.execute(SendAnimation.builder().chatId(chat).animation(file).caption(caption).replyMarkup(keyboard).disableNotification(post.silent()).build()).getMessageId();
                default -> client.execute(SendPhoto.builder().chatId(chat).photo(file).caption(caption).replyMarkup(keyboard).disableNotification(post.silent()).build()).getMessageId();
            };
        } else {
            List<InputMedia> album=new ArrayList<>();
            for(MediaRef m:post.media()) {
                InputMedia item="video".equals(m.kind())?new InputMediaVideo(""):new InputMediaPhoto("");
                item.setMedia(new ByteArrayInputStream(load(m)),m.name());
                if(album.isEmpty()&&caption!=null) item.setCaption(caption);
                album.add(item);
            }
            id=client.execute(SendMediaGroup.builder().chatId(chat).medias(album).disableNotification(post.silent()).build()).getFirst().getMessageId();
        }
        if(post.pin()) {
            try {client.execute(PinChatMessage.builder().chatId(chat).messageId((int)id).disableNotification(post.silent()).build());}
            catch(Exception e) {log.warn("Post {} was sent but could not be pinned: {}",post.id(),e.getMessage());}
        }
        return id;
    }
    private byte[] load(MediaRef m) {return files.file(m.id()).orElseThrow(()->new IllegalStateException("An attached file is missing: "+m.name())).data();}
    /** One link button per row, in the order given; null when there are none. */
    static InlineKeyboardMarkup keyboard(List<LinkButton> buttons) {
        if(buttons.isEmpty()) return null;
        List<InlineKeyboardRow> rows=new ArrayList<>();
        for(LinkButton b:buttons) rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder().text(b.text()).url(b.url()).build()));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
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
