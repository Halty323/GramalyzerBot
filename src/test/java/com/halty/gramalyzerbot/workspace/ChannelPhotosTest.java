package com.halty.gramalyzerbot.workspace;

import org.junit.jupiter.api.*;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChannelPhotosTest {
    TelegramGateway gateway=mock(TelegramGateway.class); Instant[] now={Instant.parse("2026-10-18T12:00:00Z")};
    ChannelPhotos photos=new ChannelPhotos(gateway,()->now[0]);
    @Test void downloadsOnceAndAgainOnlyWhenThePhotoChanges() throws Exception {
        when(gateway.photo(1L)).thenReturn(new TelegramGateway.PhotoRef("u1","f1")); when(gateway.download("f1")).thenReturn(new byte[]{1,2});
        assertThat(photos.photo(1L)).hasValueSatisfying(p->{assertThat(p.tag()).isEqualTo("u1"); assertThat(p.bytes()).containsExactly(1,2);});
        photos.photo(1L); verify(gateway,times(1)).photo(1L);
        now[0]=now[0].plus(ChannelPhotos.FRESH); photos.photo(1L);
        verify(gateway,times(2)).photo(1L); verify(gateway,times(1)).download(any());
        when(gateway.photo(1L)).thenReturn(new TelegramGateway.PhotoRef("u2","f2")); when(gateway.download("f2")).thenReturn(new byte[]{3});
        now[0]=now[0].plus(ChannelPhotos.FRESH); assertThat(photos.photo(1L)).hasValueSatisfying(p->assertThat(p.tag()).isEqualTo("u2"));
    }
    @Test void remembersChannelsWithoutAPhotoForAWhile() throws Exception {
        assertThat(photos.photo(2L)).isEmpty(); photos.photo(2L); verify(gateway,times(1)).photo(2L);
        now[0]=now[0].plus(ChannelPhotos.MISSING); photos.photo(2L); verify(gateway,times(2)).photo(2L);
    }
    @Test void keepsServingTheLastPhotoWhenTelegramFails() throws Exception {
        when(gateway.photo(3L)).thenReturn(new TelegramGateway.PhotoRef("u1","f1")); when(gateway.download("f1")).thenReturn(new byte[]{1});
        photos.photo(3L); when(gateway.photo(3L)).thenThrow(new java.io.IOException("Simulated outage"));
        now[0]=now[0].plus(ChannelPhotos.FRESH); assertThat(photos.photo(3L)).hasValueSatisfying(p->assertThat(p.tag()).isEqualTo("u1"));
        now[0]=now[0].plus(ChannelPhotos.RETRY); photos.photo(3L); verify(gateway,times(3)).photo(3L);
    }
    @Test void withoutABotThereAreNoPhotos() {assertThat(new ChannelPhotos(null,()->now[0]).photo(1L)).isEmpty();}
}
