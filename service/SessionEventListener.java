package ch.uzh.ifi.hase.soprafs26.service;

import org.springframework.context.event.EventListener;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class SessionEventListener {

    public SessionEventListener(UserService userService) {
        this.userService = userService;
    }

    private final UserService userService;
    private static final Logger logger = LoggerFactory.getLogger(SessionEventListener.class);

    @EventListener
    public void handleSessionTermination(HttpSessionDestroyedEvent event) {

        // 1. Get the session from the event
        HttpSession session = event.getSession();

        // 2. Retrieve the "userId" attribute
        Long userId = (Long) session.getAttribute("userId");

        // 3. Call userService.deleteUser(id)
        if (userId != null) {
                logger.info("Cleaning up ghost user with ID: {}", userId);
            userService.deleteUser(userId);
        }
    }
}
