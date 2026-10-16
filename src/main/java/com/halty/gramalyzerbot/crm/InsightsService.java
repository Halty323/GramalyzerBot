package com.halty.gramalyzerbot.crm;

import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.SubscriberService;
import com.halty.gramalyzerbot.post.AnalysisService;
import com.halty.gramalyzerbot.workspace.Models;
import com.halty.gramalyzerbot.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/** Answers "what does the analysis say about this CRM channel?" for the website. */
@Service
@RequiredArgsConstructor
public class InsightsService {

    private final WorkspaceService workspace;
    private final CrmBridge bridge;
    private final CrmDemoData demoData;
    private final AnalysisService analysis;
    private final SubscriberService subscribers;

    /** @throws WorkspaceService.Problem (404) when the CRM has no such channel */
    public Insights forChannel(String workspaceChannelId) {
        Models.Channel crm = workspace.channel(workspaceChannelId);
        Optional<Channel> found = bridge.analyticsFor(crm);
        if (found.isEmpty()) found = demoData.ensure(crm);
        if (found.isEmpty()) return Insights.notConnected(crm.id(), crm.timezone());
        Channel ch = found.get();
        return Insights.of(crm.id(), ch, analysis.report(ch), subscribers.growth(ch, 30), Instant.now());
    }
}
