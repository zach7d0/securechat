package com.securechat.user;

import com.securechat.contracts.EventCodec;
import com.securechat.contracts.EventEnvelope;
import com.securechat.contracts.Topics;
import com.securechat.contracts.events.UserRegistered;
import com.securechat.support.kafka.Consumed;
import com.securechat.support.outbox.ProcessedEvents;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates the profile for a newly registered account (deduplicated by event id). */
@Component
class UserRegisteredListener {
    private final ProfileService profiles;
    private final ProcessedEvents processed;

    UserRegisteredListener(ProfileService profiles, ProcessedEvents processed) {
        this.profiles = profiles;
        this.processed = processed;
    }

    @KafkaListener(topics = Topics.USER_REGISTERED, groupId = "user-service")
    @Transactional
    public void onRegistered(String json) {
        EventEnvelope env = Consumed.parse(json);
        if (!processed.markIfNew(env.eventId(), env.eventType())) {
            return;
        }
        UserRegistered e = EventCodec.payload(env, UserRegistered.class);
        profiles.applyRegistration(e.userId(), e.username(), e.displayName());
    }
}
