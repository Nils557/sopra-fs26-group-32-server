package ch.uzh.ifi.hase.soprafs26.rest.dto;

public class LocationResult {
    private final String city;
    private final String country;

    public LocationResult(String city, String country) {
        this.city = city;
        this.country = country;
    }

    public String getCity() { return city; }
    public String getCountry() { return country; }
    public String getFullName() { return city + ", " + country; }
}