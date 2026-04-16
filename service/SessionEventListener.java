package ch.uzh.ifi.hase.soprafs26.service;

import org.springframework.context.event.EventListener;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class SessionEventListener {

    private static final Logger logger = LoggerFactory.getLogger(SessionEventListener.class);

    @EventListener
    public void handleSessionTermination(HttpSessionDestroyedEvent event) {
        logger.info("HTTP session destroyed: {}", event.getSession().getId());
    }
}
