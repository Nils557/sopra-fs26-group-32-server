package ch.uzh.ifi.hase.soprafs26.rest.dto;

public class CurrentRoundDTO {
    private Long roundId;
    private String imageUrl;
    private int index;
    private int roundNumber;
    private int totalRounds;
    private int timeLeft;

    public Long getRoundId() { return roundId; }
    public void setRoundId(Long roundId) { this.roundId = roundId; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public int getIndex() { return index; }
    public void setIndex(int index) { this.index = index; }

    public int getRoundNumber() { return roundNumber; }
    public void setRoundNumber(int roundNumber) { this.roundNumber = roundNumber; }

    public int getTotalRounds() { return totalRounds; }
    public void setTotalRounds(int totalRounds) { this.totalRounds = totalRounds; }

    public int getTimeLeft() { return timeLeft; }
    public void setTimeLeft(int timeLeft) { this.timeLeft = timeLeft; }
}
