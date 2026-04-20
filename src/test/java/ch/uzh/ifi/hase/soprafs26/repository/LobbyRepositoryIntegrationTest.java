package ch.uzh.ifi.hase.soprafs26.repository;

import ch.uzh.ifi.hase.soprafs26.constant.LobbyStatus;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for LobbyRepository.
 *
 * Runs under @DataJpaTest which spins up an embedded H2 database, slices
 * the Spring context to JPA only (no controllers, no services, no WS
 * broker), and rolls back every test at the end. That gives us a real
 * Hibernate session to verify:
 *   - The Lobby entity maps to the schema correctly
 *   - Derived query methods on LobbyRepository resolve as expected
 *   - The lobby_players join table is wired up (unidirectional @OneToMany)
 *
 * These tests underpin the REST endpoints (findByLobbyCode is used on
 * every lobby lookup) and the host-disconnect flow (findByPlayers_Id is
 * how LobbyService decides whether a user was a host/guest).
 */
@DataJpaTest
public class LobbyRepositoryIntegrationTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private LobbyRepository lobbyRepository;

    /**
     * US2 #61 — Lobby entity + repository.
     *
     * Persisting a Lobby with all the required non-null columns
     * (lobbyCode, maxPlayers, totalRounds, status, hostUserId) should
     * round-trip cleanly. findByLobbyCode must return the persisted row
     * when the code matches and null when it doesn't — the latter is the
     * signal LobbyService.generateLobbyCode uses to know a candidate code
     * is free.
     */
    @Test
    public void findByLobbyCode_returnsMatchOrNull() {
        // given — a user to be the host, and a persisted lobby
        User host = new User();
        host.setUsername("hostUser");
        entityManager.persist(host);

        Lobby lobby = new Lobby();
        lobby.setLobbyCode("AB-1234");
        lobby.setMaxPlayers(4);
        lobby.setTotalRounds(3);
        lobby.setStatus(LobbyStatus.WAITING);
        lobby.setHostUserId(host.getId());
        lobby.getPlayers().add(host);
        entityManager.persist(lobby);
        entityManager.flush();

        // when
        Lobby found = lobbyRepository.findByLobbyCode("AB-1234");
        Lobby notFound = lobbyRepository.findByLobbyCode("ZZ-0000");

        // then — every column survived the round-trip, unknown code returns null
        assertNotNull(found);
        assertEquals("AB-1234", found.getLobbyCode());
        assertEquals(4, found.getMaxPlayers());
        assertEquals(3, found.getTotalRounds());
        assertEquals(LobbyStatus.WAITING, found.getStatus());
        assertEquals(host.getId(), found.getHostUserId());
        assertNull(notFound);
    }

    /**
     * US3 #69 / US3 #70 — Derived query that powers handlePlayerDisconnect.
     *
     * findByPlayers_Id traverses the unidirectional @OneToMany
     * "Lobby.players" collection (backed by the lobby_players join table
     * on Postgres / H2) to locate the lobby a given user belongs to. The
     * disconnect flow uses this to decide whether to remove a single
     * player from the players list (guest case) or to tear down the
     * whole lobby (host case).
     *
     * The test covers both the positive case (user in lobby -> lobby
     * returned) and the negative case (unknown user id -> empty
     * Optional).
     */
    @Test
    public void findByPlayers_Id_returnsLobbyContainingUser() {
        // given — two users, both in the same lobby
        User host = new User();
        host.setUsername("host");
        entityManager.persist(host);

        User guest = new User();
        guest.setUsername("guest");
        entityManager.persist(guest);

        Lobby lobby = new Lobby();
        lobby.setLobbyCode("CD-5678");
        lobby.setMaxPlayers(4);
        lobby.setTotalRounds(3);
        lobby.setStatus(LobbyStatus.WAITING);
        lobby.setHostUserId(host.getId());
        lobby.getPlayers().add(host);
        lobby.getPlayers().add(guest);
        entityManager.persist(lobby);
        entityManager.flush();

        // when
        Optional<Lobby> foundForHost = lobbyRepository.findByPlayers_Id(host.getId());
        Optional<Lobby> foundForGuest = lobbyRepository.findByPlayers_Id(guest.getId());
        Optional<Lobby> notFound = lobbyRepository.findByPlayers_Id(9999L);

        // then
        assertTrue(foundForHost.isPresent());
        assertTrue(foundForGuest.isPresent());
        assertEquals("CD-5678", foundForHost.get().getLobbyCode());
        assertEquals("CD-5678", foundForGuest.get().getLobbyCode());
        assertFalse(notFound.isPresent());
    }

    /**
     * US2 #64 — Lobby configuration (rounds, max players, host id) is
     * persisted and retrievable.
     *
     * This is the positive-control test for the schema mapping: every
     * config field the host supplies at creation time must survive a
     * save -> findByLobbyCode round-trip unchanged. If a future
     * refactor drops one of these columns or swaps the type, this test
     * fails immediately instead of silently returning zeroed values to
     * the waiting-room page.
     */
    @Test
    public void save_persistsAllConfigColumns() {
        // given
        User host = new User();
        host.setUsername("config-host");
        entityManager.persist(host);

        Lobby lobby = new Lobby();
        lobby.setLobbyCode("EF-9012");
        lobby.setMaxPlayers(8);
        lobby.setTotalRounds(7);
        lobby.setStatus(LobbyStatus.WAITING);
        lobby.setHostUserId(host.getId());
        lobby.getPlayers().add(host);

        // when — persist then reload via the repository
        entityManager.persist(lobby);
        entityManager.flush();
        entityManager.clear(); // force a real DB read, not a session cache hit

        Lobby reloaded = lobbyRepository.findByLobbyCode("EF-9012");

        // then
        assertNotNull(reloaded);
        assertEquals(8, reloaded.getMaxPlayers());
        assertEquals(7, reloaded.getTotalRounds());
        assertEquals(host.getId(), reloaded.getHostUserId());
        assertEquals(LobbyStatus.WAITING, reloaded.getStatus());
    }
}
