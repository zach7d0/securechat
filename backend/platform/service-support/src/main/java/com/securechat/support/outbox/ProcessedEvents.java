package com.securechat.support.outbox;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Consumer-side deduplication: {@link #markIfNew} inserts the event id into {@code processed_events}
 * in the caller's transaction and returns false if it was already processed.
 */
public class ProcessedEvents {
    private final JdbcTemplate jdbc;

    public ProcessedEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean markIfNew(UUID eventId, String eventType) {
        return jdbc.update("INSERT INTO processed_events (event_id, event_type, processed_at) VALUES (?, ?, now()) ON CONFLICT DO NOTHING",
                eventId, eventType) == 1;
    }
}
