package ch.uzh.ifi.hase.soprafs26.websocket;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import ch.uzh.ifi.hase.soprafs26.service.DisconnectGraceService;
import ch.uzh.ifi.hase.soprafs26.service.WebSocketSessionService;

@Component
public class WebSocketChannelInterceptor implements ChannelInterceptor {

    private final WebSocketSessionService sessionService;
    private final DisconnectGraceService graceService;

    public WebSocketChannelInterceptor(WebSocketSessionService sessionService,
                                       DisconnectGraceService graceService) {
        this.sessionService = sessionService;
        this.graceService = graceService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String userIdStr = accessor.getFirstNativeHeader("userId");
        String sessionId = accessor.getSessionId();

        if (userIdStr != null && !userIdStr.equals("undefined") && sessionId != null) {
            try {
                Long userId = Long.parseLong(userIdStr);
                sessionService.pair(sessionId, userId);
                // Cancel a pending disconnect — this is a page refresh, not a real leave.
                graceService.cancel(userId);
            } catch (NumberFormatException e) {
                // invalid userId format, skip pairing
            }
        }

        return message;
    }
}
