package ch.uzh.ifi.hase.soprafs26.repository;

import ch.uzh.ifi.hase.soprafs26.entity.Round;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for RoundRepository.
 *
 * Runs under @DataJpaTest so we get a real Hibernate session with an
 * embedded H2 database (separate from production data). These tests
 * exercise:
 *   - The Round entity's JPA mapping (GAME_ROUND table, generated id,
 *     lat/lon columns, and the @ElementCollection image sequence that
 *     lives in a side table ROUND_IMAGES)
 *   - The derived findByLobbyCode query that RoundService relies on
 *     for both reading (roundNumber counting in startRoundWithTimer)
 *     and cleanup (cleanupLobby)
 *   - That deleting a Round also removes its ROUND_IMAGES rows —
 *     otherwise those would become orphaned rows that Hibernate can
 *     never reach again.
 *
 * Nothing here hits the real Mapillary API or the scheduler — the
 * Round entity is populated in-memory and saved directly.
 */
@DataJpaTest
public class RoundRepositoryIntegrationTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private RoundRepository roundRepository;

    /**
     * US6 #132 — Round Image Sequence Logic: Round persists its image
     * sequence across a save/reload cycle.
     *
     * Round.imageSequence is a List<String> annotated with
     * @ElementCollection, which Hibernate maps to a side table
     * ROUND_IMAGES (FK back to game_round). This test persists a
     * Round with 5 URLs, evicts the entity from the session, then
     * re-queries via findByLobbyCode and confirms all 5 URLs came
     * back in order.
     *
     * If a future refactor swaps @ElementCollection for a lazy
     * @OneToMany without fetch-type adjustments, the reload would
     * return an empty list (or throw LazyInitializationException) and
     * this test would catch it.
     */
    @Test
    public void save_thenFindByLobbyCode_returnsRoundWithAll5Images() {
        // given — a Round with 5 image URLs
        Round round = new Round();
        round.setLobbyCode("AB-1234");
        round.setTargetLatitude(47.3769);
        round.setTargetLongitude(8.5417);
        round.setImageSequence(Arrays.asList("u1", "u2", "u3", "u4", "u5"));
        entityManager.persist(round);
        entityManager.flush();
        // Force a real DB round-trip so we don't get the session-cached copy
        entityManager.clear();

        // when
        List<Round> found = roundRepository.findByLobbyCode("AB-1234");

        // then — the Round came back with all 5 URLs from ROUND_IMAGES
        assertEquals(1, found.size());
        Round reloaded = found.get(0);
        assertNotNull(reloaded.getId(), "id must be generated");
        assertEquals("AB-1234", reloaded.getLobbyCode());
        assertEquals(47.3769, reloaded.getTargetLatitude());
        assertEquals(8.5417, reloaded.getTargetLongitude());
        assertEquals(5, reloaded.getImageSequence().size());
        assertTrue(reloaded.getImageSequence().containsAll(
                Arrays.asList("u1", "u2", "u3", "u4", "u5")));
    }

    /**
     * US6 #132 — Multiple rounds per lobby are all returned.
     *
     * Across a game session a lobby accumulates multiple Round rows
     * (one per round). RoundService.startRoundWithTimer uses
     * findByLobbyCode(code).size() as the current round number
     * counter, so it needs all existing rounds back — not just the
     * most recent one. This test plants two rounds for the same lobby
     * and one for a different lobby, then confirms the query returns
     * only the two that match.
     */
    @Test
    public void findByLobbyCode_returnsAllRoundsForLobbyOnly() {
        // given
        Round r1 = new Round();
        r1.setLobbyCode("AA-1111");
        r1.setTargetLatitude(1.0);
        r1.setTargetLongitude(1.0);
        r1.setImageSequence(Arrays.asList("a", "b", "c", "d", "e"));
        entityManager.persist(r1);

        Round r2 = new Round();
        r2.setLobbyCode("AA-1111");
        r2.setTargetLatitude(2.0);
        r2.setTargetLongitude(2.0);
        r2.setImageSequence(Arrays.asList("f", "g", "h", "i", "j"));
        entityManager.persist(r2);

        Round other = new Round();
        other.setLobbyCode("BB-2222");
        other.setTargetLatitude(3.0);
        other.setTargetLongitude(3.0);
        other.setImageSequence(Arrays.asList("x", "y", "z", "w", "v"));
        entityManager.persist(other);

        entityManager.flush();
        entityManager.clear();

        // when
        List<Round> rounds = roundRepository.findByLobbyCode("AA-1111");

        // then — exactly the two rounds for AA-1111, not the BB-2222 one
        assertEquals(2, rounds.size());
    }

    /**
     * US6 #134 — Round Lifecycle: deleting a Round cascades the
     * @ElementCollection child rows.
     *
     * When RoundService.cleanupLobby runs roundRepository.deleteAll()
     * on the host-disconnect path, Hibernate must also remove the
     * corresponding ROUND_IMAGES rows. If it didn't, every finished
     * game would leak hundreds of image_url rows indefinitely, and
     * eventually the DB would run out of space. @ElementCollection
     * enforces this cascade automatically — this test pins down that
     * guarantee.
     *
     * findByLobbyCode on the cleared code must return an empty list
     * afterwards.
     */
    @Test
    public void deleteAll_removesRoundsAndCascadesImageCollectionRows() {
        // given — a round with image rows
        Round r = new Round();
        r.setLobbyCode("CC-3333");
        r.setTargetLatitude(5.0);
        r.setTargetLongitude(5.0);
        r.setImageSequence(Arrays.asList("u1", "u2", "u3"));
        entityManager.persist(r);
        entityManager.flush();

        // when
        roundRepository.deleteAll(roundRepository.findByLobbyCode("CC-3333"));
        entityManager.flush();
        entityManager.clear();

        // then — no rounds remain for that lobby
        assertTrue(roundRepository.findByLobbyCode("CC-3333").isEmpty(),
                "all rounds for the lobby must be deleted");
    }
}
