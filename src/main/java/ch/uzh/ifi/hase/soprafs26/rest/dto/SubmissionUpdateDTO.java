package ch.uzh.ifi.hase.soprafs26.rest.dto;

public class SubmissionUpdateDTO {

    private Long playerId;
    private boolean hasSubmitted = true; // Hardcoded to true since we only send this when they submit

    // Constructors
    public SubmissionUpdateDTO() {}

    public SubmissionUpdateDTO(Long playerId) {
        this.playerId = playerId;
    }

    // Getters and Setters
    public Long getPlayerId() {
        return playerId;
    }

    public void setPlayerId(Long playerId) {
        this.playerId = playerId;
    }

    public boolean isHasSubmitted() {
        return hasSubmitted;
    }

    public void setHasSubmitted(boolean hasSubmitted) {
        this.hasSubmitted = hasSubmitted;
    }
}