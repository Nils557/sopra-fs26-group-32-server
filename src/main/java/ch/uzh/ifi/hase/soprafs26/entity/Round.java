package ch.uzh.ifi.hase.soprafs26.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;

@Entity
@Table(name = "GAME_ROUND") // "Round" is often a reserved SQL keyword, renaming table to be safe
public class Round {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String lobbyCode;

    @Column(nullable = false)
    private double targetLatitude;

    @Column(nullable = false)
    private double targetLongitude;

    @Column(nullable = false)
    private boolean finished = false;

    @Column
    private Instant startedAt;

    @Column
    private String targetCity;

    @Column
    private String targetCountry;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ROUND_IMAGES", joinColumns = @JoinColumn(name = "round_id"))
    @Column(name = "image_url", length = 1000) // URLs can be long
    private List<String> imageSequence = new ArrayList<>();

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getLobbyCode() { return lobbyCode; }
    public void setLobbyCode(String lobbyCode) { this.lobbyCode = lobbyCode; }
    public double getTargetLatitude() { return targetLatitude; }
    public void setTargetLatitude(double targetLatitude) { this.targetLatitude = targetLatitude; }
    public double getTargetLongitude() { return targetLongitude; }
    public void setTargetLongitude(double targetLongitude) { this.targetLongitude = targetLongitude; }
    public List<String> getImageSequence() { return imageSequence; }
    public void setImageSequence(List<String> imageSequence) { this.imageSequence = imageSequence; }
    public boolean isFinished() { return finished; }
    public void setFinished(boolean finished) { this.finished = finished; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public String getTargetCity() { return targetCity; }
    public void setTargetCity(String targetCity) { this.targetCity = targetCity; }
    public String getTargetCountry() { return targetCountry; }
    public void setTargetCountry(String targetCountry) { this.targetCountry = targetCountry; }
}