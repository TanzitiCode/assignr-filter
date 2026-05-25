package filter;

import config.UserPreferences;
import model.Assignment;

import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Filters a list of assignments down to only the ones worth considering.
 *
 * DESIGN PRINCIPLE — Binary decisions only:
 * This class answers yes/no questions: "Is this assignment acceptable at all?"
 * It does NOT decide which assignments are *best* — that's AssignmentScorer's job.
 *
 * WHY separate filtering from scoring?
 * - Filtering removes absolute dealbreakers (pay too low, too far, blacklisted)
 * - Scoring ranks what's left by preference (higher pay is better, closer is better)
 * - Keeping them separate makes each easier to understand, test, and change
 *
 * HOW TO ADD A NEW FILTER:
 * 1. Add a private boolean passes___Filter(Assignment a) method
 * 2. Add a check in getRejectionReason() with a clear reason string
 * That's it — the rest of the logic in filter() handles it automatically.
 */
public class AssignmentFilter {

    private final UserPreferences prefs;

    public AssignmentFilter(UserPreferences prefs) {
        this.prefs = prefs;
    }

    /**
     * Runs every filter against each assignment and returns only the ones that pass.
     * Prints a summary of how many were accepted vs rejected.
     */
    public List<Assignment> filter(List<Assignment> assignments) {
        List<Assignment> passed  = new ArrayList<>();
        List<String>     reasons = new ArrayList<>(); // collect rejection reasons for summary

        for (Assignment a : assignments) {
            String reason = getRejectionReason(a);
            if (reason == null) {
                passed.add(a);
            } else {
                reasons.add(reason);
            }
        }

        // Print a brief summary so you know what was filtered and why
        System.out.printf("Filtered: %d/%d assignments passed%n", passed.size(), assignments.size());
        if (!reasons.isEmpty()) {
            System.out.println("Rejection reasons:");
            reasons.stream()
                .collect(java.util.stream.Collectors.groupingBy(r -> r, java.util.stream.Collectors.counting()))
                .entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> System.out.printf("  %-30s  x%d%n", e.getKey(), e.getValue()));
        }

        return passed;
    }

    /**
     * Returns a human-readable reason string if this assignment fails any filter,
     * or null if it passes all filters.
     *
     * Using one method for all checks means we can easily log WHY
     * something was rejected — valuable for debugging your filter rules.
     */
    private String getRejectionReason(Assignment a) {
        if (!passesPayFilter(a))          return "pay too low ($" + String.format("%.2f", a.getPay()) + ")";
        if (!passesDistanceFilter(a))     return "too far (" + String.format("%.1f", a.getDistanceMiles()) + " mi)";
        if (!passesBlacklistFilter(a))    return "blacklisted school or field";
        if (!passesAvailabilityFilter(a)) return "not available (day/time conflict)";
        return null; // passes all filters
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Individual filter methods
    // Each is a single responsibility — easy to read and easy to tweak
    // ─────────────────────────────────────────────────────────────────────────

    /** Reject if game pay is below the configured minimum.
     *  pay=0 means "unknown" (not shown on unassigned games pages) — always pass through. */
    private boolean passesPayFilter(Assignment a) {
        if (a.getPay() == 0.0) return true;
        return a.getPay() >= prefs.getMinimumPay();
    }

    /** Reject if the venue is farther than the max distance. */
    private boolean passesDistanceFilter(Assignment a) {
        // If distance is 0 (not calculated yet) we let it through — don't falsely reject
        if (a.getDistanceMiles() == 0.0) return true;
        return a.getDistanceMiles() <= prefs.getMaximumDistanceMiles();
    }

    /**
     * Reject if any team name or the venue name matches a blacklisted entry.
     * Uses contains() so "Jefferson" matches "Thomas Jefferson HS".
     */
    private boolean passesBlacklistFilter(Assignment a) {
        String home  = a.getHomeTeam().toLowerCase();
        String away  = a.getAwayTeam().toLowerCase();
        String venue = a.getVenueName().toLowerCase();

        for (String blocked : prefs.getBlacklistedSchools()) {
            if (home.contains(blocked) || away.contains(blocked)) return false;
        }
        for (String blocked : prefs.getBlacklistedFields()) {
            if (venue.contains(blocked)) return false;
        }
        return true;
    }

    /**
     * Reject if the game is on a day you're not available, or outside your time window.
     * If gameTime is null (API didn't provide it), we let it through.
     */
    private boolean passesAvailabilityFilter(Assignment a) {
        if (a.getGameTime() == null) return true;

        // Check day of week
        List<String> availableDays = prefs.getAvailableDays();
        if (!availableDays.isEmpty()) {
            String gameDay = a.getGameTime()
                .getDayOfWeek()
                .getDisplayName(TextStyle.FULL, Locale.ENGLISH); // "Saturday"
            // Case-insensitive comparison
            boolean dayMatches = availableDays.stream()
                .anyMatch(d -> d.equalsIgnoreCase(gameDay));
            if (!dayMatches) return false;
        }

        // Check time window
        LocalTime gameTime = a.getGameTime().toLocalTime();
        LocalTime earliest = LocalTime.parse(prefs.getEarliestGameTime()); // "08:00"
        LocalTime latest   = LocalTime.parse(prefs.getLatestGameTime());   // "20:00"

        return !gameTime.isBefore(earliest) && !gameTime.isAfter(latest);
    }
}
