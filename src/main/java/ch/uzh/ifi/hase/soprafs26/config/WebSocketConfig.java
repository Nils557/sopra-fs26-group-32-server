package ch.uzh.ifi.hase.soprafs26.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import ch.uzh.ifi.hase.soprafs26.websocket.WebSocketChannelInterceptor;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketChannelInterceptor channelInterceptor;

    public WebSocketConfig(WebSocketChannelInterceptor channelInterceptor) {
        this.channelInterceptor = channelInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // The "Front Door": Where the client connects
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*") // This is the "Modern Wildcard"
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // The "Outbound" Lane: Messages from server -> clients (e.g., /topic/lobby/1)
        registry.enableSimpleBroker("/topic");
        
        // The "Inbound" Lane: Messages from client -> server (e.g., /app/join)
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Hires the "Bouncer" to check User IDs on connection
        registration.interceptors(channelInterceptor);
    }
}