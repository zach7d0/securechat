package com.securechat.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fake User/Message/Delivery/Presence/Conversation internal APIs (records every call). */
final class DownstreamStub {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final UUID FORBIDDEN_CONVERSATION = UUID.fromString("00000000-0000-0000-0000-00000000dead");
    final Map<UUID, String[]> devices = new ConcurrentHashMap<>();       // deviceId → [userId, status]
    final Map<UUID, List<UUID>> conversations = new ConcurrentHashMap<>(); // conversationId → members
    final List<JsonNode> submitted = new CopyOnWriteArrayList<>();
    final List<JsonNode> acks = new CopyOnWriteArrayList<>();
    final List<String> presenceCalls = new CopyOnWriteArrayList<>();
    final List<UUID> replays = new CopyOnWriteArrayList<>();
    final String url;

    DownstreamStub() throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", this::handle);
        s.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        s.start();
        url = "http://127.0.0.1:" + s.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        byte[] in = ex.getRequestBody().readAllBytes();
        JsonNode body = in.length == 0 ? null : JSON.readTree(in);
        String[] p = path.split("/");
        try {
            if (path.startsWith("/internal/v1/devices/") && path.endsWith("/seen")) {
                reply(ex, 204, null);
            } else if (path.startsWith("/internal/v1/devices/")) {
                String[] d = devices.get(UUID.fromString(p[4]));
                if (d == null) {
                    reply(ex, 404, "{\"code\":\"NOT_FOUND\"}");
                } else {
                    reply(ex, 200, "{\"id\":\"" + p[4] + "\",\"userId\":\"" + d[0] + "\",\"status\":\"" + d[1] + "\"}");
                }
            } else if (path.equals("/internal/v1/messages")) {
                submitted.add(body);
                if (body.get("conversationId").asString().equals(FORBIDDEN_CONVERSATION.toString())) {
                    reply(ex, 403, "{\"code\":\"NOT_A_MEMBER\",\"detail\":\"not a member\"}");
                } else {
                    reply(ex, 200, "{\"messageId\":\"" + UUID.randomUUID() + "\",\"createdAt\":\"" + Instant.now() + "\",\"status\":\"ACCEPTED\"}");
                }
            } else if (path.equals("/internal/v1/delivery/acks")) {
                acks.add(body);
                reply(ex, 204, null);
            } else if (path.equals("/internal/v1/delivery/replay")) {
                replays.add(UUID.fromString(body.get("deviceId").asString()));
                reply(ex, 204, null);
            } else if (path.startsWith("/internal/v1/presence/")) {
                presenceCalls.add(p[4] + ":" + (body.has("deviceId") ? body.get("deviceId").asString() : body.get("devices").size()));
                reply(ex, 204, null);
            } else if (path.matches("/internal/v1/conversations/[^/]+/authorize")) {
                List<UUID> members = conversations.getOrDefault(UUID.fromString(p[4]), List.of());
                String user = ex.getRequestURI().getQuery().replace("userId=", "");
                boolean member = members.stream().anyMatch(m -> m.toString().equals(user));
                reply(ex, 200, JSON.writeValueAsString(Map.of("member", member, "type", "GROUP", "membershipVersion", 1,
                        "memberUserIds", members)));
            } else if (path.matches("/internal/v1/users/[^/]+/peers")) {
                reply(ex, 200, "[]");
            } else {
                reply(ex, 404, "{}");
            }
        } catch (RuntimeException e) {
            reply(ex, 500, "{\"code\":\"INTERNAL_ERROR\"}");
        }
    }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, status == 204 ? -1 : b.length);
        if (status != 204) {
            ex.getResponseBody().write(b);
        }
        ex.close();
    }
}
