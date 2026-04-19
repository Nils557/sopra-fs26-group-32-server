package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.rest.dto.MapillaryGetDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Random;
import java.util.Locale;

@Service
public class MapillaryService {

    @Value("${mapillary.access-token}")
    private String accessToken;

    @Value("${mapillary.base-url}")
    private String baseUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Random random;

    public MapillaryService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.random = new Random();
    }

    public MapillaryGetDTO getRandomImage(double minLon, double minLat, double maxLon, double maxLat) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Mapillary API token is not configured.");
        }

        String url = String.format("%s/images?fields=id,thumb_1024_url,geometry&limit=20&bbox=%f,%f,%f,%f", 
                                    baseUrl, minLon, minLat, maxLon, maxLat);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "OAuth " + accessToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode data = root.path("data");

            if (data.isMissingNode() || !data.isArray() || data.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No Mapillary images found for the given location.");
            }

            int randomIndex = random.nextInt(data.size());
            JsonNode selectedImage = data.get(randomIndex);

            // Using MapillaryGetDTO to maintain naming consistency
            MapillaryGetDTO imageDTO = new MapillaryGetDTO();
            imageDTO.setImageUrl(selectedImage.path("thumb_1024_url").asText());
            
            JsonNode coordinates = selectedImage.path("geometry").path("coordinates");
            imageDTO.setLongitude(coordinates.get(0).asDouble());
            imageDTO.setLatitude(coordinates.get(1).asDouble());

            return imageDTO;

        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to fetch image from Mapillary API: " + e.getMessage());
        }
    }

    public List<String> getImageSequence(double minLon, double minLat, double maxLon, double maxLat, int count) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Mapillary API token not configured.");
        }

        String url = String.format(Locale.US, "%s/images?fields=id,thumb_1024_url&limit=10&bbox=%f,%f,%f,%f", 
                                    baseUrl, minLon, minLat, maxLon, maxLat);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "OAuth " + accessToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            JsonNode data = objectMapper.readTree(response.getBody()).path("data");

            if (data.isMissingNode() || !data.isArray() || data.size() < count) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not enough images found in the target area.");
            }

            // Extract all URLs into a list
            List<String> allUrls = new java.util.ArrayList<>();
            for (JsonNode node : data) {
                allUrls.add(node.path("thumb_1024_url").asText());
            }

            // Shuffle and pick the requested count
            java.util.Collections.shuffle(allUrls, random);
            return allUrls.subList(0, count);

        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to fetch image sequence: " + e.getMessage());
        }
    }
    
}