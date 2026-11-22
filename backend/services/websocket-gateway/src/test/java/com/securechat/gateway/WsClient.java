package com.securechat.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;

/** Minimal JDK WebSocket test client collecting frames and the close code. */
final class WsClient implements WebSocket.Listener {
    final BlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
    final CompletableFuture<Integer> closed = new CompletableFuture<>();
    private final StringBuilder partial = new StringBuilder();
    WebSocket ws;

    static WsClient connect(int port, String origin) {
        WsClient c = new WsClient();
        c.ws = HttpClient.newHttpClient().newWebSocketBuilder().header("Origin", origin)
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), c).join();
        return c;
    }

    WsClient send(String json) {
        ws.sendText(json, true).join();
        return this;
    }

    WsClient auth(String token) {
        return send("{\"type\":\"auth\",\"token\":\"" + token + "\"}");
    }

    JsonNode await(String type, Duration timeout) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            JsonNode f = frames.poll(50, TimeUnit.MILLISECONDS);
            if (f != null && type.equals(f.path("type").asString())) {
                return f;
            }
        }
        return null;
    }

    int closeCode(Duration timeout) {
        try {
            return closed.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            frames.add(DownstreamStub.JSON.readTree(partial.toString()));
            partial.setLength(0);
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        closed.complete(statusCode);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        closed.complete(-2);
    }
}
