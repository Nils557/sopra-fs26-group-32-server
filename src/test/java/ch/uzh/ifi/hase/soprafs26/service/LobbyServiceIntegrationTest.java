package ch.uzh.ifi.hase.soprafs26.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.web.WebAppConfiguration;

import ch.uzh.ifi.hase.soprafs26.constant.UserStatus;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Integration tests for LobbyService.
 *
 * Runs against the full Spring context (real DB, real repositories, real
 * service wiring) so that database-level constraints — like the
 * lobby_players join table foreign keys — are actually exercised.
 */
@WebAppConfiguration
@SpringBootTest
public class LobbyServiceIntegrationTest {

    @Qualifier("lobbyRepository")
    @Autowired
    private LobbyRepository lobbyRepository;

    @Qualifier("userRepository")
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LobbyService lobbyService;

    @BeforeEach
    public void setup() {
        lobbyRepository.deleteAll();
        userRepository.deleteAll();
    }

    /**
     * Clean up after every test so we don't leak lobby_players rows into
     * downstream test classes. @SpringBootTest (unlike @DataJpaTest) does
     * NOT auto-rollback, so the second lobby created in the regression
     * test below would otherwise stay in the DB and break
     * UserServiceIntegrationTest.setup()'s userRepository.deleteAll()
     * with a foreign-key constraint violation.
     *
     * Order matters: delete lobbies first (which clears the lobby_players
     * join-table rows via the entity's collection wiring), then users.
     */
    @AfterEach
    public void tearDown() {
        lobbyRepository.deleteAll();
        userRepository.deleteAll();
    }

    /**
     * US12 #215 — Ghost Lobby Detachment regression test.
     *
     * Scenario:
     *   1. Host creates a lobby (status = WAITING).
     *   2. A second player joins the lobby.
     *   3. The host disconnects. The host-leaves branch of removePlayer
     *      deletes the lobby; the fix (issue #213) ensures every other
     *      player's status is reset to ONLINE and the join-table rows
     *      are cleared first, so no foreign-key references remain.
     *   4. The remaining player creates a brand-new lobby.
     *
     * Without the fix at LobbyService.java:145-151, step 4 throws
     * DataIntegrityViolationException because the lobby_players join
     * table (and on Postgres, the User row's lingering status) still
     * references the deleted lobby.
     *
     * MANUAL SABOTAGE: In LobbyService.removePlayer (host-leaves branch),
     * comment out the loop
     *     for (User player : lobby.getPlayers()) {
     *         player.setStatus(UserStatus.ONLINE);
     *         userRepository.save(player);
     *     }
     *     lobby.getPlayers().clear();
     * The second createLobby call below will throw
     * DataIntegrityViolationException, and assertDoesNotThrow fails.
     */
    @Test
    public void hostLeaves_remainingPlayerCreatesNewLobby_doesNotThrowDataIntegrityViolation() {
        // 1. Seed two users in the database
        User host = new User();
        host.setUsername("host");
        host.setStatus(UserStatus.ONLINE);
        host = userRepository.save(host);

        User guest = new User();
        guest.setUsername("guest");
        guest.setStatus(UserStatus.ONLINE);
        guest = userRepository.save(guest);
        userRepository.flush();

        // 2. Host creates a lobby
        Lobby hostLobby = new Lobby();
        hostLobby.setHostUserId(host.getId());
        hostLobby.setMaxPlayers(4);
        hostLobby.setTotalRounds(3);
        Lobby createdLobby = lobbyService.createLobby(hostLobby);
        String hostLobbyCode = createdLobby.getLobbyCode();
        assertNotNull(hostLobbyCode, "createLobby must return a lobby with a generated code");

        // 3. Guest joins the lobby
        lobbyService.joinLobby(hostLobbyCode, guest.getId());

        // 4. Host disconnects — triggers the host-leaves branch of removePlayer:
        //    - lobby is deleted
        //    - every remaining player is reset to ONLINE (the fix)
        //    - join-table rows are cleared
        lobbyService.handlePlayerDisconnect(host.getId());

        // Sanity check: the original lobby is gone
        assertNull(lobbyRepository.findByLobbyCode(hostLobbyCode),
                "host lobby must be deleted after the host disconnects");

        // 5. THE REGRESSION ASSERTION:
        //    the remaining player tries to create a brand-new lobby.
        //    Before the fix this threw DataIntegrityViolationException
        //    because lingering join-table rows still pointed at the deleted lobby.
        Long guestId = guest.getId();
        assertDoesNotThrow(() -> {
            Lobby secondLobby = new Lobby();
            secondLobby.setHostUserId(guestId);
            secondLobby.setMaxPlayers(4);
            secondLobby.setTotalRounds(3);
            lobbyService.createLobby(secondLobby);
        }, "remaining player must be able to create a new lobby after host leaves");

        // Bonus check: guest's status was correctly reset by the fix
        User refreshedGuest = userRepository.findById(guestId).orElseThrow();
        assertEquals(UserStatus.ONLINE, refreshedGuest.getStatus(),
                "guest's status must be ONLINE after host disconnect (proves the fix loop ran)");
    }
}
