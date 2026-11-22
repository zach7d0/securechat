package com.securechat.testsupport;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Tiny JSON HTTP client for black-box integration tests; never throws on 4xx/5xx. */
public final class Http {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final RestClient client;

    public record Response(int status, JsonNode body) {
        public String code() {
            return body != null && body.has("code") ? body.get("code").asString() : null;
        }

        public String text(String field) {
            return body.get(field).asString();
        }
    }

    public Http(String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).defaultStatusHandler(HttpStatusCode::isError, (q, r) -> { })
                .build();
    }

    public Response get(String path, String bearer) {
        return call("GET", path, null, bearer, Map.of());
    }

    public Response post(String path, Object body, String bearer) {
        return call("POST", path, body, bearer, Map.of());
    }

    public Response put(String path, Object body, String bearer) {
        return call("PUT", path, body, bearer, Map.of());
    }

    public Response patch(String path, Object body, String bearer) {
        return call("PATCH", path, body, bearer, Map.of());
    }

    public Response delete(String path, String bearer) {
        return call("DELETE", path, null, bearer, Map.of());
    }

    public Response internal(String method, String path, Object body) {
        return call(method, path, body, null, Map.of("X-Internal-Token", Infra.INTERNAL_TOKEN));
    }

    public Response call(String method, String path, Object body, String bearer, Map<String, String> headers) {
        RestClient.RequestBodySpec spec = client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path)
                .headers(h -> {
                    if (bearer != null) {
                        h.set(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
                    }
                    headers.forEach(h::set);
                });
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(MAPPER.writeValueAsString(body));
        }
        return spec.exchange((req, res) -> {
            byte[] bytes = res.getBody().readAllBytes();
            JsonNode node = bytes.length == 0 ? null : MAPPER.readTree(bytes);
            return new Response(res.getStatusCode().value(), node);
        });
    }
}
