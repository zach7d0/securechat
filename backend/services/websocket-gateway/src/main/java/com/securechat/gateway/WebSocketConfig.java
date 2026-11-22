package com.securechat.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
class WebSocketConfig implements WebSocketConfigurer {
    private final ChatSocketHandler handler;
    private final GatewayProperties props;

    WebSocketConfig(ChatSocketHandler handler, GatewayProperties props) {
        this.handler = handler;
        this.props = props;
    }

    /** Browser Origin must be the chat app (blocks cross-site WebSocket hijacking). */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws").setAllowedOrigins(props.allowedOrigins().toArray(String[]::new));
    }

    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean c = new ServletServerContainerFactoryBean();
        c.setMaxTextMessageBufferSize(props.maxFrameBytes());
        c.setMaxSessionIdleTimeout(120_000L);
        return c;
    }

    @Bean("gatewayGroupId")
    String gatewayGroupId() {
        return "wsgw-" + props.id();
    }
}
