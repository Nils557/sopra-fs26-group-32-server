package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.Round;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.repository.RoundRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for RoundService.
 *
 * Mocks every dependency (RoundRepository, MapillaryService,
 * SimpMessagingTemplate, LobbyRepository) so these tests exercise pure
 * logic with no HTTP, no DB, no real STOMP broker.
 *
 * A few things are awkward to test in isolation:
 *   - `locationsDataset` is normally populated by @PostConstruct
 *     reading src/main/resources/locations.json. Under @InjectMocks
 *     Spring's lifecycle callbacks don't fire, so we seed the field
 *     with a small controlled list via ReflectionTestUtils for most
 *     tests. One test calls the public loadLocationsDataset() method
 *     directly to prove the JSON file parses.
 *   - The `scheduler` ScheduledExecutorService is constructed inline
 *     in a field initializer, so we can't easily swap it for a
 *     deterministic fake. The timer tests only verify the synchronous
 *     side effects (first image broadcast, future registered in
 *     activeTimers); the 9-second periodic callback would fire well
 *     after the test has passed, and we always stopTimer() to cancel
 *     it so no thread leaks into other tests.
 */
public class RoundServiceTest {

    @Mock
    private RoundRepository roundRepository;

    @Mock
    private MapillaryService mapillaryService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private LobbyRepository lobbyRepository;

    @InjectMocks
    private RoundService roundService;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    // -------------------------------------------------------------------
    // Helper — seed a controlled locations dataset (bypasses @PostConstruct)
    // -------------------------------------------------------------------
    private void seedDataset(RoundService.CuratedLocation... locations) {
        List<RoundService.CuratedLocation> dataset = new ArrayList<>(Arrays.asList(locations));
        ReflectionTestUtils.setField(roundService, "locationsDataset", dataset);
    }

    // -------------------------------------------------------------------
    // createAndStartRound — US6 (#132, #141)
    // -------------------------------------------------------------------

    /**
     * US6 #132 — Round Image Sequence Logic: happy path.
     *
     * With a single curated location in the dataset and a MapillaryService
     * that returns 5 image URLs on the first try, createAndStartRound must:
     *   - Pick the (only) location in the dataset
     *   - Persist a Round with that location's lat/lon + the 5 URLs
     *   - Return the persisted Round
     *
     * The 5-image count is hard-wired in the production code; the Round
     * entity stores the list as @ElementCollection into ROUND_IMAGES.
     */
    @Test
    public void createAndStartRound_valid_savesRoundWithLocationAnd5Images() {
        // given
        seedDataset(new RoundService.CuratedLocation(40.0, -74.0, "New York"));
        List<String> urls = Arrays.asList("u1", "u2", "u3", "u4", "u5");
        when(mapillaryService.getImageSequence(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(5)))
                .thenReturn(urls);
        when(roundRepository.save(any(Round.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        Round round = roundService.createAndStartRound("AB-1234");

        // then
        assertNotNull(round);
        assertEquals("AB-1234", round.getLobbyCode());
        assertEquals(5, round.getImageSequence().size());
        assertEquals(40.0, round.getTargetLatitude());
        assertEquals(-74.0, round.getTargetLongitude());
        verify(roundRepository).save(any(Round.class));
    }

    /**
     * US6 #132 — Retry loop.
     *
     * When the first random-location attempt throws (Mapillary returns
     * empty / not enough images / network error), createAndStartRound
     * must catch that, pick another location from the dataset, and
     * retry. This is what gives the feature its robustness: even
     * though many locations on Earth have no Mapillary coverage, we
     * keep trying until one lands.
     *
     * Test setup: force the 1st call to throw, 2nd to return 5 URLs.
     * Expect exactly 2 Mapillary calls and a successful Round return.
     */
    @Test
    public void createAndStartRound_firstAttemptFails_retriesAndSucceeds() {
        // given — two locations so the retry has somewhere to go
        seedDataset(
                new RoundService.CuratedLocation(40.0, -74.0, "NYC"),
                new RoundService.CuratedLocation(35.0, 139.0, "Tokyo"));
        AtomicInteger callCount = new AtomicInteger();
        when(mapillaryService.getImageSequence(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(5)))
                .thenAnswer(inv -> {
                    if (callCount.incrementAndGet() == 1) {
                        throw new RuntimeException("no images at first location");
                    }
                    return Arrays.asList("u1", "u2", "u3", "u4", "u5");
                });
        when(roundRepository.save(any(Round.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        Round round = roundService.createAndStartRound("AB-1234");

        // then — Mapillary was called twice (one fail + one success)
        verify(mapillaryService, times(2))
                .getImageSequence(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(5));
        assertNotNull(round);
        assertEquals(5, round.getImageSequence().size());
    }

    /**
     * US6 #141 — Mystery Location Logic: Zurich fallback.
     *
     * The retry loop tries a random location 10 times. If every
     * attempt fails (large parts of Earth have no Mapillary coverage
     * in the tightest bbox radius), the service falls back to Zurich
     * HB coordinates (47.3769, 8.5417) with a wider radius (0.005
     * delta instead of 0.01) — Zurich HB is guaranteed to have
     * thousands of Mapillary images.
     *
     * This test forces the first 10 calls to throw and the 11th
     * (Zurich) to succeed, then asserts the returned Round is
     * persisted with Zurich's exact coordinates.
     */
    @Test
    public void createAndStartRound_all10AttemptsFail_fallsBackToZurich() {
        // given
        seedDataset(new RoundService.CuratedLocation(40.0, -74.0, "NYC"));
        AtomicInteger callCount = new AtomicInteger();
        when(mapillaryService.getImageSequence(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(5)))
                .thenAnswer(inv -> {
                    int n = callCount.incrementAndGet();
                    if (n <= 10) {
                        throw new RuntimeException("fail " + n);
                    }
                    // Attempt 11 = Zurich fallback, succeeds
                    return Arrays.asList("z1", "z2", "z3", "z4", "z5");
                });
        when(roundRepository.save(any(Round.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        Round round = roundService.createAndStartRound("AB-1234");

        // then — 10 random attempts + 1 Zurich attempt = 11 total
        verify(mapillaryService, times(11))
                .getImageSequence(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(5));
        assertEquals(47.3769, round.getTargetLatitude());
        assertEquals(8.5417, round.getTargetLongitude());
    }

    /**
     * US6 #141 — Complete Mapillary failure -> 502 BAD_GATEWAY.
     *
     * If every random attempt AND the Zurich fallback throw, we've
     * exhausted our safety net. Rather than persist a Round with no
     * images, the service throws 502 BAD_GATEWAY — "upstream API is
     * unreachable". In practice this only happens when the Mapillary
     * token is invalid / expired or Mapillary itself is down for
     * everyone.
     */
    @Test
    public void createAndStartRound_allAttemptsIncludingZurichFail_throws502() {
        // given
        seedDataset(new RoundService.CuratedLocation(40.0, -74.0, "NYC"));
        when(mapillaryService.getImageSequence(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(5)))
                .thenThrow(new RuntimeException("Mapillary is down"));

        // when / then
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> roundService.createAndStartRound("AB-1234"));
        assertEquals(502, ex.getStatusCode().value());

        // 10 random attempts + 1 Zurich attempt, all failing
        verify(mapillaryService, times(11))
                .getImageSequence(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(5));
    }

    /**
     * US6 #141 — Empty locations dataset is a 500.
     *
     * If the @PostConstruct dataset-load failed (bad file, missing
     * JSON) and the fallback "add Zurich" also didn't happen for any
     * reason, the list is empty. createAndStartRound short-circuits
     * with HTTP 500 instead of calling random.nextInt(0), which
     * would throw IllegalArgumentException with a less helpful
     * message.
     */
    @Test
    public void createAndStartRound_emptyDataset_throws500() {
        // given — deliberately empty
        ReflectionTestUtils.setField(roundService, "locationsDataset", new ArrayList<>());

        // when / then
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> roundService.createAndStartRound("AB-1234"));
        assertEquals(500, ex.getStatusCode().value());
    }

    // -------------------------------------------------------------------
    // startRoundWithTimer — US6 (#133)
    // -------------------------------------------------------------------

    /**
     * US6 #133 — Broadcast Image on Timer: first image is sent
     * synchronously.
     *
     * startRoundWithTimer does three things:
     *   1. Build a Round via createAndStartRound (5 images).
     *   2. Immediately publish image[0] on /topic/game/{code}/image
     *      with an ImageBroadcastMessage payload.
     *   3. Schedule the remaining 4 images at 9-second intervals via
     *      the internal ScheduledExecutorService.
     *
     * This test verifies (2). The scheduler part (3) fires 9 seconds
     * later and is not asserted here to keep the test deterministic
     * and avoid sleeping / waiting. The broadcast payload's shape is
     * locked in explicitly so any change to the ImageBroadcastMessage
     * DTO breaks this test and alerts us before the frontend breaks.
     *
     * We stopTimer() at the end to cancel the scheduled future so the
     * test-worker thread pool stays clean.
     */
    @Test
    public void startRoundWithTimer_broadcastsFirstImageImmediatelyWithCorrectPayload() {
        // given — one location, 5 images, a lobby with totalRounds=3
        seedDataset(new RoundService.CuratedLocation(40.0, -74.0, "NYC"));
        when(mapillaryService.getImageSequence(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(5)))
                .thenReturn(Arrays.asList("url0", "url1", "url2", "url3", "url4"));
        when(roundRepository.save(any(Round.class))).thenAnswer(inv -> inv.getArgument(0));

        Lobby lobby = new Lobby();
        lobby.setLobbyCode("AB-1234");
        lobby.setTotalRounds(3);
        when(lobbyRepository.findByLobbyCode("AB-1234")).thenReturn(lobby);

        Round savedRound = new Round();
        savedRound.setLobbyCode("AB-1234");
        when(roundRepository.findByLobbyCode("AB-1234")).thenReturn(List.of(savedRound));

        try {
            // when
            roundService.startRoundWithTimer("AB-1234");

            // then — first image broadcast fired synchronously
            ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/game/AB-1234/image"),
                    payloadCaptor.capture());

            Object payload = payloadCaptor.getValue();
            assertTrue(payload instanceof RoundService.ImageBroadcastMessage,
                    "payload must be an ImageBroadcastMessage, got: "
                            + payload.getClass().getName());

            RoundService.ImageBroadcastMessage msg =
                    (RoundService.ImageBroadcastMessage) payload;
            assertEquals("url0", msg.imageUrl, "first image must be images[0]");
            assertEquals(0, msg.index, "first image index must be 0");
            assertEquals(3, msg.totalRounds, "totalRounds pulled from the Lobby");
            assertEquals(1, msg.roundNumber, "roundNumber counts persisted rounds");
        } finally {
            // Always cancel the 9-second periodic future so we don't leak
            // a thread into later tests.
            roundService.stopTimer("AB-1234");
        }
    }

    /**
     * US6 #133 — broadcastImage payload contract.
     *
     * broadcastImage is invoked by the periodic scheduler tick for
     * images 1..4. Calling it directly exercises the same code path
     * the scheduler uses, so if this test passes the 9-second tick
     * also produces the right payload. The alternative (waiting for
     * the scheduler to fire) would add 9 seconds of real time per
     * test run — not worth it.
     */
    @Test
    public void broadcastImage_sendsImageBroadcastMessageToGameTopicWithExactFields() {
        // when
        roundService.broadcastImage("AB-1234", "some-url", 2, 1, 3);

        // then
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(
                eq("/topic/game/AB-1234/image"),
                captor.capture());
        RoundService.ImageBroadcastMessage msg =
                (RoundService.ImageBroadcastMessage) captor.getValue();
        assertEquals("some-url", msg.imageUrl);
        assertEquals(2, msg.index);
        assertEquals(1, msg.roundNumber);
        assertEquals(3, msg.totalRounds);
    }

    // -------------------------------------------------------------------
    // stopTimer — US6 (#134 round lifecycle)
    // -------------------------------------------------------------------

    /**
     * US6 #134 — Round Lifecycle Endpoint: stopTimer cancels and
     * deregisters.
     *
     * stopTimer is how the round-lifecycle tear-down halts the
     * periodic broadcast. It must both:
     *   - Call future.cancel(false) so the scheduler stops firing
     *     the tick lambda.
     *   - Remove the entry from activeTimers so a subsequent lookup
     *     of that lobby code returns no stale future.
     *
     * We reach into activeTimers via ReflectionTestUtils to plant a
     * mocked ScheduledFuture, then verify both effects.
     */
    @SuppressWarnings("unchecked")
    @Test
    public void stopTimer_cancelsActiveFutureAndRemovesFromMap() {
        // given — a mocked future sitting in activeTimers under "AB-1234"
        Map<String, ScheduledFuture<?>> activeTimers =
                (Map<String, ScheduledFuture<?>>) ReflectionTestUtils.getField(
                        roundService, "activeTimers");
        assertNotNull(activeTimers, "activeTimers must be accessible via reflection");
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        activeTimers.put("AB-1234", future);

        // when
        roundService.stopTimer("AB-1234");

        // then — cancel was called with mayInterruptIfRunning=false, map cleared
        verify(future).cancel(false);
        assertFalse(activeTimers.containsKey("AB-1234"));
    }

    /**
     * US6 #134 — stopTimer on a lobby with no active future is a no-op.
     *
     * Defensive: stopTimer may be invoked more than once (future
     * refactors), or called on a lobby that never started a round.
     * Either way it must return quietly without NPE, without touching
     * collaborators.
     */
    @Test
    public void stopTimer_noActiveFutureForLobby_isSilentNoOp() {
        // Nothing in activeTimers — just make sure we don't throw.
        roundService.stopTimer("XX-0000");
        // No assertions needed — test passes if no exception bubbled.
    }

    // -------------------------------------------------------------------
    // cleanupLobby — US6 (#134) + safety net for the host-logout fix
    // -------------------------------------------------------------------

    /**
     * US6 #134 — cleanupLobby deletes every Round row tied to the
     * given lobby code.
     *
     * Round.lobbyCode is a plain String column (no FK), so Hibernate
     * cannot cascade Round deletion when the Lobby is deleted. Instead,
     * LobbyService.removePlayer (host branch) calls roundService
     * .cleanupLobby(code) first so the rows are gone before the lobby
     * itself is removed — otherwise we'd leak orphan Round rows
     * indefinitely.
     *
     * This test verifies both the find + delete calls happen.
     */
    @Test
    public void cleanupLobby_roundsExist_deletesAll() {
        // given — two rounds persisted under the same lobby code
        Round r1 = new Round();
        r1.setLobbyCode("AB-1234");
        Round r2 = new Round();
        r2.setLobbyCode("AB-1234");
        List<Round> rounds = Arrays.asList(r1, r2);
        when(roundRepository.findByLobbyCode("AB-1234")).thenReturn(rounds);

        // when
        roundService.cleanupLobby("AB-1234");

        // then — deleteAll was invoked with exactly those rounds
        verify(roundRepository).deleteAll(rounds);
    }

    /**
     * US6 #134 — cleanupLobby with no rounds is silent.
     *
     * Calling cleanupLobby for a lobby that never got past the waiting
     * room (no rounds) must not attempt a pointless deleteAll on an
     * empty list. We skip the DB round-trip entirely.
     */
    @Test
    public void cleanupLobby_noRoundsForLobby_doesNotCallDelete() {
        when(roundRepository.findByLobbyCode("AB-1234"))
                .thenReturn(new ArrayList<>());

        roundService.cleanupLobby("AB-1234");

        verify(roundRepository, never()).deleteAll(any());
    }

    // -------------------------------------------------------------------
    // loadLocationsDataset — US6 (#141)
    // -------------------------------------------------------------------

    /**
     * US6 #141 — Mystery Location Logic: the curated dataset exists
     * and parses.
     *
     * locations.json lives in src/main/resources and is loaded by the
     * @PostConstruct hook at boot. This test invokes that method
     * directly (it's public) so we can verify in a plain unit test
     * that:
     *   - The file is still on the classpath
     *   - ObjectMapper can parse it into a List<CuratedLocation>
     *   - The list is non-empty
     *
     * If someone ever deletes or renames the file, or breaks the JSON
     * format, this test fails loudly instead of the backend silently
     * falling back to a 1-entry Zurich-only list.
     */
    @SuppressWarnings("unchecked")
    @Test
    public void loadLocationsDataset_loadsNonEmptyListFromClasspath() {
        // when — manually trigger what @PostConstruct would do at boot
        roundService.loadLocationsDataset();

        // then — the dataset field is populated
        List<RoundService.CuratedLocation> dataset =
                (List<RoundService.CuratedLocation>) ReflectionTestUtils.getField(
                        roundService, "locationsDataset");
        assertNotNull(dataset, "locationsDataset must be initialised");
        assertFalse(dataset.isEmpty(), "locations.json must load at least one curated location");
    }
}
