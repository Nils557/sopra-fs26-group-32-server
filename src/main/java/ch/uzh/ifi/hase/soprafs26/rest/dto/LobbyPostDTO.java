package ch.uzh.ifi.hase.soprafs26.rest.dto;

public class LobbyPostDTO {
    private Long hostUserId;
    private int maxPlayers;
    private int totalRounds;

    public Long getHostUserId() { return hostUserId; }
    public void setHostUserId(Long hostUserId) { this.hostUserId = hostUserId; }

    public int getMaxPlayers() { return maxPlayers; }
    public void setMaxPlayers(int maxPlayers) { this.maxPlayers = maxPlayers; }

    public int getTotalRounds() { return totalRounds; }
    public void setTotalRounds(int totalRounds) { this.totalRounds = totalRounds; }
}

