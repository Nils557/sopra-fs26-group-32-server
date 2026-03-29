package ch.uzh.ifi.hase.soprafs26.rest.dto;

import ch.uzh.ifi.hase.soprafs26.constant.LobbyStatus;

public class LobbyGetDTO {
    private Long id;
    private String lobbyCode;
    private int maxPlayers;
    private int totalRounds;
    private LobbyStatus status;
    private Long hostUserId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getLobbyCode() { return lobbyCode; }
    public void setLobbyCode(String lobbyCode) { this.lobbyCode = lobbyCode; }

    public int getMaxPlayers() { return maxPlayers; }
    public void setMaxPlayers(int maxPlayers) { this.maxPlayers = maxPlayers; }

    public int getTotalRounds() { return totalRounds; }
    public void setTotalRounds(int totalRounds) { this.totalRounds = totalRounds; }

    public LobbyStatus getStatus() { return status; }
    public void setStatus(LobbyStatus status) { this.status = status; }

    public Long getHostUserId() { return hostUserId; }
    public void setHostUserId(Long hostUserId) { this.hostUserId = hostUserId; }
}