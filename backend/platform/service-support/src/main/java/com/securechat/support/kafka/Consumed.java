package com.securechat.support.kafka;

import com.securechat.contracts.EventCodec;
import com.securechat.contracts.EventEnvelope;
import com.securechat.support.web.Correlation;

/** Parses a consumed record and puts its correlation id into MDC for the listener's logs. */
public final class Consumed {
    private Consumed() {
    }

    public static EventEnvelope parse(String json) {
        EventEnvelope e = EventCodec.fromJson(json);
        Correlation.set(Correlation.sanitize(e.correlationId()));
        return e;
    }
}
