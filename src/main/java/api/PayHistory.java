package api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Estimates pay for open games from YOUR OWN payment history.
 *
 * WHY?
 * Assignors almost never publish fees on unassigned games (the fee columns are $0.00), but your
 * "Payments Received" statements list every past game with its position and what you were paid:
 *     "# 13592122 Sun Oct 11 5:00 PM @ The Fields at RFK: ... (Asst Referee [AR1])   $78.00"
 * Averaging those per assigning site and per position (center / AR) gives a realistic estimate.
 *
 * Statements are cached in pay-history.json (your payment data — kept out of git, owner-only),
 * so each run only downloads statements it hasn't seen before.
 */
public class PayHistory {

    private static final Path CACHE_FILE = Paths.get("pay-history.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** One paid game from a statement. */
    public static class Line {
        String from;     // statement "From" column, e.g. "Shaun Papperman (MidAtlantic Pro Referee Assigning)"
        String kind;     // "center", "AR", "4th", or "other"
        double amount;   // dollars paid for this game/position
        String date;     // game date as shown, e.g. "Oct 11 2026"
    }

    /** Statement id -> its game lines. */
    private Map<String, List<Line>> statements = new LinkedHashMap<>();

    public static PayHistory load() {
        PayHistory h = new PayHistory();
        if (Files.exists(CACHE_FILE)) {
            try (Reader in = Files.newBufferedReader(CACHE_FILE, StandardCharsets.UTF_8)) {
                Map<String, List<Line>> m = GSON.fromJson(in, new TypeToken<LinkedHashMap<String, List<Line>>>(){}.getType());
                if (m != null) h.statements = m;
            } catch (Exception e) {
                System.out.println("  (pay-history.json unreadable — rebuilding it)");
            }
        }
        return h;
    }

    /**
     * Reads the Payments Received list (READ-ONLY: navigation only, no clicks or form submissions)
     * and downloads any statements not already cached.
     *
     * @param siteBase any of your Assignr site URLs, e.g. "https://dcreferees.assignr.com" —
     *                 the statements page there lists statements from all your sites
     */
    @SuppressWarnings("unchecked")
    public void update(Page page, String siteBase) {
        page.navigate(siteBase + "/account/statements");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        List<Map<String, String>> rows = (List<Map<String, String>>) page.evaluate(
            "() => Array.from(document.querySelectorAll('table tbody tr')).map(tr => {" +
            "  const a = tr.querySelector('a[href*=\"/account/statements/\"]');" +
            "  const td = Array.from(tr.querySelectorAll('td')).map(c => c.innerText.trim());" +
            "  return { href: a ? a.href : '', from: td[1] || '' };" +
            "}).filter(r => r.href)"
        );

        int added = 0;
        for (Map<String, String> row : rows) {
            Matcher idm = Pattern.compile("/account/statements/(\\d+)").matcher(row.get("href"));
            if (!idm.find() || statements.containsKey(idm.group(1))) continue;
            try {
                statements.put(idm.group(1), readStatement(page, row.get("href"), row.get("from")));
                added++;
            } catch (Exception e) {
                System.err.println("  Could not read statement " + idm.group(1) + ": " + e.getMessage().split("\n")[0]);
            }
        }
        System.out.printf("Pay history: %d statements (%d new), %d paid games.%n",
            statements.size(), added, statements.values().stream().mapToInt(List::size).sum());
        if (added > 0) save();
    }

    @SuppressWarnings("unchecked")
    private List<Line> readStatement(Page page, String url, String from) {
        page.navigate(url);
        page.waitForLoadState(LoadState.NETWORKIDLE);
        List<List<String>> cells = (List<List<String>>) page.evaluate(
            "() => Array.from(document.querySelectorAll('table tbody tr'))" +
            "        .map(tr => Array.from(tr.querySelectorAll('td')).map(c => c.innerText.trim().replace(/\\s+/g, ' ')))"
        );
        List<Line> lines = new ArrayList<>();
        for (List<String> c : cells) {
            if (c.size() < 3) continue;
            String desc = c.get(1), amountText = c.get(c.size() - 1);
            if (!desc.startsWith("#")) continue; // skips "Statement Total:" and blank rows
            Line l = new Line();
            l.from = from;
            l.date = c.get(0);
            l.kind = positionKind(lastParenthetical(desc));
            l.amount = parseDollars(amountText);
            if (l.amount > 0) lines.add(l);
        }
        return lines;
    }

    private void save() {
        try (Writer out = Files.newBufferedWriter(CACHE_FILE, StandardCharsets.UTF_8)) {
            GSON.toJson(statements, out);
        } catch (IOException e) {
            System.err.println("WARNING: Could not save pay-history.json — " + e.getMessage());
            return;
        }
        try {
            Files.setPosixFilePermissions(CACHE_FILE, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException ignored) {}
    }

    /**
     * Average pay per position kind for one assigning site, e.g. {"center": 72.5, "AR": 51.0}.
     * A statement belongs to a site when its "From" column ends with "(<site name>)".
     */
    public Map<String, Double> estimatesForSite(String siteName) {
        Map<String, double[]> sums = new HashMap<>(); // kind -> {total, count}
        for (List<Line> lines : statements.values()) {
            for (Line l : lines) {
                if (!belongsToSite(l.from, siteName) || "other".equals(l.kind)) continue;
                double[] s = sums.computeIfAbsent(l.kind, k -> new double[2]);
                s[0] += l.amount;
                s[1]++;
            }
        }
        Map<String, Double> avg = new TreeMap<>();
        sums.forEach((kind, s) -> avg.put(kind, Math.round(s[0] / s[1] * 100.0) / 100.0));
        return avg;
    }

    /** Number of past paid games behind the estimates for a site. */
    public int gamesForSite(String siteName) {
        int n = 0;
        for (List<Line> lines : statements.values())
            for (Line l : lines) if (belongsToSite(l.from, siteName) && !"other".equals(l.kind)) n++;
        return n;
    }

    private static boolean belongsToSite(String from, String siteName) {
        if (from == null || siteName == null || siteName.isBlank()) return false;
        String f = from.trim().toLowerCase(Locale.ENGLISH), s = siteName.trim().toLowerCase(Locale.ENGLISH);
        return f.endsWith("(" + s + ")");
    }

    // ── Parsing helpers (also used by AssignrScraper for open positions) ──────

    /** "…(Field 3) … (Asst Referee [AR1])" -> "Asst Referee [AR1]" */
    static String lastParenthetical(String s) {
        int close = s.lastIndexOf(')');
        if (close < 0) return "";
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            char ch = s.charAt(i);
            if (ch == ')') depth++;
            else if (ch == '(' && --depth == 0) return s.substring(i + 1, close);
        }
        return "";
    }

    /**
     * Classifies a position label: "Asst Referee [AR1]", "AR2", "Asst. Referee" -> "AR";
     * "Referee", "R", "Center" -> "center"; "4th Official" -> "4th"; anything else -> "other".
     */
    public static String positionKind(String label) {
        String p = label.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9\\[\\] ]", " ").trim();
        if (p.contains("asst") || p.contains("assistant") || p.contains("[ar") || p.matches("ar\\d*") || p.startsWith("ar ")
                || p.contains("linesman") || p.contains("line judge")) return "AR";
        if (p.contains("4th") || p.contains("fourth")) return "4th";
        if (p.matches("r|cr|ref|center|centre") || p.startsWith("referee") || p.contains("center ref")) return "center";
        return "other";
    }

    static double parseDollars(String s) {
        try {
            return Double.parseDouble(s.replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
