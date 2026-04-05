package ch.uzh.ifi.hase.soprafs26.rest.dto;
import ch.uzh.ifi.hase.soprafs26.constant.LobbyStatus;

public class LobbyStartGetDTO {
    private String lobbyCode;
    private LobbyStatus status;

    public String getLobbyCode() { return lobbyCode; }
    public void setLobbyCode(String lobbyCode) { this.lobbyCode = lobbyCode; }

    public LobbyStatus getStatus() { return status; }
    public void setStatus(LobbyStatus status) { this.status = status; }
}