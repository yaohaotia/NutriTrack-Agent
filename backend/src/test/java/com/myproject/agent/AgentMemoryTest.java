package com.myproject.agent;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentMemoryTest {
    @Test void isolatesOwnersAndChecksOwnershipOnDelete() {
        AgentMemory memory = new AgentMemory(new AgentConfig());
        var session = memory.acquire("alice", null);
        memory.commit(session, "my meal", "private answer"); memory.release(session);
        assertEquals(404, assertThrows(AgentException.class, () -> memory.view("bob", session.id)).status());
        assertEquals(404, assertThrows(AgentException.class, () -> memory.delete("bob", session.id)).status());
        assertEquals(2, memory.view("alice", session.id).messages().size());
    }

    @Test void keepsCompletePairsAndRejectsConcurrentTurns() {
        AgentConfig config = new AgentConfig(); config.setMaxHistoryMessages(2);
        AgentMemory memory = new AgentMemory(config);
        var first = memory.acquire("alice", null);
        assertEquals(409, assertThrows(AgentException.class, () -> memory.acquire("alice", first.id)).status());
        memory.commit(first, "old question", "old answer"); memory.release(first);
        var second = memory.acquire("alice", first.id);
        memory.commit(second, "new question", "new answer"); memory.release(second);
        var history = memory.view("alice", first.id).messages();
        assertEquals(2, history.size()); assertEquals("new question", history.get(0).content());
        assertEquals("assistant", history.get(1).role());
    }

    @Test void expiresIdleSessionsWithoutEvictingActiveSessions() {
        AgentConfig config = new AgentConfig(); config.setSessionTtlMinutes(1); config.setMaxSessions(1);
        MutableClock clock = new MutableClock(); AgentMemory memory = new AgentMemory(config, clock);
        var session = memory.acquire("alice", null);
        clock.now = clock.now.plusSeconds(61);
        assertEquals(429, assertThrows(AgentException.class, () -> memory.acquire("bob", null)).status());
        memory.commit(session, "q", "a"); memory.release(session);
        clock.now = clock.now.plusSeconds(61);
        assertEquals(404, assertThrows(AgentException.class, () -> memory.view("alice", session.id)).status());
        assertNotNull(memory.acquire("bob", null));
    }

    @Test void failedFirstTurnDoesNotLeaveSessionBehind() {
        AgentMemory memory = new AgentMemory(new AgentConfig());
        var session = memory.acquire("alice", null); memory.release(session);
        assertEquals(404, assertThrows(AgentException.class, () -> memory.view("alice", session.id)).status());
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-30T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
