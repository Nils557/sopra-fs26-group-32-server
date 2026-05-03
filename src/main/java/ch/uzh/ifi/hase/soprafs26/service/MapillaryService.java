package ch.uzh.ifi.hase.soprafs26.service;

import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.uzh.ifi.hase.soprafs26.rest.dto.MapillaryGetDTO;

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

        // 1. Ask for 'sequence_id' in the fields, and increase the limit to 50 so we have plenty to pick from
        String url = String.format(Locale.US, "%s/images?fields=id,thumb_1024_url,sequence_id&limit=50&bbox=%f,%f,%f,%f",
                                     baseUrl, minLon, minLat, maxLon, maxLat);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "OAuth " + accessToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            JsonNode data = objectMapper.readTree(response.getBody()).path("data");

            if (data.isMissingNode() || !data.isArray() || data.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No images found in the target area.");
            }

            // 2. Put all JSON nodes in a list and shuffle them FIRST.
            // This prevents us from always picking sequences from the same part of town.
            List<JsonNode> nodeList = new java.util.ArrayList<>();
            data.forEach(nodeList::add);
            java.util.Collections.shuffle(nodeList, random);

            // 3. Filter for unique sequences
            java.util.Set<String> seenSequences = new java.util.HashSet<>();
            List<String> finalUrls = new java.util.ArrayList<>();

            for (JsonNode node : nodeList) {
                String seqId = node.path("sequence_id").asText();
                
                // If we haven't seen this sequence yet, add the image!
                if (!seenSequences.contains(seqId)) {
                    seenSequences.add(seqId);
                    finalUrls.add(node.path("thumb_1024_url").asText());
                }

                // Stop as soon as we have the exact number we need (5)
                if (finalUrls.size() == count) {
                    break;
                }
            }

            // 4. Safety check: did we actually find enough unique sequences?
            if (finalUrls.size() < count) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not enough unique locations found.");
            }

            return finalUrls;

        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to fetch image sequence: " + e.getMessage());
        }
    }
    
}