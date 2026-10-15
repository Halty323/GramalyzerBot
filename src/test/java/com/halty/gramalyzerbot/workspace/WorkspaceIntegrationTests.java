package com.halty.gramalyzerbot.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
    static final String AUTH="Bearer test-only-workspace-token";
    Channel channel;
    @BeforeEach void reset() {jdbc.update("DELETE FROM channel_daily_metrics"); jdbc.update("DELETE FROM workspace_posts"); jdbc.update("DELETE FROM workspace_channels"); channel=service.registerChannel(-1001234L,"Test channel","test_channel",true);}
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
    @Test void atomicClaimsAndInterruptedRecoveryPreventBlindResends() {
        store.insert(new Post("interrupted",channel.id(),"Due","Hello","scheduled",Instant.now().minusSeconds(60),0,0,0,"Community","pending",null,null));
        assertThat(store.claim("interrupted")).isTrue(); assertThat(store.claim("interrupted")).isFalse(); store.recoverInterrupted();
        assertThat(service.post("interrupted").deliveryState()).isEqualTo("uncertain"); assertThat(store.due()).isEmpty();
    }
}
