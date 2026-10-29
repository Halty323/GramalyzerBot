package com.halty.gramalyzerbot.crm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The website's demo mode: sample channels get sample insights, and the CRM's own sample data stays exactly as it was. */
@ActiveProfiles("demo")
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:demoinsights;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class DemoInsightsTests {

    @Autowired MockMvc http;
    @Autowired ObjectMapper mapper;

    @Test
    void everyDemoChannelHasInsightsWithoutChangingTheWorkspace() throws Exception {
        String before = http.perform(get("/api/workspace")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (String id : new String[]{"design", "tech", "makers"}) {
            http.perform(get("/api/channels/" + id + "/insights")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.channelId").value(id))
                    .andExpect(jsonPath("$.status").value("ready"))
                    .andExpect(jsonPath("$.bestTimes[0].day").isString());
        }
        // asking twice reuses the same sample channel
        http.perform(get("/api/channels/tech/insights")).andExpect(jsonPath("$.status").value("ready"));
        String after = http.perform(get("/api/workspace")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(after)).isEqualTo(mapper.readTree(before));
    }

    @Test
    void demoChannelsHaveJoinsLeavesAndPromoLinks() throws Exception {
        http.perform(get("/api/channels/design/growth?days=30")).andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.tracking").value(true))
                .andExpect(jsonPath("$.joined").isNumber())
                .andExpect(jsonPath("$.daily.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(30)))
                .andExpect(jsonPath("$.gainers[0].joined").isNumber())
                .andExpect(jsonPath("$.links.length()").value(3))
                .andExpect(jsonPath("$.links[?(@.name == 'Shoutout in a partner channel')].costPerSubscriber").isNotEmpty());
        // links can be made and revoked from the website
        String made = http.perform(post("/api/channels/design/promo-links").contentType("application/json")
                        .content("{\"name\":\"Story in @friend\",\"cost\":12.5,\"currency\":\"$\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.joined").value(0)).andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(made).get("id").asLong();
        http.perform(delete("/api/channels/design/promo-links/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.revoked").value(true));
        http.perform(post("/api/channels/design/promo-links").contentType("application/json").content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("promo.err.name"));
    }
}
