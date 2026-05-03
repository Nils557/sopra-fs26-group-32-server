package ch.uzh.ifi.hase.soprafs26.service;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private final Logger log = LoggerFactory.getLogger(LobbyService.class);

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
        if (accessToken == null || accessToken.isBlank() || accessToken.equals("<MAPILLARY_ACCESS_TOKEN>")) {
                    log.error("Mapillary API token is missing or invalid.");
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Mapillary API token is not configured.");
        }

        String url = String.format(Locale.US, "%s/images?fields=id,thumb_1024_url,sequence_id&limit=1000&bbox=%f,%f,%f,%f",
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

            List<JsonNode> nodeList = new java.util.ArrayList<>();
            data.forEach(nodeList::add);
            java.util.Collections.shuffle(nodeList, random);

            java.util.Set<String> seenSequences = new java.util.HashSet<>();
            List<String> finalUrls = new java.util.ArrayList<>();

            // PASS 1: Try to get 5 perfectly unique sequences
            for (JsonNode node : nodeList) {
                String seqId = node.path("sequence_id").asText();
                if (!seenSequences.contains(seqId)) {
                    seenSequences.add(seqId);
                    finalUrls.add(node.path("thumb_1024_url").asText());
                }
                if (finalUrls.size() == count) {
                    break;
                }
            }

            // PASS 2: THE FALLBACK (The "Stride" Method)
            if (finalUrls.size() < count) {
                log.warn("Only found {} unique sequences. Spacing out images to force variety.", finalUrls.size());
                int step = Math.max(1, nodeList.size() / count); 
                for (int i = 0; i < nodeList.size() && finalUrls.size() < count; i += step) {
                    String urlStr = nodeList.get(i).path("thumb_1024_url").asText();
                    if (!finalUrls.contains(urlStr)) {
                        finalUrls.add(urlStr);
                    }
                }
            }

            // PASS 3: The ultimate safety net
            if (finalUrls.size() < count) {
                log.error("CRITICAL: Area does not even contain {} total images. Only found {}.", count, finalUrls.size());
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not enough images found in the target area.");
            }

            return finalUrls;

        } catch (Exception e) {
            // If Pass 3 threw the exception, let it pass through directly!
            if (e instanceof ResponseStatusException) {
                throw (ResponseStatusException) e;
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to fetch image sequence: " + e.getMessage());
        }
    }
    
}