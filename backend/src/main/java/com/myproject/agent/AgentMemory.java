package com.myproject.agent;

import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static com.myproject.agent.AgentModels.*;

/** Single-process, bounded TTL memory. Ownership is checked on every access. */
@Component
public class AgentMemory {
    private final AgentConfig config;
    private final Clock clock;
    private final Map<String, Session> sessions = new HashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public AgentMemory(AgentConfig config) { this(config, Clock.systemUTC()); }
    AgentMemory(AgentConfig config, Clock clock) { this.config = config; this.clock = clock; }

    static final class Session {
        final String id = UUID.randomUUID().toString();
        final String owner;
        final List<Message> messages = new ArrayList<>();
        Instant touched;
        boolean busy;
        Session(String owner, Instant now) { this.owner = owner; touched = now; }
    }

    synchronized Session acquire(String owner, String id) {
        prune();
        Session session;
        if (id == null || id.isBlank()) {
            if (sessions.size() >= config.getMaxSessions()) {
                sessions.values().stream().filter(s -> !s.busy).min(Comparator.comparing(s -> s.touched))
                        .ifPresent(s -> sessions.remove(s.id));
                if (sessions.size() >= config.getMaxSessions()) throw new AgentException(429, "当前会话较多，请稍后重试");
            }
            session = new Session(owner, clock.instant());
            sessions.put(session.id, session);
        } else {
            session = requireOwned(owner, id);
        }
        if (session.busy) throw new AgentException(409, "此会话正在生成回复，请稍后重试");
        session.busy = true;
        session.touched = clock.instant();
        return session;
    }

    synchronized List<Message> history(Session session) { return List.copyOf(session.messages); }

    synchronized void commit(Session session, String question, String answer) {
        session.messages.add(new Message("user", question));
        session.messages.add(new Message("assistant", answer));
        int excess = session.messages.size() - config.getMaxHistoryMessages();
        if (excess > 0) session.messages.subList(0, excess).clear();
        session.touched = clock.instant();
    }

    synchronized void release(Session session) {
        session.busy = false;
        session.touched = clock.instant();
        // A failed first turn must not consume memory capacity or leave an unreachable session.
        if (session.messages.isEmpty()) sessions.remove(session.id);
    }

    public synchronized SessionView view(String owner, String id) {
        prune();
        Session session = requireOwned(owner, id);
        session.touched = clock.instant();
        return new SessionView(session.id, List.copyOf(session.messages));
    }

    public synchronized void delete(String owner, String id) {
        prune();
        Session session = requireOwned(owner, id);
        if (session.busy) throw new AgentException(409, "请等待本轮回复完成后清除会话");
        sessions.remove(session.id);
    }

    private Session requireOwned(String owner, String id) {
        Session session = sessions.get(id);
        if (session == null || !session.owner.equals(owner)) {
            throw new AgentException(404, "会话不存在或已过期，请开启新对话");
        }
        return session;
    }

    private void prune() {
        Instant expiry = clock.instant().minus(Duration.ofMinutes(config.getSessionTtlMinutes()));
        sessions.values().removeIf(s -> !s.busy && !s.touched.isAfter(expiry));
    }
}
