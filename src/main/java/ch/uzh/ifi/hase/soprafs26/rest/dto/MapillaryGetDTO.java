package ch.uzh.ifi.hase.soprafs26.rest.dto;

public class MapillaryGetDTO {
    private String imageUrl;
    private double latitude;
    private double longitude;

    // Getters and Setters
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }
    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }
}