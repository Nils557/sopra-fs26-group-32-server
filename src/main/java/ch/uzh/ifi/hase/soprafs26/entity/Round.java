package ch.uzh.ifi.hase.soprafs26.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

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
}