import api.AssignrScraper;
import config.UserPreferences;
import filter.AssignmentFilter;
import model.Assignment;
import ranking.AssignmentScorer;
import util.DistanceCalculator;

import java.io.IOException;
import java.util.List;

/**
 * Entry point for the Assignr Assignment Filter.
 *
 * This class is intentionally thin — it just wires together all the pieces
 * and orchestrates the pipeline:
 *
 *   Config → Browser Login → Scrape → Distance → Filter → Score → Display
 *
 * HOW TO RUN:
 *   1. Copy config.properties.example → config.properties
 *   2. Fill in your Assignr email, password, and home location
 *   3. mvn package
 *   4. (First time only) mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"
 *   5. java -jar target/assignr-filter-1.0-SNAPSHOT.jar
 *
 * HOW TO WATCH THE BROWSER (useful for debugging selectors):
 *   Set browser.headless=false in config.properties
 *
 * HOW TO SAVE SCREENSHOTS AT EACH STEP:
 *   Set AssignrScraper.DEBUG_SCREENSHOTS = true; in the line below
 */
public class Main {

    private static final String CONFIG_FILE = "config.properties";
    private static final int    TOP_N       = 10; // How many top matches to display

    public static void main(String[] args) {
        printHeader();

        // ── Step 1: Load user preferences ────────────────────────────────
        UserPreferences prefs;
        try {
            prefs = UserPreferences.load(CONFIG_FILE);
            System.out.println("Loaded preferences from " + CONFIG_FILE);
        } catch (IOException e) {
            System.err.println("\nERROR: Could not load " + CONFIG_FILE);
            System.err.println("       Copy config.properties.example to config.properties");
            System.err.println("       and fill in your API credentials and preferences.");
            System.exit(1);
            return;
        }

        // ── Step 2 + 3: Launch browser, log in, and scrape assignments ──────
        // Set these to true to debug selectors:
        // AssignrScraper.DEBUG_SCREENSHOTS = true;
        // AssignrScraper.DUMP_PAGE_HTML    = true;

        AssignrScraper scraper = new AssignrScraper(
            prefs.getAssignrEmail(),
            prefs.getAssignrPassword(),
            prefs.isHeadlessBrowser()
        );

        List<Assignment> assignments;
        try {
            assignments = scraper.scrapeAvailableAssignments();
            System.out.printf("Scraped %d available assignments.%n%n", assignments.size());
        } catch (Exception e) {
            System.err.println("\nERROR: Scraping failed — " + e.getMessage());
            System.err.println("Tips:");
            System.err.println("  • Set browser.headless=false to watch the browser");
            System.err.println("  • Uncomment DEBUG_SCREENSHOTS=true in Main.java");
            System.exit(1);
            return;
        }

        if (assignments.isEmpty()) {
            System.out.println("No available assignments found.");
            return;
        }

        // ── Step 4: Calculate distance for each assignment ────────────────
        // The API gives us lat/lon for each venue. We compare to our home location.
        int noCoords = 0;
        for (Assignment a : assignments) {
            if (DistanceCalculator.isValidCoordinate(a.getLatitude(), a.getLongitude())) {
                double miles = DistanceCalculator.calculateMiles(
                    prefs.getHomeLat(), prefs.getHomeLon(),
                    a.getLatitude(),    a.getLongitude()
                );
                a.setDistanceMiles(miles);
            } else {
                // No coordinates — leave distanceMiles at 0.0 so the distance filter
                // treats this game as "local" (the 0.0 check in AssignmentFilter is the signal).
                // Unassigned games don't embed venue lat/lon in the page HTML.
                noCoords++;
            }
        }

        if (noCoords > 0) {
            System.out.printf("Note: %d assignment(s) had no coordinates — shown with distance 0 (location unknown).%n", noCoords);
        }

        // ── Step 5: Filter assignments ────────────────────────────────────
        AssignmentFilter filter = new AssignmentFilter(prefs);
        List<Assignment> filtered = filter.filter(assignments);

        if (filtered.isEmpty()) {
            System.out.println("\nNo assignments match your current preferences.");
            System.out.println("Try relaxing your filters in config.properties (lower min_pay or raise max_distance).");
            return;
        }

        // ── Step 6: Score and rank ────────────────────────────────────────
        AssignmentScorer scorer = new AssignmentScorer(prefs);
        List<Assignment> ranked = scorer.scoreAndRank(filtered);

        // ── Step 7: Display results ───────────────────────────────────────
        displayResults(ranked, TOP_N, prefs);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Display helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static void displayResults(List<Assignment> ranked, int topN, UserPreferences prefs) {
        System.out.println("\n" + "═".repeat(100));
        System.out.printf("  TOP %d ASSIGNMENTS  (ranked by score — higher = better match for your preferences)%n",
            Math.min(topN, ranked.size()));
        System.out.println("═".repeat(100));
        System.out.printf("  Filters: min $%.0f pay (pay=0 means unknown, always shown)  |  max %.0f mi  |  days: %s%n%n",
            prefs.getMinimumPay(),
            prefs.getMaximumDistanceMiles(),
            String.join(", ", prefs.getAvailableDays())
        );

        int limit = Math.min(topN, ranked.size());
        for (int i = 0; i < limit; i++) {
            Assignment a = ranked.get(i);
            System.out.printf("  %2d. %s%n", i + 1, a);
            System.out.printf("      Venue:    %s%n", a.getVenueName());
            System.out.printf("      Address:  %s%n", a.getVenueAddress());
            System.out.printf("      Position: %s%n", a.getPosition());
            System.out.println();
        }

        if (ranked.size() > topN) {
            System.out.printf("  ... and %d more (showing top %d)%n", ranked.size() - topN, topN);
        }

        System.out.println("═".repeat(100));
    }

    private static void printHeader() {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║     Assignr Assignment Filter        ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println();
    }
}
