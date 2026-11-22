package com.securechat.gateway;

import com.securechat.contracts.EventCodec;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.JsonNode;

/**
 * Internal gateway transport: Delivery Service publishes to {@code gw:{gatewayId}} after looking up
 * the owner in the registry; other gateways publish {@code evict}. Typing is broadcast on
 * {@code typing}. Pub/Sub is best effort — durability lives in Delivery's pending rows.
 */
@Configuration
class RouteListener {
    private static final Logger log = LoggerFactory.getLogger(RouteListener.class);

    @Bean
    RedisMessageListenerContainer routeContainer(RedisConnectionFactory cf, GatewayProperties props, LocalSessions sessions,
            io.micrometer.core.instrument.MeterRegistry meters) {
        RedisMessageListenerContainer c = new RedisMessageListenerContainer();
        c.setConnectionFactory(cf);
        MessageListener route = (Message m, byte[] pattern) -> {
            try {
                JsonNode f = EventCodec.mapper().readTree(new String(m.getBody(), StandardCharsets.UTF_8));
                UUID device = UUID.fromString(f.path("deviceId").asString());
                LocalSessions.Conn conn = sessions.device(device);
                switch (f.path("type").asString("")) {
                    case "deliver" -> {
                        if (conn != null) {
                            conn.send(f.get("frame").toString());
                            meters.counter("websocket.egress", "kind", "message").increment();
                        } else {
                            meters.counter("websocket.egress.miss").increment();
                        }
                    }
                    case "frame" -> {
                        if (conn != null) {
                            conn.send(f.get("frame").toString());
                        }
                    }
                    case "evict" -> {
                        if (conn != null && conn.connectionId.equals(f.path("connectionId").asString())) {
                            conn.close(ChatSocketHandler.REPLACED);
                        }
                    }
                    default -> log.debug("unknown route frame");
                }
            } catch (RuntimeException e) {
                log.warn("bad route frame: {}", e.getClass().getSimpleName());
            }
        };
        MessageListener typing = (Message m, byte[] pattern) -> {
            try {
                JsonNode f = EventCodec.mapper().readTree(new String(m.getBody(), StandardCharsets.UTF_8));
                UUID sender = UUID.fromString(f.path("userId").asString());
                ((tools.jackson.databind.node.ObjectNode) f).remove("members");
                String frame = f.toString();
                for (JsonNode u : EventCodec.mapper().readTree(new String(m.getBody(), StandardCharsets.UTF_8)).path("members")) {
                    UUID member = UUID.fromString(u.asString());
                    if (!member.equals(sender)) {
                        sessions.ofUser(member).forEach(conn -> conn.send(frame));
                    }
                }
            } catch (RuntimeException e) {
                log.debug("bad typing frame");
            }
        };
        c.addMessageListener(route, new ChannelTopic(ConnectionRegistry.channel(props.id())));
        c.addMessageListener(typing, new ChannelTopic("typing"));
        return c;
    }
}
