package ch.uzh.ifi.hase.soprafs26.rest.dto;

import javax.validation.constraints.NotNull;

public class AnswerPostDTO {

  @NotNull(message = "Player ID is required")
  private Long playerId;

  @NotNull(message = "Latitude is required")
  private Double latitude;

  @NotNull(message = "Longitude is required")
  private Double longitude;

  public Long getPlayerId() { return playerId; }
  public void setPlayerId(Long playerId) { this.playerId = playerId; }

  public Double getLatitude() { return latitude; }
  public void setLatitude(Double latitude) { this.latitude = latitude; }

  public Double getLongitude() { return longitude; }
  public void setLongitude(Double longitude) { this.longitude = longitude; }
}