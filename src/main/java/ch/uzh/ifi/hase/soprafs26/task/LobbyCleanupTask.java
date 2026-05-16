package ch.uzh.ifi.hase.soprafs26.task;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import ch.uzh.ifi.hase.soprafs26.constant.LobbyStatus;
import ch.uzh.ifi.hase.soprafs26.constant.UserStatus;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.service.RoundService;

@Component
public class LobbyCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(LobbyCleanupTask.class);

    private final LobbyRepository lobbyRepository;
    private final UserRepository userRepository;
    private final RoundService roundService;

    public LobbyCleanupTask(LobbyRepository lobbyRepository, UserRepository userRepository, RoundService roundService) {
        this.lobbyRepository = lobbyRepository;
        this.userRepository = userRepository;
        this.roundService = roundService;
    }

    // Runs every hour (3,600,000 milliseconds)
    @Scheduled(fixedRate = 3600000)
    @Transactional
    public void cleanupStaleLobbies() {
        log.info("Running Garbage Collector: Cleaning up stale lobbies...");

        // Define "stale" as older than 2 hours
        Instant cutoffTime = Instant.now().minus(2, ChronoUnit.HOURS);

        // Fetch all ghost lobbies
        List<Lobby> staleLobbies = lobbyRepository.findByStatusAndCreatedAtBefore(LobbyStatus.WAITING, cutoffTime);

        if (staleLobbies.isEmpty()) {
            log.info("Garbage Collector finished. No stale lobbies found.");
            return;
        }

        for (Lobby lobby : staleLobbies) {
            String lobbyCode = lobby.getLobbyCode();
            log.info("Deleting stale ghost lobby: {}", lobbyCode);

            // 1. Delete associated rounds
            roundService.cleanupLobby(lobbyCode);

            // 2. Fix the Ghost User bug
            for (User player : lobby.getPlayers()) {
                player.setStatus(UserStatus.ONLINE);
                userRepository.save(player);
            }

            // 3. Sever database constraints and delete
            lobby.getPlayers().clear();
            lobbyRepository.saveAndFlush(lobby);
            lobbyRepository.delete(lobby);
        }

        log.info("Garbage Collector finished. Deleted {} stale lobbies.", staleLobbies.size());
    }
}