package com.halty.gramalyzerbot.workspace;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.CopyMessage;
import org.telegram.telegrambots.meta.api.methods.CopyMessages;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.MessageId;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.halty.gramalyzerbot.workspace.Models.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** What the queue sends to Telegram for text, picture and album posts. */
class LiveTelegramGatewayTest {

    private final TelegramClient client = mock(TelegramClient.class);
    private final WorkspaceMedia files = mock(WorkspaceMedia.class);
    private final LiveTelegramGateway gateway = new LiveTelegramGateway(client, files);
    private final Channel channel = new Channel("c", "Test", "@test", "Te", "purple", 0, 0, 0, 0, "", false, "UTC", true, false, "— Test", -100L);

    private static Message message(int id) { Message m = new Message(); m.setMessageId(id); return m; }

    private Post post(String body, List<MediaRef> media, List<LinkButton> buttons, boolean pin) {
        return new Post("p", "c", "t", body, "scheduled", Instant.now(), 0, 0, 0, "Community", "sending", null, null, media, buttons, true, pin, List.of());
    }

    private MediaRef stored(String id, String kind) {
        MediaRef ref = new MediaRef(id, kind, id + ".bin");
        when(files.file(id)).thenReturn(Optional.of(new WorkspaceMedia.File(ref, "image/jpeg", new byte[]{1, 2, 3})));
        return ref;
    }

    @Test
    void textWithButtonsIsSentSilently() throws Exception {
        when(client.execute(any(SendMessage.class))).thenReturn(message(5));
        long id = gateway.publish(channel, post("Hello", List.of(), List.of(new LinkButton("Shop", "https://shop.example")), false));
        assertThat(id).isEqualTo(5);
        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(sent.capture());
        assertThat(sent.getValue().getText()).isEqualTo("Hello\n\n— Test");
        assertThat(sent.getValue().getDisableNotification()).isTrue();
        assertThat(sent.getValue().getReplyMarkup().toString()).contains("https://shop.example");
        verify(client, never()).execute(any(PinChatMessage.class));
    }

    @Test
    void aPictureCarriesTheCaptionAndIsPinned() throws Exception {
        when(client.execute(any(SendPhoto.class))).thenReturn(message(9));
        long id = gateway.publish(channel, post("Look", List.of(stored("a", "photo")), List.of(), true));
        assertThat(id).isEqualTo(9);
        ArgumentCaptor<SendPhoto> sent = ArgumentCaptor.forClass(SendPhoto.class);
        verify(client).execute(sent.capture());
        assertThat(sent.getValue().getCaption()).isEqualTo("Look\n\n— Test");
        verify(client).execute(any(PinChatMessage.class));
    }

    @Test
    void severalFilesGoOutAsOneAlbum() throws Exception {
        when(client.execute(any(SendMediaGroup.class))).thenReturn(new ArrayList<>(List.of(message(20), message(21))));
        long id = gateway.publish(channel, post("", List.of(stored("a", "photo"), stored("b", "video")), List.of(), false));
        assertThat(id).isEqualTo(20);
        ArgumentCaptor<SendMediaGroup> sent = ArgumentCaptor.forClass(SendMediaGroup.class);
        verify(client).execute(sent.capture());
        assertThat(sent.getValue().getMedias()).hasSize(2);
        assertThat(sent.getValue().getMedias().getFirst().getCaption()).isEqualTo("— Test");
        assertThat(sent.getValue().getMedias().get(1).getType()).isEqualTo("video");
    }

    private static MessageId copied(long id) { MessageId m = new MessageId(); m.setMessageId(id); return m; }

    private Post repost(List<Integer> of) {
        return new Post("r", "c", "t", "", "scheduled", Instant.now(), 0, 0, 0, "Evergreen", "sending", null, null, List.of(), List.of(), true, false, of);
    }

    @Test
    void aRepostCopiesTheOldMessageWithoutTheSignature() throws Exception {
        when(client.execute(any(CopyMessage.class))).thenReturn(copied(77));
        assertThat(gateway.publish(channel, repost(List.of(12)))).isEqualTo(77);
        ArgumentCaptor<CopyMessage> sent = ArgumentCaptor.forClass(CopyMessage.class);
        verify(client).execute(sent.capture());
        assertThat(sent.getValue().getMessageId()).isEqualTo(12);
        assertThat(sent.getValue().getFromChatId()).isEqualTo("-100");
        assertThat(sent.getValue().getDisableNotification()).isTrue();
        verify(client, never()).execute(any(SendMessage.class));
    }

    @Test
    void anAlbumRepostCopiesEveryPart() throws Exception {
        when(client.execute(any(CopyMessages.class))).thenReturn(new ArrayList<>(List.of(copied(80), copied(81))));
        assertThat(gateway.publish(channel, repost(List.of(30, 31)))).isEqualTo(80);
        ArgumentCaptor<CopyMessages> sent = ArgumentCaptor.forClass(CopyMessages.class);
        verify(client).execute(sent.capture());
        assertThat(sent.getValue().getMessageIds()).containsExactly(30, 31);
    }
}
