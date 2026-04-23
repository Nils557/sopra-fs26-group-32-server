package ch.uzh.ifi.hase.soprafs26.entity;

import jakarta.persistence.*;
import java.time.Instant;
import ch.uzh.ifi.hase.soprafs26.constant.ScoreResult;
import ch.uzh.ifi.hase.soprafs26.util.DistanceCalculator;

@Entity
@Table(name = "ANSWER")
public class Answer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    @Enumerated(EnumType.STRING)
    private ScoreResult scoreResult;

    @Column(nullable = false)
    private int pointsAwarded;

    @Column(nullable = false)
    private Instant submittedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "round_id", nullable = false)
    private Round round;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private User player;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }
    
    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }
    
    public ScoreResult getScoreResult() { return scoreResult; }
    public void setScoreResult(ScoreResult scoreResult) { this.scoreResult = scoreResult; }
    
    public int getPointsAwarded() { return pointsAwarded; }
    public void setPointsAwarded(int pointsAwarded) { this.pointsAwarded = pointsAwarded; }
    
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    
    public Round getRound() { return round; }
    public void setRound(Round round) { this.round = round; }
    
    public User getPlayer() { return player; }
    public void setPlayer(User player) { this.player = player; }

    @Transient 
    public double getDistanceToTargetInKm() {
        if (this.round == null) {
            return 0.0;
        }
        
        return DistanceCalculator.calculateDistanceInKm(
            this.latitude, 
            this.longitude, 
            this.round.getTargetLatitude(), 
            this.round.getTargetLongitude()
        );
    }
}