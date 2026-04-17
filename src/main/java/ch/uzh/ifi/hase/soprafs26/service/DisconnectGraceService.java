package ch.uzh.ifi.hase.soprafs26.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

/**
 * Buffers WebSocket disconnects so a page refresh doesn't instantly tear the
 * lobby down. If the same userId reconnects within GRACE_SECONDS, the pending
 * disconnect handler is cancelled.
 */
@Service
public class DisconnectGraceService {

    private static final long GRACE_SECONDS = 8;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();
    private final Map<Long, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    public void scheduleDisconnect(Long userId, Runnable task) {
        if (userId == null) return;
        cancel(userId);
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                task.run();
            } finally {
                pending.remove(userId);
            }
        }, GRACE_SECONDS, TimeUnit.SECONDS);
        pending.put(userId, future);
    }

    public void cancel(Long userId) {
        if (userId == null) return;
        ScheduledFuture<?> existing = pending.remove(userId);
        if (existing != null) existing.cancel(false);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
