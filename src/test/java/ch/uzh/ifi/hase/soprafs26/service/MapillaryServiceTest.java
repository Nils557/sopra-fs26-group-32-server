package ch.uzh.ifi.hase.soprafs26.service;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for MapillaryService.
 *
 * MapillaryService owns the single dangerous external dependency in
 * the backend — a real HTTP call to the Mapillary Graph API. We mock
 * RestTemplate so tests don't hit the network, don't need a real
 * token, and don't flake based on Mapillary availability.
 *
 * Construction detail: MapillaryService instantiates its own
 * RestTemplate / ObjectMapper / Random in the no-arg constructor,
 * and receives the Mapillary token + base URL via @Value field
 * injection from Spring. Since we build the service with `new` here
 * (no Spring context), we use ReflectionTestUtils.setField to swap
 * in the mock RestTemplate and seed the @Value-annotated fields.
 */
public class MapillaryServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private MapillaryService service;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        service = new MapillaryService();
        // Swap the real RestTemplate the constructor just created for our mock
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        // Seed the @Value fields Spring would normally populate
        ReflectionTestUtils.setField(service, "accessToken", "test-token");
        ReflectionTestUtils.setField(service, "baseUrl", "https://graph.mapillary.com");
    }

    /**
     * US6 #131 — Mapillary API Integration, happy path.
     *
     * Given a well-formed Mapillary Graph API response containing ten
     * image records, getImageSequence must return exactly the number
     * of URLs requested (5). The implementation extracts all the
     * thumb_1024_url strings, shuffles them, and returns the first
     * `count` items — so we can't assert which specific URLs come
     * back, only that there are 5 and each is a valid URL from the
     * mocked payload.
     */
    @Test
    public void getImageSequence_validBbox_returnsRequestedCount() {
        // given — ten images in the mocked response, 5 requested
        String body = "{\"data\":["
        + "{\"thumb_1024_url\":\"u1\", \"sequence_id\":\"seq1\"},"
        + "{\"thumb_1024_url\":\"u2\", \"sequence_id\":\"seq2\"},"
        + "{\"thumb_1024_url\":\"u3\", \"sequence_id\":\"seq3\"},"
        + "{\"thumb_1024_url\":\"u4\", \"sequence_id\":\"seq4\"},"
        + "{\"thumb_1024_url\":\"u5\", \"sequence_id\":\"seq5\"},"
        + "{\"thumb_1024_url\":\"u6\", \"sequence_id\":\"seq6\"},"
        + "{\"thumb_1024_url\":\"u7\", \"sequence_id\":\"seq7\"},"
        + "{\"thumb_1024_url\":\"u8\", \"sequence_id\":\"seq8\" }," 
        + "{\"thumb_1024_url\":\"u9\", \"sequence_id\":\"seq9\"},"
        + "{\"thumb_1024_url\":\"u10\", \"sequence_id\":\"seq10\"}"
        + "]}";
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        // when
        List<String> urls = service.getImageSequence(0.0, 0.0, 1.0, 1.0, 5);

        // then — we got 5 URLs, all drawn from the mocked set u1..u10
        assertEquals(5, urls.size());
        urls.forEach(u -> assertTrue(u.matches("^u(10|[1-9])$"),
                "each returned URL must come from the mocked payload: " + u));
    }

    /**
     * US6 #131 — Token validation guard.
     *
     * When MAPILLARY_ACCESS_TOKEN is missing or blank (deploy
     * misconfiguration), the service must short-circuit with HTTP 500
     * BEFORE making an unauthenticated HTTP call. Without this guard
     * we would hammer Mapillary with invalid-token requests and
     * pollute their server logs.
     */
    @Test
    public void getImageSequence_blankToken_throws500AndDoesNotCallRestTemplate() {
        // given — token gets wiped after setup
        ReflectionTestUtils.setField(service, "accessToken", "");

        // when / then
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getImageSequence(0.0, 0.0, 1.0, 1.0, 5));
        assertEquals(500, ex.getStatusCode().value());

        // and critically: no HTTP call was made
        verify(restTemplate, org.mockito.Mockito.never())
                .exchange(any(String.class), eq(HttpMethod.GET),
                          any(HttpEntity.class), eq(String.class));
    }

    /**
     * US6 #131 — Not-enough-images branch.
     *
     * If Mapillary's response contains fewer images than the caller
     * requested, the service throws. RoundService.tryCreateRound
     * catches this (returns null) and retries with another location
     * from the curated dataset. Without this check, `subList(0, count)`
     * would blow up with IndexOutOfBoundsException.
     */
    @Test
    public void getImageSequence_notEnoughImages_throws() {
        // given — response has only 1 image, caller wants 5
        String body = "{\"data\":[{\"thumb_1024_url\":\"only-one\"}]}";
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        // when / then
        assertThrows(ResponseStatusException.class,
                () -> service.getImageSequence(0.0, 0.0, 1.0, 1.0, 5));
    }

    /**
     * US6 #131 — Empty-data branch.
     *
     * Mapillary sometimes returns `{"data":[]}` for a sparse bbox
     * (rural area with no coverage). The service must reject this
     * cleanly so RoundService can retry on another random location
     * instead of persisting a Round with zero images.
     */
    @Test
    public void getImageSequence_emptyData_throws() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"data\":[]}", HttpStatus.OK));

        assertThrows(ResponseStatusException.class,
                () -> service.getImageSequence(0.0, 0.0, 1.0, 1.0, 5));
    }

    /**
     * US6 #131 — Locale-safe URL formatting.
     *
     * Regression guard. getImageSequence formats the bbox query param
     * with `String.format(Locale.US, ...)` so that on a non-US JVM
     * (German, Swiss, etc.) the decimals stay as `.` and not `,`. If
     * that Locale.US argument ever gets dropped, the formatted URL
     * under a German locale becomes e.g.
     *   `...bbox=47,37690,8,54170,...`
     * which Mapillary rejects with 400. This test forces the default
     * locale to Germany for the duration of the call and asserts the
     * URL uses dots.
     *
     * Note: the sibling method getRandomImage has the same bug latent
     * today (it formats without Locale.US), but it is not wired into
     * any call path, so we don't test it here — the code-review
     * report already flagged it as dead code.
     */
    @Test
    public void getImageSequence_formatsBboxWithLocaleUS_evenOnGermanLocale() {
        Locale original = Locale.getDefault();
        try {
                Locale.setDefault(Locale.GERMANY);

        String body = "{\"data\":["
                + "{\"thumb_1024_url\":\"u1\", \"sequence_id\":\"seq1\"},"
                + "{\"thumb_1024_url\":\"u2\", \"sequence_id\":\"seq2\"},"
                + "{\"thumb_1024_url\":\"u3\", \"sequence_id\":\"seq3\"},"
                + "{\"thumb_1024_url\":\"u4\", \"sequence_id\":\"seq4\"},"
                + "{\"thumb_1024_url\":\"u5\", \"sequence_id\":\"seq5\"},"
                + "{\"thumb_1024_url\":\"u6\", \"sequence_id\":\"seq6\"},"
                + "{\"thumb_1024_url\":\"u7\", \"sequence_id\":\"seq7\"},"
                + "{\"thumb_1024_url\":\"u8\", \"sequence_id\":\"seq8\" }," 
                + "{\"thumb_1024_url\":\"u9\", \"sequence_id\":\"seq9\"},"
                + "{\"thumb_1024_url\":\"u10\", \"sequence_id\":\"seq10\"}"
                + "]}";
            when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET),
                    any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

            // when — call with decimal-heavy bbox coordinates
            service.getImageSequence(47.376900, 8.541700, 47.380000, 8.545000, 5);

            // then — capture the URL that was passed to RestTemplate
            ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
            verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.GET),
                    any(HttpEntity.class), eq(String.class));
            String url = urlCaptor.getValue();

            // The bbox parameter must have dot decimal separators, never
            // German-locale commas. A correctly-formatted URL contains
            // "bbox=47.376900,8.541700,..." — we just check for "47."
            // and the absence of "47," to keep the test robust against
            // small number-of-digits changes.
            assertTrue(url.contains("bbox="),
                    "URL should contain the bbox parameter: " + url);
            assertTrue(url.contains("47.") || url.contains("8."),
                    "bbox must use dot decimal separators: " + url);
            assertFalse(url.matches(".*bbox=\\d+,\\d{3,}.*"),
                    "bbox must not use comma decimal separators (German locale): " + url);
        } finally {
            // Always restore the original default locale — leaking it
            // would affect every subsequent test in the JVM.
            Locale.setDefault(original);
        }
    }
}
