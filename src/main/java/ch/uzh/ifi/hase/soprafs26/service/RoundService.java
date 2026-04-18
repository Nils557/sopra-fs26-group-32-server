package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.entity.Round;
import ch.uzh.ifi.hase.soprafs26.repository.RoundRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class RoundService {

    private final RoundRepository roundRepository;
    private final MapillaryService mapillaryService;

    @Autowired
    public RoundService(RoundRepository roundRepository, MapillaryService mapillaryService) {
        this.roundRepository = roundRepository;
        this.mapillaryService = mapillaryService;
    }

    public Round createAndStartRound(String lobbyCode) {

        //Hardcoded target location for testing - Zurich Main Station
        double targetLat = 47.3769;
        double targetLon = 8.5417;
        double delta = 0.002; // ~200m bounding box

        // 45s round / 9s per image = 5 images needed
        List<String> imageUrls = mapillaryService.getImageSequence(
                targetLon - delta, targetLat - delta, 
                targetLon + delta, targetLat + delta, 
                5);

        Round round = new Round();
        round.setLobbyCode(lobbyCode);
        round.setTargetLatitude(targetLat);
        round.setTargetLongitude(targetLon);
        round.setImageSequence(imageUrls);

        return roundRepository.save(round);
    }
}