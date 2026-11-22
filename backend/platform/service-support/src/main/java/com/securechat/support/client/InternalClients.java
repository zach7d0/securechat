package com.securechat.support.client;

import com.securechat.support.security.InternalTokenFilter;
import com.securechat.support.web.Correlation;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClient for {@code /internal/**} calls: service credential, correlation id propagation and
 * short timeouts (callers fail closed on errors).
 */
public final class InternalClients {
    private InternalClients() {
    }

    public static RestClient create(RestClient.Builder builder, String baseUrl, String internalToken, Duration timeout) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) Math.min(timeout.toMillis(), 2000));
        rf.setReadTimeout((int) timeout.toMillis());
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(rf)
                .defaultHeader(InternalTokenFilter.HEADER, internalToken)
                .requestInterceptor((req, body, exec) -> {
                    req.getHeaders().set(Correlation.HEADER, Correlation.current());
                    return exec.execute(req, body);
                })
                .build();
    }
}
