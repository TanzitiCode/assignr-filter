package config;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Loads and holds all user-defined preferences for filtering and scoring.
 *
 * WHY load from a file instead of hard-coding values?
 * - You might share this code on GitHub — your login credentials and home address
 *   should NEVER be in source code. The config file stays on your machine.
 * - config.properties is in .gitignore so it's never accidentally committed.
 * - Anyone else using your code creates their own config.properties.
 */
public class UserPreferences {

    // Assignr login credentials (replaces the old OAuth client_id / client_secret)
    private String assignrEmail;
    private String assignrPassword;

    // Run browser visibly (false) or invisibly in background (true)
    private boolean headlessBrowser;

    // Your home location — used by DistanceCalculator
    private double homeLat;
    private double homeLon;

    // Filter thresholds
    private double minimumPay;
    private double maximumDistanceMiles;

    // Lists for preference matching
    private List<String> preferredLeagues;
    private List<String> preferredGameLevels;
    private List<String> preferredSchools;
    private List<String> blacklistedSchools;
    private List<String> blacklistedFields;

    // Availability window
    private List<String> availableDays;  // e.g., ["Saturday", "Sunday"]
    private String earliestGameTime;      // "08:00" in 24-hour format
    private String latestGameTime;        // "20:00"

    // Scoring weights — tune these to match your priorities
    private double payWeight;
    private double distanceWeight;
    private double levelBonusVarsity;
    private double levelBonusCollegiate;

    // Private constructor — use the static load() method instead
    private UserPreferences() {}

    /**
     * Reads config.properties and returns a populated UserPreferences object.
     *
     * @param filePath  Path to config file (e.g., "config.properties")
     * @throws IOException if the file doesn't exist or can't be read
     */
    public static UserPreferences load(String filePath) throws IOException {
        Properties props = new Properties();

        // try-with-resources: automatically closes the stream when done
        try (FileInputStream fis = new FileInputStream(filePath)) {
            props.load(fis);
        }

        UserPreferences p = new UserPreferences();

        // Assignr credentials
        p.assignrEmail    = props.getProperty("assignr.email", "");
        p.assignrPassword = props.getProperty("assignr.password", "");
        p.headlessBrowser = Boolean.parseBoolean(props.getProperty("browser.headless", "true"));

        // Home location (for distance calculations)
        p.homeLat = parseDouble(props, "home.latitude", 0.0);
        p.homeLon = parseDouble(props, "home.longitude", 0.0);

        // Filter settings
        p.minimumPay           = parseDouble(props, "filter.min_pay", 80.0);
        p.maximumDistanceMiles = parseDouble(props, "filter.max_distance_miles", 25.0);

        // Comma-separated list settings
        p.preferredLeagues    = parseList(props.getProperty("filter.preferred_leagues", "VHSL,ECNL,NCAA"));
        p.preferredGameLevels = parseList(props.getProperty("filter.preferred_levels", "varsity,collegiate"));
        p.preferredSchools    = parseList(props.getProperty("filter.preferred_schools", ""));
        p.blacklistedSchools  = parseList(props.getProperty("filter.blacklisted_schools", ""));
        p.blacklistedFields   = parseList(props.getProperty("filter.blacklisted_fields", ""));
        p.availableDays       = parseList(props.getProperty("filter.available_days", "Saturday,Sunday"));

        p.earliestGameTime = props.getProperty("filter.earliest_time", "08:00");
        p.latestGameTime   = props.getProperty("filter.latest_time", "20:00");

        // Scoring weights
        p.payWeight            = parseDouble(props, "score.pay_weight", 1.0);
        p.distanceWeight       = parseDouble(props, "score.distance_weight", 0.5);
        p.levelBonusVarsity    = parseDouble(props, "score.varsity_bonus", 15.0);
        p.levelBonusCollegiate = parseDouble(props, "score.collegiate_bonus", 20.0);

        validate(p);
        return p;
    }

    /** Converts "VHSL, ECNL, NCAA" into a List of lowercase trimmed strings. */
    private static List<String> parseList(String value) {
        if (value == null || value.trim().isEmpty()) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (String item : value.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) result.add(trimmed.toLowerCase());
        }
        return result;
    }

    private static double parseDouble(Properties props, String key, double defaultVal) {
        try {
            return Double.parseDouble(props.getProperty(key, String.valueOf(defaultVal)));
        } catch (NumberFormatException e) {
            System.err.println("Warning: invalid number for '" + key + "', using default " + defaultVal);
            return defaultVal;
        }
    }

    private static void validate(UserPreferences p) {
        if (p.assignrEmail.isEmpty())
            System.err.println("Warning: assignr.email is not set in config.properties");
        if (p.assignrPassword.isEmpty())
            System.err.println("Warning: assignr.password is not set in config.properties");
        if (p.homeLat == 0.0 && p.homeLon == 0.0)
            System.err.println("Warning: home.latitude/longitude not set — distance will be inaccurate");
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public String getAssignrEmail()          { return assignrEmail; }
    public String getAssignrPassword()       { return assignrPassword; }
    public boolean isHeadlessBrowser()       { return headlessBrowser; }
    public double getHomeLat()               { return homeLat; }
    public double getHomeLon()               { return homeLon; }
    public double getMinimumPay()            { return minimumPay; }
    public double getMaximumDistanceMiles()  { return maximumDistanceMiles; }
    public List<String> getPreferredLeagues()    { return preferredLeagues; }
    public List<String> getPreferredGameLevels() { return preferredGameLevels; }
    public List<String> getPreferredSchools()    { return preferredSchools; }
    public List<String> getBlacklistedSchools()  { return blacklistedSchools; }
    public List<String> getBlacklistedFields()   { return blacklistedFields; }
    public List<String> getAvailableDays()       { return availableDays; }
    public String getEarliestGameTime()      { return earliestGameTime; }
    public String getLatestGameTime()        { return latestGameTime; }
    public double getPayWeight()             { return payWeight; }
    public double getDistanceWeight()        { return distanceWeight; }
    public double getLevelBonusVarsity()     { return levelBonusVarsity; }
    public double getLevelBonusCollegiate()  { return levelBonusCollegiate; }
}
