package com.halty.gramalyzerbot.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import static com.halty.gramalyzerbot.workspace.Models.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:workspace;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","gramalyzer.demo=false","gramalyzer.api-token=test-only-workspace-token","telegram.bot.enabled=false"})
@AutoConfigureMockMvc
class WorkspaceIntegrationTests {
    @Autowired MockMvc http; @Autowired WorkspaceStore store; @Autowired WorkspaceService service; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper mapper;
    @MockitoBean TelegramGateway gateway;
    static final String AUTH="Bearer test-only-workspace-token";
    Channel channel;
    @BeforeEach void reset() {jdbc.update("DELETE FROM workspace_media"); jdbc.update("DELETE FROM channel_daily_metrics"); jdbc.update("DELETE FROM workspace_posts"); jdbc.update("DELETE FROM workspace_channels"); channel=service.registerChannel(-1001234L,"Test channel","test_channel",true);}
    String postJson(String status, Instant at) throws Exception {return mapper.writeValueAsString(Map.of("channelId",channel.id(),"title","Community update","body","Hello from the shared backend!","status",status,"scheduledAt",at.toString(),"category","Community"));}
    @Test void authenticatesBrowsersAndApiClients() throws Exception {
        http.perform(get("/api/workspace")).andExpect(status().isUnauthorized());
        http.perform(get("/api/workspace").header("Authorization",AUTH)).andExpect(status().isOk()).andExpect(jsonPath("$.demo").value(false));
        var login=http.perform(post("/api/login").contentType("application/json").content("{\"token\":\"test-only-workspace-token\"}")).andExpect(status().isOk()).andReturn();
        String cookie=login.getResponse().getHeader("Set-Cookie"); assertThat(cookie).contains("HttpOnly","SameSite=Strict");
        http.perform(get("/api/workspace").cookie(new Cookie("gramalyzer_session",cookie.split("[=;]")[1]))).andExpect(status().isOk());
        http.perform(post("/api/login").header("Origin","https://unrelated.example").contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
    @Test void sharedSettingsAreUsedByBothHttpAndBotServices() throws Exception {
        http.perform(patch("/api/channels/"+channel.id()).header("Authorization",AUTH).contentType("application/json").content("{\"paused\":true,\"signature\":\"Stay curious.\",\"timezone\":\"Europe/London\"}")).andExpect(status().isOk());
        Channel fromBot=service.channel(channel.id()); assertThat(fromBot.paused()).isTrue(); assertThat(fromBot.signature()).isEqualTo("Stay curious.");
        http.perform(patch("/api/channels/"+channel.id()).header("Authorization",AUTH).contentType("application/json").content("{\"timezone\":\"bad/zone\"}")).andExpect(status().isBadRequest());
        http.perform(patch("/api/channels/"+channel.id()).header("Authorization",AUTH).contentType("application/json").content("{\"subscribers\":99999}")).andExpect(status().isBadRequest());
    }
    @Test void savesEditsSchedulesAndDeletesPosts() throws Exception {
        String body=postJson("draft",Instant.now().plusSeconds(3600));
        var result=http.perform(post("/api/posts").header("Authorization",AUTH).contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn();
        String id=mapper.readTree(result.getResponse().getContentAsString()).path("id").asText();
        http.perform(patch("/api/posts/"+id).header("Authorization",AUTH).contentType("application/json").content(postJson("scheduled",Instant.now().plusSeconds(7200)))).andExpect(status().isOk());
        assertThat(service.post(id).status()).isEqualTo("scheduled");
        http.perform(delete("/api/posts/"+id).header("Authorization",AUTH)).andExpect(status().isNoContent());
        assertThat(store.post(id)).isEmpty();
        http.perform(post("/api/posts").header("Authorization",AUTH).contentType("application/json").content(postJson("scheduled",Instant.now().minusSeconds(60)))).andExpect(status().isBadRequest());
    }
    @Test void workerRespectsPauseAndRecordsReceiptExactlyOnce() throws Exception {
        TelegramGateway gateway=mock(TelegramGateway.class); when(gateway.publish(any(),any())).thenReturn(77L);
        Post p=store.insert(new Post("due",channel.id(),"Due post","Hello","scheduled",Instant.now().minusSeconds(60),0,0,0,"Community","pending",null,null));
        PostingWorker worker=new PostingWorker(store,gateway);
        service.settings(channel.id(),mapper.createObjectNode().put("paused",true)); worker.tick(); verifyNoInteractions(gateway);
        service.settings(channel.id(),mapper.createObjectNode().put("paused",false)); worker.tick(); worker.tick();
        verify(gateway,times(1)).publish(any(),any()); assertThat(service.post(p.id()).status()).isEqualTo("published"); assertThat(service.post(p.id()).telegramMessageId()).isEqualTo(77);
        service.channelPost(-1001234L,77,"Hello",Instant.now().getEpochSecond()); assertThat(store.posts()).hasSize(1);
    }
    @Test void uncertainDeliveryIsNotAutomaticallyRetriedOrEditable() throws Exception {
        TelegramGateway gateway=mock(TelegramGateway.class); when(gateway.publish(any(),any())).thenThrow(new java.io.IOException("Simulated connection interruption"));
        store.insert(new Post("uncertain",channel.id(),"Due post","Hello","scheduled",Instant.now().minusSeconds(60),0,0,0,"Community","pending",null,null));
        PostingWorker worker=new PostingWorker(store,gateway); worker.tick(); worker.tick(); verify(gateway,times(1)).publish(any(),any());
        assertThat(service.post("uncertain").deliveryState()).isEqualTo("uncertain");
        assertThatThrownBy(()->service.delete("uncertain")).isInstanceOf(WorkspaceService.Problem.class);
        assertThatThrownBy(()->service.save("uncertain",new PostInput(channel.id(),"Changed","Hello","scheduled",Instant.now().plusSeconds(3600),"Community"))).isInstanceOf(WorkspaceService.Problem.class);
    }
    @Test void telegramDiscoveryAndSubscriberHistoryUseTheSameDatabase() {
        service.registerChannel(-1001234L,"Renamed channel","renamed",true); assertThat(store.channels()).hasSize(1);
        store.recordSubscribers(channel.id(),1200); store.recordSubscribers(channel.id(),1250);
        assertThat(service.workspace().history()).hasSize(1); assertThat(service.channel(channel.id()).subscribers()).isEqualTo(1250);
        service.channelPost(-1001234L,21,"External post",Instant.now().getEpochSecond()); service.channelPost(-1001234L,21,"External post",Instant.now().getEpochSecond()); assertThat(store.posts()).hasSize(1);
    }
    @Test void servesChannelPhotosThroughTheBackend() throws Exception {
        when(gateway.photo(-1001234L)).thenReturn(new TelegramGateway.PhotoRef("photo-v1","file-1")); when(gateway.download("file-1")).thenReturn(new byte[]{(byte)0xFF,(byte)0xD8});
        String url="/api/channels/"+channel.id()+"/photo";
        http.perform(get(url)).andExpect(status().isUnauthorized());
        http.perform(get(url).header("Authorization",AUTH)).andExpect(status().isOk()).andExpect(content().contentType("image/jpeg")).andExpect(header().string("ETag","\"photo-v1\"")).andExpect(header().stringValues("Cache-Control","private, max-age=3600")).andExpect(content().bytes(new byte[]{(byte)0xFF,(byte)0xD8}));
        http.perform(get(url).header("Authorization",AUTH).header("If-None-Match","\"photo-v1\"")).andExpect(status().isNotModified());
        Channel unlinked=store.saveChannel(new Channel("unlinked","Demo","@demo","DE","#000",0,0,0,0,"",false,"UTC",true,false,"",null));
        http.perform(get("/api/channels/"+unlinked.id()+"/photo").header("Authorization",AUTH)).andExpect(status().isNotFound());
        http.perform(get("/api/channels/missing/photo").header("Authorization",AUTH)).andExpect(status().isNotFound());
        verify(gateway,times(1)).download(any());
    }
    @Test void atomicClaimsAndInterruptedRecoveryPreventBlindResends() {
        store.insert(new Post("interrupted",channel.id(),"Due","Hello","scheduled",Instant.now().minusSeconds(60),0,0,0,"Community","pending",null,null));
        assertThat(store.claim("interrupted")).isTrue(); assertThat(store.claim("interrupted")).isFalse(); store.recoverInterrupted();
        assertThat(service.post("interrupted").deliveryState()).isEqualTo("uncertain"); assertThat(store.due()).isEmpty();
    }
    String upload(String type, String name, byte[] data) throws Exception {
        var r=http.perform(post("/api/media").param("name",name).header("Authorization",AUTH).contentType(type).content(data)).andExpect(status().isCreated()).andReturn();
        return mapper.readTree(r.getResponse().getContentAsString()).path("id").asText();
    }
    String mediaPost(String body, java.util.List<String> media, java.util.List<Map<String,String>> buttons) throws Exception {
        var m=new java.util.HashMap<String,Object>(Map.of("channelId",channel.id(),"title","With pictures","body",body,"status","scheduled","scheduledAt",Instant.now().plusSeconds(3600).toString(),"silent",true,"pin",true));
        m.put("media",media.stream().map(id->Map.of("id",id,"kind","photo","name","x")).toList()); m.put("buttons",buttons);
        return mapper.writeValueAsString(m);
    }
    @Test void postsCanCarryPicturesVideosAndButtons() throws Exception {
        byte[] jpeg={(byte)0xFF,(byte)0xD8,(byte)0xFF,1,2,3};
        String photo=upload("image/jpeg","cover.jpg",jpeg), video=upload("video/mp4","clip.mp4",new byte[]{0,0,0,24}), gif=upload("image/gif","fun.gif",new byte[]{71,73,70});
        http.perform(get("/api/media/"+photo).header("Authorization",AUTH)).andExpect(status().isOk()).andExpect(content().contentType("image/jpeg")).andExpect(content().bytes(jpeg));
        http.perform(post("/api/media").header("Authorization",AUTH).contentType("application/pdf").content(new byte[]{1})).andExpect(status().isUnsupportedMediaType());

        var made=http.perform(post("/api/posts").header("Authorization",AUTH).contentType("application/json").content(mediaPost("",java.util.List.of(video),java.util.List.of(Map.of("text","Read more","url","https://example.com")))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.media[0].kind").value("video")).andExpect(jsonPath("$.buttons[0].text").value("Read more")).andExpect(jsonPath("$.pin").value(true)).andReturn();
        String id=mapper.readTree(made.getResponse().getContentAsString()).path("id").asText();
        // an older client (Studio) that knows nothing about media keeps them when it edits the text
        http.perform(patch("/api/posts/"+id).header("Authorization",AUTH).contentType("application/json").content(postJson("scheduled",Instant.now().plusSeconds(7200)))).andExpect(status().isOk());
        Post kept=service.post(id); assertThat(kept.media()).extracting(MediaRef::id).containsExactly(video); assertThat(kept.buttons()).hasSize(1); assertThat(kept.silent()).isTrue();

        // what Telegram can't do is refused before it's queued
        http.perform(post("/api/posts").header("Authorization",AUTH).contentType("application/json").content(mediaPost("Album",java.util.List.of(photo,video),java.util.List.of(Map.of("text","Go","url","https://example.com"))))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("album")));
        http.perform(post("/api/posts").header("Authorization",AUTH).contentType("application/json").content(mediaPost("Album",java.util.List.of(photo,gif),java.util.List.of()))).andExpect(status().isBadRequest());
        http.perform(post("/api/posts").header("Authorization",AUTH).contentType("application/json").content(mediaPost("x".repeat(1100),java.util.List.of(photo),java.util.List.of()))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("1024")));
        http.perform(post("/api/posts").header("Authorization",AUTH).contentType("application/json").content(mediaPost("",java.util.List.of(),java.util.List.of()))).andExpect(status().isBadRequest());
        http.perform(post("/api/posts").header("Authorization",AUTH).contentType("application/json").content(mediaPost("Hi",java.util.List.of("missing"),java.util.List.of()))).andExpect(status().isBadRequest());
        http.perform(post("/api/posts").header("Authorization",AUTH).contentType("application/json").content(mediaPost("Hi",java.util.List.of(),java.util.List.of(Map.of("text","Bad","url","javascript:alert(1)"))))).andExpect(status().isBadRequest());
        http.perform(post("/api/posts").header("Authorization",AUTH).contentType("application/json").content(mediaPost("A whole album",java.util.List.of(photo,video),java.util.List.of()))).andExpect(status().isCreated());
    }
    @Test void unusedUploadsAreRemovedAfterADay(@Autowired WorkspaceMedia files) throws Exception {
        String used=upload("image/png","a.png",new byte[]{1}), unused=upload("image/png","b.png",new byte[]{2});
        http.perform(post("/api/posts").header("Authorization",AUTH).contentType("application/json").content(mediaPost("Hi",java.util.List.of(used),java.util.List.of()))).andExpect(status().isCreated());
        jdbc.update("UPDATE workspace_media SET created_at=?",java.time.OffsetDateTime.now().minusDays(2));
        files.removeUnused();
        assertThat(files.ref(used)).isPresent(); assertThat(files.ref(unused)).isEmpty();
    }
}
