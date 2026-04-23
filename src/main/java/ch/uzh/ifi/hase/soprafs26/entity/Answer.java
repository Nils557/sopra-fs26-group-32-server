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

    /**
     * Evaluates the score result category and calculates the points awarded 
     * based on the geographic distance between the guess and the target.
     * * Constants are defined to map distance thresholds to ScoreResult categories:
     * - CORRECT_CITY: <= 50km (Yields 1500 - 2000 points based on exact closeness)
     * - CORRECT_COUNTRY: <= 1000km (Yields 0 - 1000 points based on closeness)
     * - INCORRECT: > 1000km (Yields 0 points)
     */
    public void calculateScoreBasedOnDistance() {
        double distance = getDistanceToTargetInKm();
        
        final double CITY_THRESHOLD_KM = 50.0;
        final double COUNTRY_THRESHOLD_KM = 1000.0;
        final int MAX_POINTS = 2000;
        final int HALF_POINTS = 1000;

        if (distance <= CITY_THRESHOLD_KM) {
            this.scoreResult = ScoreResult.CORRECT_CITY;
            // Scales linearly from 2000 points (0km) down to 1500 points (50km)
            double penalty = (distance / CITY_THRESHOLD_KM) * 500;
            this.pointsAwarded = MAX_POINTS - (int) penalty;
            
        } else if (distance <= COUNTRY_THRESHOLD_KM) {
            this.scoreResult = ScoreResult.CORRECT_COUNTRY;
            // Scales linearly from 1000 points (50km) down to 0 points (1000km)
            double distanceIntoCountry = distance - CITY_THRESHOLD_KM;
            double countryRange = COUNTRY_THRESHOLD_KM - CITY_THRESHOLD_KM;
            double penalty = (distanceIntoCountry / countryRange) * HALF_POINTS;
            this.pointsAwarded = HALF_POINTS - (int) penalty;
            
        } else {
            this.scoreResult = ScoreResult.INCORRECT;
            this.pointsAwarded = 0;
        }

        // Failsafe bounds check
        if (this.pointsAwarded < 0) {
            this.pointsAwarded = 0;
        }
    }
}