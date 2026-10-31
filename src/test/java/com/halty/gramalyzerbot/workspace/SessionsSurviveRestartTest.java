package com.halty.gramalyzerbot.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** A website sign-in is a signed cookie, so it outlives a restart of the server (a new WorkspaceAuth) with the same token. */
class SessionsSurviveRestartTest {

    @Test
    void aSignInOutlivesARestartButNotATokenChange() {
        WorkspaceAuth before = new WorkspaceAuth(false, "first-token", new ObjectMapper());
        String session = before.newSession(Instant.now().plusSeconds(3600));

        assertThat(new WorkspaceAuth(false, "first-token", new ObjectMapper()).validSession(session)).isTrue();
        assertThat(new WorkspaceAuth(false, "another-token", new ObjectMapper()).validSession(session)).isFalse();
    }

    @Test
    void expiredOrTamperedSessionsAreRefused() {
        WorkspaceAuth auth = new WorkspaceAuth(false, "first-token", new ObjectMapper());
        assertThat(auth.validSession(auth.newSession(Instant.now().minusSeconds(1)))).isFalse();
        String session = auth.newSession(Instant.now().plusSeconds(3600));
        String later = (Instant.now().getEpochSecond() + 999_999) + session.substring(session.indexOf('.'));
        assertThat(auth.validSession(later)).isFalse();
        assertThat(auth.validSession("garbage")).isFalse();
        assertThat(auth.validSession(null)).isFalse();
    }
}
