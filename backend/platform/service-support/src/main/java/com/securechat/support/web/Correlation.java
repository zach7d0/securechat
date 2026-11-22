package com.securechat.support.web;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/** Correlation id held in the logging MDC for the current request / record. */
public final class Correlation {
    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private Correlation() {
    }

    public static String current() {
        String c = MDC.get(MDC_KEY);
        return c != null ? c : UUID.randomUUID().toString();
    }

    /** Accepts a caller-supplied id only if it is short and log-safe; otherwise generates one. */
    public static String sanitize(String candidate) {
        return candidate != null && SAFE.matcher(candidate).matches() ? candidate : UUID.randomUUID().toString();
    }

    public static void set(String id) {
        MDC.put(MDC_KEY, id);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
