package ranking;

import config.UserPreferences;
import model.Assignment;

import java.util.Comparator;
import java.util.List;

/**
 * Scores each assignment with a numeric value and sorts them best-to-worst.
 *
 * SCORING FORMULA:
 *   score = (pay × payWeight)
 *         - (distanceMiles × distanceWeight)
 *         + levelBonus      (if varsity or collegiate)
 *         + leagueBonus     (if a preferred league)
 *         + schoolBonus     (if a preferred school/team)
 *
 * WHY a weighted formula instead of simple sorting?
 * Simple sorting can only rank by one thing at a time (e.g., highest pay OR
 * closest distance — not both). A weighted score lets all factors contribute
 * simultaneously. Adjust the weights in config.properties to change priorities.
 *
 * EXAMPLE:
 *   Game A: $95 pay, 5 miles, varsity VHSL
 *     score = (95×1.0) - (5×0.5) + 15 + 10 = 117.5
 *
 *   Game B: $120 pay, 22 miles, JV club
 *     score = (120×1.0) - (22×0.5) + 0 + 0 = 109.0
 *
 *   Game A ranks higher despite paying less — you prefer nearby varsity games.
 *   Change score.pay_weight=2.0 to flip the preference.
 */
public class AssignmentScorer {

    private final UserPreferences prefs;

    public AssignmentScorer(UserPreferences prefs) {
        this.prefs = prefs;
    }

    /**
     * Scores every assignment in the list, then sorts highest score first.
     * Modifies the list in-place and also returns it (for chaining).
     */
    public List<Assignment> scoreAndRank(List<Assignment> assignments) {
        // Score each assignment
        assignments.forEach(this::computeScore);

        // Sort descending by score; push unknown-location games to the bottom
        assignments.sort(
            Comparator.comparingInt((Assignment a) -> a.getVenuePopupId() <= 0 ? 1 : 0)
                .thenComparing(Comparator.comparingDouble(Assignment::getScore).reversed())
        );

        return assignments;
    }

    /** Computes and sets the score on a single assignment. */
    private void computeScore(Assignment a) {
        double score = 0;

        // ── Pay ────────────────────────────────────────────────────────────
        // More money = higher score. payWeight=1.0 means $1 = 1 point.
        // Raise payWeight to 2.0 if pay is your top priority.
        score += a.getPay() * prefs.getPayWeight();

        // ── Distance penalty ───────────────────────────────────────────────
        // Farther away = lower score. distanceWeight=0.5 means 1 mile = -0.5 pts.
        // Raise distanceWeight to penalize distance more heavily.
        score -= a.getDistanceMiles() * prefs.getDistanceWeight();

        // ── Game level bonus ───────────────────────────────────────────────
        // Varsity and collegiate games are more prestigious and typically
        // look better on a ref resume — reward them.
        String level = a.getGameLevel().toLowerCase();
        if (level.contains("varsity")) {
            score += prefs.getLevelBonusVarsity();
        } else if (level.contains("collegiate") || level.contains("college")
                || level.contains("ncaa") || level.contains("university")) {
            score += prefs.getLevelBonusCollegiate();
        }

        // ── Preferred league bonus ─────────────────────────────────────────
        // A flat +10 points for each preferred league match.
        // Having VHSL/ECNL/NCAA in your preferred_leagues gives those games
        // a consistent bump over club games.
        String league = a.getLeague().toUpperCase();
        for (String preferred : prefs.getPreferredLeagues()) {
            if (league.contains(preferred.toUpperCase())) {
                score += 10;
                break; // Only count the bonus once even if multiple leagues match
            }
        }

        // ── Preferred school bonus ─────────────────────────────────────────
        // You might prefer certain schools — maybe they pay on time, have great
        // facilities, or you just enjoy working there.
        String home = a.getHomeTeam().toLowerCase();
        String away = a.getAwayTeam().toLowerCase();
        for (String preferred : prefs.getPreferredSchools()) {
            if (home.contains(preferred.toLowerCase()) || away.contains(preferred.toLowerCase())) {
                score += 5;
                break;
            }
        }

        a.setScore(score);
    }
}
