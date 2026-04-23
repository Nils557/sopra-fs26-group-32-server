package ch.uzh.ifi.hase.soprafs26.rest.dto;

import java.time.Instant;
import ch.uzh.ifi.hase.soprafs26.constant.ScoreResult;

public class AnswerGetDTO {
    private Long id;
    private Double latitude;
    private Double longitude;
    private ScoreResult scoreResult;
    private Integer pointsAwarded;
    private Instant submittedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    
    public ScoreResult getScoreResult() { return scoreResult; }
    public void setScoreResult(ScoreResult scoreResult) { this.scoreResult = scoreResult; }
    
    public Integer getPointsAwarded() { return pointsAwarded; }
    public void setPointsAwarded(Integer pointsAwarded) { this.pointsAwarded = pointsAwarded; }
    
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
}