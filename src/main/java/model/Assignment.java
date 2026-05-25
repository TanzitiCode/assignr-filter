package model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Represents a single referee assignment from the Assignr API.
 *
 * This is a "model" class (also called a POJO — Plain Old Java Object).
 * Its only responsibility is to hold data — no business logic here.
 * Think of it like one row in a spreadsheet where each field is a column.
 *
 * WHY a separate class for this?
 * Every other class (filter, scorer, display) can work with Assignment objects
 * without caring where the data came from (API, file, database, etc.).
 */
public class Assignment {

    private int id;
    private String homeTeam;
    private String awayTeam;
    private String venueName;
    private String venueAddress;
    private double latitude;
    private double longitude;
    private double pay;
    private String gameLevel;   // "varsity", "jv", "freshman", "middle_school"
    private String league;      // "VHSL", "ECNL", "NCAA", "Club"
    private String position;    // "Referee", "AR1", "AR2"
    private LocalDateTime gameTime;
    private boolean accepted;

    // These two fields are NOT from the API — we calculate them ourselves:
    private double distanceMiles; // calculated using Haversine formula
    private double score;         // calculated by AssignmentScorer

    // No-arg constructor — Gson needs this to create Assignment objects from JSON
    public Assignment() {}

    // ──────────────────────────────────────────────────────────
    // Getters and Setters
    // WHY getters/setters instead of public fields?
    // - Lets you add validation later (e.g., reject negative pay)
    // - Standard Java convention — frameworks and libraries expect it
    // ──────────────────────────────────────────────────────────

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getHomeTeam() { return homeTeam; }
    public void setHomeTeam(String homeTeam) { this.homeTeam = homeTeam; }

    public String getAwayTeam() { return awayTeam; }
    public void setAwayTeam(String awayTeam) { this.awayTeam = awayTeam; }

    public String getVenueName() { return venueName; }
    public void setVenueName(String venueName) { this.venueName = venueName; }

    public String getVenueAddress() { return venueAddress; }
    public void setVenueAddress(String venueAddress) { this.venueAddress = venueAddress; }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public double getPay() { return pay; }
    public void setPay(double pay) { this.pay = pay; }

    public String getGameLevel() { return gameLevel != null ? gameLevel : ""; }
    public void setGameLevel(String gameLevel) { this.gameLevel = gameLevel; }

    public String getLeague() { return league != null ? league : ""; }
    public void setLeague(String league) { this.league = league; }

    public String getPosition() { return position != null ? position : ""; }
    public void setPosition(String position) { this.position = position; }

    public LocalDateTime getGameTime() { return gameTime; }
    public void setGameTime(LocalDateTime gameTime) { this.gameTime = gameTime; }

    public boolean isAccepted() { return accepted; }
    public void setAccepted(boolean accepted) { this.accepted = accepted; }

    public double getDistanceMiles() { return distanceMiles; }
    public void setDistanceMiles(double distanceMiles) { this.distanceMiles = distanceMiles; }

    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }

    /**
     * A human-readable one-line summary of this assignment.
     * Used when printing to the console.
     *
     * String.format() works like printf: %s = string, %.1f = float with 1 decimal, %.2f = 2 decimals.
     */
    @Override
    public String toString() {
        String timeStr = (gameTime != null)
            ? gameTime.format(DateTimeFormatter.ofPattern("EEE MMM d 'at' h:mma"))
            : "Time TBD";

        String payStr  = (pay == 0.0) ? "Unknown" : String.format("$%.0f", pay);
        String distStr = (distanceMiles == 0.0) ? "? mi" : String.format("%.1f mi", distanceMiles);

        return String.format(
            "[Score: %5.1f]  %-25s vs %-25s  |  %-10s  %-10s  |  %7s  |  %6s  |  %s",
            score,
            truncate(homeTeam, 25),
            truncate(awayTeam, 25),
            truncate(league, 10),
            truncate(gameLevel, 10),
            payStr,
            distStr,
            timeStr
        );
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }
}
