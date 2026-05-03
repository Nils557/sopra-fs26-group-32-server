package ch.uzh.ifi.hase.soprafs26.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.Locale;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LocationResult;

@Service
public class GeocodingService {
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Logger log = LoggerFactory.getLogger(GeocodingService.class);

    public GeocodingService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * US #144: Resolve coordinates into LocationResult (City + Country)
     */
    public LocationResult resolveLocation(double lat, double lon) {
        String url = String.format(Locale.US,
                "https://nominatim.openstreetmap.org/reverse?format=json&lat=%f&lon=%f&zoom=10&addressdetails=1",
                lat, lon);

        HttpHeaders headers = new HttpHeaders();
        // Nominatim requires identifying the app
        headers.set("User-Agent", "SopraGameApp/1.0 (group32@uzh.ch)"); 
        headers.set("Accept-Language", "en-US,en;q=0.9");
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode address = root.path("address");

            if (address.isMissingNode()) {
                return new LocationResult("Unknown City", "Unknown Country");
            }

            String city = address.path("city").asText(
                            address.path("town").asText(
                              address.path("village").asText("Unknown City")));
            
            String country = address.path("country").asText("Unknown Country");

            return new LocationResult(city, country);
        } catch (Exception e) {
            log.error("Geocoding failed for {}, {}: {}", lat, lon, e.getMessage());
            return new LocationResult("Unknown City", "Unknown Country");
        }
    }
}