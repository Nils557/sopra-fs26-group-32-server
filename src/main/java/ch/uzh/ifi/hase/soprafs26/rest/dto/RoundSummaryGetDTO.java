package ch.uzh.ifi.hase.soprafs26.rest.dto;

import java.util.List;

import ch.uzh.ifi.hase.soprafs26.service.ScoringService.PlayerStanding;

public class RoundSummaryGetDTO {
    private Long roundId;
    private String correctCity;
    private String correctCountry;
    private Double correctLatitude;
    private Double correctLongitude;
    
    private List<PlayerStanding> standings;

    public Long getRoundId() { return roundId; }
    public void setRoundId(Long roundId) { this.roundId = roundId; }

    public String getCorrectCity() { return correctCity; }
    public void setCorrectCity(String correctCity) { this.correctCity = correctCity; }

    public String getCorrectCountry() { return correctCountry; }
    public void setCorrectCountry(String correctCountry) { this.correctCountry = correctCountry; }

    public Double getCorrectLatitude() { return correctLatitude; }
    public void setCorrectLatitude(Double correctLatitude) { this.correctLatitude = correctLatitude; }

    public Double getCorrectLongitude() { return correctLongitude; }
    public void setCorrectLongitude(Double correctLongitude) { this.correctLongitude = correctLongitude; }

    public List<PlayerStanding> getStandings() { return standings; }
    public void setStandings(List<PlayerStanding> standings) { this.standings = standings; }
}