package api;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import model.Assignment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scrapes available referee assignments from Assignr using a real Chromium browser.
 *
 * ── HOW IT WORKS ──────────────────────────────────────────────────────────────
 *  1. Launch Chromium (headless or visible — controlled by browser.headless config)
 *  2. Log in at app.assignr.com/accounts/sign_in
 *  3. Visit each org's subdomain and navigate to /games/officials/unassigned
 *  4. Intercept the JSON API call that loads game rows
 *  5. Parse game data from JSON (primary) or DOM (fallback)
 *
 * ── IF YOU GET 0 RESULTS ──────────────────────────────────────────────────────
 *  1. Set browser.headless=false in config.properties → watch the browser
 *  2. Set DUMP_PAGE_HTML = true and DEBUG_SCREENSHOTS = true below
 *  3. Run the app — it will save debug-*.png screenshots and api-response-*.json files
 *  4. Open api-response-0.json to see the raw game data from Assignr's API
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class AssignrScraper {

    // ── Login URL ─────────────────────────────────────────────────────────────
    private static final String LOGIN_URL = "https://app.assignr.com/accounts/sign_in";
    // Hub page that lists all the user's organizations
    private static final String ORGS_URL  = "https://app.assignr.com/games";

    // ── Login form selectors (verified from live HTML) ────────────────────────
    private static final String SEL_LOGIN_FIELD = "input[name='login']";
    private static final String SEL_PASSWORD    = "input[name='password']";

    // ── Navigation link text (confirmed from live page HTML) ──────────────────
    private static final String NAV_UNASSIGNED   = "All Unassigned Games";
    private static final String NAV_UNCONFIRMED  = "My Unconfirmed Games";
    private static final String NAV_MY_GAMES     = "My Games";

    // ── URL paths (relative to subdomain base) ────────────────────────────────
    private static final String PATH_UNASSIGNED  = "/games/officials/unassigned";
    private static final String PATH_MY_GAMES    = "/games";

    // ── DOM selectors tried in order (most specific first) ────────────────────
    private static final String[] SEL_ROW_CANDIDATES = {
        "[data-game-id]",
        "table.table tbody tr",
        "table tbody tr",
        ".game-row",
        "tr[class*='game']",
    };

    // Patterns for team name extraction from "X [Home]  vs. Y [Away]"
    private static final Pattern PAT_TIME = Pattern.compile("(\\d+:\\d+ [AP]M)");

    // ── Debug flags (set to true in Main.java for debugging) ─────────────────
    public static boolean DEBUG_SCREENSHOTS = false;
    public static boolean DUMP_PAGE_HTML    = false;

    // ─────────────────────────────────────────────────────────────────────────

    private final String  email;
    private final String  password;
    private final boolean headless;

    private Playwright playwright;
    private Browser    browser;
    private Page       page;

    public AssignrScraper(String email, String password, boolean headless) {
        this.email    = email;
        this.password = password;
        this.headless = headless;
    }

    // ── Public entry point ────────────────────────────────────────────────────

    public List<Assignment> scrapeAvailableAssignments() {
        try {
            setup();
            login();
            return scrapeAllOrgs();
        } finally {
            teardown();
        }
    }

    // ── Browser lifecycle ─────────────────────────────────────────────────────

    private void setup() {
        System.out.println("Launching " + (headless ? "headless" : "visible") + " browser...");
        playwright = Playwright.create();

        browser = playwright.chromium().launch(
            new BrowserType.LaunchOptions()
                .setHeadless(headless)
                .setSlowMo(headless ? 0 : 80)
                .setArgs(Arrays.asList(
                    "--disable-blink-features=AutomationControlled",
                    "--no-sandbox",
                    "--disable-dev-shm-usage",
                    "--disable-infobars"
                ))
        );

        BrowserContext ctx = browser.newContext(
            new Browser.NewContextOptions()
                .setViewportSize(1400, 900)
                .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                              "AppleWebKit/537.36 (KHTML, like Gecko) " +
                              "Chrome/124.0.0.0 Safari/537.36")
                .setLocale("en-US")
                .setTimezoneId("America/New_York")
        );

        ctx.addInitScript(
            "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});"
        );

        page = ctx.newPage();
    }

    private void teardown() {
        // Catch Throwable (not just Exception) because Playwright sometimes throws
        // NoClassDefFoundError for CloseOptions on certain JVM/version combos.
        try { if (browser    != null) browser.close();    } catch (Throwable ignored) {}
        try { if (playwright != null) playwright.close(); } catch (Throwable ignored) {}
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    private void login() {
        System.out.println("Navigating to login page...");
        page.navigate(LOGIN_URL);
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        screenshot("01-login-page");

        // Assignr has two login forms: a visible Bootstrap form (#new_user_session)
        // and a hidden Tailwind form. Target the Bootstrap form via JS to bypass
        // visibility restrictions on the elements.
        page.evaluate(
            "([u, p]) => {" +
            "  const form = document.getElementById('new_user_session');" +
            "  if (!form) { console.error('Bootstrap form not found'); return; }" +
            "  const loginEl = form.querySelector(\"input[name='login']\");" +
            "  const passEl  = form.querySelector(\"input[name='password']\");" +
            "  if (loginEl) { loginEl.value = u; loginEl.dispatchEvent(new Event('input', {bubbles:true})); }" +
            "  if (passEl)  { passEl.value  = p; passEl.dispatchEvent(new Event('input',  {bubbles:true})); }" +
            "}",
            Arrays.asList(email, password)
        );
        screenshot("02-filled");

        page.evaluate("() => { const f = document.getElementById('new_user_session'); if (f) f.submit(); }");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        screenshot("03-after-login");

        String url = page.url();
        if (url.contains("sign_in") || url.contains("login")) {
            String err = "";
            try {
                err = (String) page.evaluate(
                    "() => { const el = document.querySelector('.alert, .alert-error, .flash, [class*=\"alert\"]'); " +
                    "        return el ? el.innerText.trim() : ''; }"
                );
            } catch (Exception ignored) {}
            throw new RuntimeException(
                "Login failed — still on login page.\n" +
                "  URL: " + url + "\n" +
                (err.isEmpty() ? "" : "  Assignr says: " + err + "\n") +
                "  Check assignr.email and assignr.password in config.properties."
            );
        }

        System.out.println("Logged in. URL: " + url);
    }

    // ── Scraping: all organizations ───────────────────────────────────────────

    private List<Assignment> scrapeAllOrgs() {
        System.out.println("Loading organization list...");
        page.navigate(ORGS_URL);
        page.waitForLoadState(LoadState.NETWORKIDLE);
        screenshot("04-orgs-page");

        List<String> orgWelcomeUrls = extractOrgUrls();
        System.out.printf("Found %d organization(s).%n", orgWelcomeUrls.size());

        List<Assignment> all = new ArrayList<>();
        for (int i = 0; i < orgWelcomeUrls.size(); i++) {
            String welcomeUrl = orgWelcomeUrls.get(i);
            String orgName = welcomeUrl
                .replaceFirst("https://", "")
                .replaceFirst("\\.assignr\\.com.*", "");
            System.out.printf("%n[%d/%d] %s%n", i + 1, orgWelcomeUrls.size(), orgName);

            try {
                List<Assignment> games = scrapeOneOrg(welcomeUrl, orgName, i);
                all.addAll(games);
                System.out.printf("  → %d game(s).%n", games.size());
            } catch (Exception e) {
                System.err.printf("  → Error: %s%n", e.getMessage().split("\n")[0]);
            }
        }

        return all;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractOrgUrls() {
        Object result = page.evaluate(
            "() => Array.from(document.querySelectorAll('table tbody tr a.btn'))" +
            "        .map(a => a.href)" +
            "        .filter(h => h.includes('.assignr.com'))"
        );
        return result instanceof List ? (List<String>) result : new ArrayList<>();
    }

    // ── Scraping: one organization ────────────────────────────────────────────

    private List<Assignment> scrapeOneOrg(String welcomeUrl, String orgName, int index) {
        // Establish the subdomain session by visiting the welcome page
        page.navigate(welcomeUrl);
        page.waitForLoadState(LoadState.NETWORKIDLE);

        String base = welcomeUrl.replaceFirst("/welcome.*", "");

        // Capture JSON API responses while we navigate (game data loads via AJAX)
        List<String[]> apiResponses = Collections.synchronizedList(new ArrayList<>());
        Consumer<Response> handler = response -> {
            String url  = response.url();
            String ct   = response.headers().getOrDefault("content-type", "");
            if (ct.contains("json") &&
                    (url.contains("/game") || url.contains("/assign") || url.contains("/official"))) {
                try {
                    String body = response.text();
                    apiResponses.add(new String[]{url, body});
                    System.out.println("  [API] " + url.replaceFirst("https://[^/]+", "") +
                                       " (" + body.length() + " bytes)");
                } catch (Exception ignored) {}
            }
        };
        page.onResponse(handler);

        // ── Try 1: All Unassigned Games (games with no official yet — best for finding work) ──
        List<Assignment> games = tryUrl(base + PATH_UNASSIGNED, "All Unassigned", orgName, index, 0);
        if (index == 0) dumpHtml("debug-unassigned-games.html"); // save while on this page

        // ── Try 2: My Unconfirmed Games (assigned to me, not yet confirmed) ──────────────────
        if (games.isEmpty()) {
            games = tryNavThenScrape(NAV_UNCONFIRMED, orgName, index, 1);
        }

        // ── Try 3: My Games (any assigned games) ─────────────────────────────────────────────
        if (games.isEmpty()) {
            games = tryNavThenScrape(NAV_MY_GAMES, orgName, index, 2);
        }

        // ── Try 4: Parse any captured API JSON (if DOM scraping found nothing) ───────────────
        if (games.isEmpty() && !apiResponses.isEmpty()) {
            System.out.println("  DOM scraping found 0 rows — trying JSON API response...");
            for (String[] resp : apiResponses) {
                List<Assignment> parsed = parseApiJson(resp[1], orgName);
                if (!parsed.isEmpty()) {
                    games = parsed;
                    System.out.printf("  Parsed %d game(s) from API JSON.%n", games.size());
                    break;
                }
            }
        }

        // ── Debug: save files for the first org so you can inspect the structure ─────────────
        if (index == 0) {
            dumpHtml("debug-games-page.html");
            saveApiResponses(apiResponses);
        }

        // Tag each game with the org name if league is blank
        for (Assignment a : games) {
            if (a.getLeague() == null || a.getLeague().isBlank()) a.setLeague(orgName);
        }

        return games;
    }

    private List<Assignment> tryUrl(String url, String label, String orgName, int orgIdx, int attempt) {
        try {
            System.out.println("  Navigating to: " + url.replaceFirst("https://[^/]+", ""));
            page.navigate(url);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            screenshot("org" + orgIdx + "-attempt" + attempt);
            return waitAndScrape();
        } catch (Exception e) {
            System.err.println("  " + label + " nav failed: " + e.getMessage().split("\n")[0]);
            return new ArrayList<>();
        }
    }

    private List<Assignment> tryNavThenScrape(String linkText, String orgName, int orgIdx, int attempt) {
        if (clickLink(linkText)) {
            page.waitForLoadState(LoadState.NETWORKIDLE);
            screenshot("org" + orgIdx + "-attempt" + attempt);
            return waitAndScrape();
        }
        return new ArrayList<>();
    }

    /**
     * Waits for dynamic content to render, then parses game rows from the DOM.
     * Assignr renders game rows via JavaScript after the initial page load,
     * so we need to wait for the JS to complete before reading the DOM.
     */
    private List<Assignment> waitAndScrape() {
        // If we've been redirected away from a games page (e.g., to Home), skip the wait.
        String currentUrl = page.url();
        boolean onGamesPage = currentUrl.contains("/games") || currentUrl.contains("/assign");
        if (!onGamesPage) {
            return parseGameRows(); // will find 0 rows and return quickly
        }

        // Give JavaScript time to make its AJAX call and render the game rows.
        try {
            page.waitForSelector(
                String.join(", ", SEL_ROW_CANDIDATES),
                new Page.WaitForSelectorOptions().setTimeout(8_000)
            );
        } catch (Exception ignored) {
            // No rows after 8s — likely 0 games, not a timeout bug
        }

        return parseGameRows();
    }

    // ── Navigation helpers ────────────────────────────────────────────────────

    private boolean clickLink(String text) {
        try {
            Locator loc = page.getByText(text, new Page.GetByTextOptions().setExact(true));
            if (loc.count() > 0) { loc.first().click(); return true; }
        } catch (Exception ignored) {}
        try {
            Locator loc = page.locator("a").filter(new Locator.FilterOptions().setHasText(text));
            if (loc.count() > 0) { loc.first().click(); return true; }
        } catch (Exception ignored) {}
        return false;
    }

    // ── DOM game row parsing ──────────────────────────────────────────────────

    private List<Assignment> parseGameRows() {
        List<ElementHandle> rows = new ArrayList<>();
        String winningSelector = null;

        for (String sel : SEL_ROW_CANDIDATES) {
            try {
                List<ElementHandle> found = page.querySelectorAll(sel);
                if (found.size() > 1) { // >1 filters out header-only tables
                    rows = found;
                    winningSelector = sel;
                    break;
                }
            } catch (Exception ignored) {}
        }

        if (rows.isEmpty()) {
            System.out.println("  0 rows found in DOM. (Page: " + page.title() + " | " +
                               page.url().replaceFirst("https://[^/]+", "") + ")");
            return new ArrayList<>();
        }

        System.out.printf("  Found %d DOM rows (selector: %s)%n", rows.size(), winningSelector);

        // Print first row's outerHTML for selector debugging (only when DUMP_PAGE_HTML is on)
        if (DUMP_PAGE_HTML && !rows.isEmpty()) {
            try {
                String firstRowHtml = (String) rows.get(0).evaluate("el => el.outerHTML");
                System.out.println("  [DBG] First row HTML:\n" + firstRowHtml);
            } catch (Exception ignored) {}
        }

        List<Assignment> results = new ArrayList<>();
        for (ElementHandle row : rows) {
            try {
                Assignment a = parseRow(row);
                if (a != null) results.add(a);
            } catch (Exception e) {
                System.err.println("  Row parse error: " + e.getMessage());
            }
        }
        System.out.printf("  Parsed %d valid assignment(s).%n", results.size());
        return results;
    }

    /**
     * Parses one Assignr game card (a div with data-game-id) into an Assignment.
     *
     * Actual HTML structure (confirmed from live page):
     *   <div data-game-id="..." data-date="May 26 2026"
     *        data-description="Tue 6:00 PM @ Lightridge High School">
     *     <div class="game-description">
     *       <h5>Playoffs VarB: <em>Lightridge [Home] vs. Riverside [Away]</em></h5>
     *       <p>Boys, VHSL, Playoff</p>
     *     </div>
     *     <div class="game-table-assignments">
     *       <div class="assignment">
     *         <div class="position"><strong>R: </strong></div>
     *         <div class="assigned">Assigned</div>  ← or empty if open
     *       </div>
     *       ...
     *     </div>
     *   </div>
     */
    private Assignment parseRow(ElementHandle row) {
        Assignment a = new Assignment();

        // Date + time from data attributes on the outer card div
        String dateAttr = row.getAttribute("data-date");        // "May 26 2026"
        String descAttr = row.getAttribute("data-description"); // "Tue 6:00 PM @ Lightridge HS"
        if (dateAttr != null && !dateAttr.isBlank()) {
            String timeStr = "";
            if (descAttr != null) {
                Matcher m = PAT_TIME.matcher(descAttr);
                if (m.find()) timeStr = m.group(1);
            }
            a.setGameTime(parseDateTime(
                timeStr.isEmpty() ? dateAttr : (dateAttr + " " + timeStr)
            ));
        }

        // Venue: try link first (has coordinates on the map popup), then plain-text fallback.
        // Some orgs don't link the venue, so we also check .venue-name and col-md-5 h4.
        ElementHandle venueLink = row.querySelector("a[href*='venue_maps']");
        if (venueLink != null) {
            a.setVenueName(venueLink.innerText().trim());
        } else {
            ElementHandle venueSpan = row.querySelector(".venue-name");
            if (venueSpan != null) {
                a.setVenueName(venueSpan.innerText()
                    .replaceFirst("@\\s*", "")      // strip leading "@"
                    .replaceAll("\\s*-\\s*\\d+$", "") // strip trailing " - 1" (field number)
                    .trim());
            } else {
                ElementHandle h4 = row.querySelector(".col-md-5 h4");
                if (h4 != null) {
                    a.setVenueName(h4.innerText()
                        .replaceFirst("@\\s*", "")
                        .replaceAll("\\s+\\d+$", "")
                        .trim());
                }
            }
        }

        // Teams: "Lightridge [Home]  vs. Riverside [Away]" inside h5 > em
        ElementHandle teamsEl = row.querySelector("h5 em");
        if (teamsEl != null) {
            String raw = teamsEl.innerText().trim();
            String[] parts = raw.split("\\s+vs\\.\\s+");
            if (parts.length == 2) {
                a.setHomeTeam(parts[0].replaceAll("\\s*\\[Home\\]\\s*", "").trim());
                a.setAwayTeam(parts[1].replaceAll("\\s*\\[Away\\]\\s*", "").trim());
            } else {
                a.setHomeTeam(raw);
            }
        }

        // League and level: first <p> in game-description ("Boys, VHSL, Playoff")
        ElementHandle infoPara = row.querySelector(".game-description p");
        if (infoPara != null) {
            String info = infoPara.innerText().trim();
            a.setLeague(extractLeague(info));
            a.setGameLevel(extractLevel(info).toLowerCase(Locale.ENGLISH));
        }

        // Open referee positions (fa-circle-dashed icon = slot is open)
        a.setPosition(extractOpenPositions(row));

        // Pay is not shown on the "All Unassigned Games" page (you haven't been assigned yet).
        // pay=0 is treated as "unknown" by the filter — it won't be rejected by the pay filter.
        a.setPay(0.0);

        a.setAccepted(false);

        // Discard blank rows (no game content at all)
        if (a.getHomeTeam().isBlank() && a.getVenueName().isBlank() && a.getGameTime() == null) return null;
        return a;
    }

    /** Scans "Boys, VHSL, Playoff" for known league names. */
    private String extractLeague(String info) {
        String upper = info.toUpperCase();
        for (String known : new String[]{"ECNL","NCAA","MLS NEXT","MLSNEXT","VHSL","USYS","VYSA","MSSL","NPL","AYSO"}) {
            if (upper.contains(known)) return known;
        }
        String[] parts = info.split(",");
        return parts.length >= 2 ? parts[1].trim() : "";
    }

    /** Extracts game level keyword from the info string. */
    private String extractLevel(String info) {
        String lower = info.toLowerCase();
        if (lower.contains("playoff"))   return "playoff";
        if (lower.contains("varsity"))   return "varsity";
        if (lower.contains(" var"))      return "varsity";
        if (lower.contains("jv") || lower.contains("junior varsity")) return "jv";
        if (lower.contains("freshman"))  return "freshman";
        if (lower.contains("collegiat")) return "collegiate";
        if (lower.contains("middle"))    return "middle school";
        String[] parts = info.split(",");
        return parts.length >= 3 ? parts[2].trim() : "";
    }

    /** Returns a comma list of positions with open (fa-circle-dashed) slots. */
    private String extractOpenPositions(ElementHandle row) {
        StringBuilder sb = new StringBuilder();
        try {
            for (ElementHandle slot : row.querySelectorAll(".assignment")) {
                // fa-circle-dashed = unassigned; fa-check = assigned
                if (slot.querySelector(".fa-circle-dashed") != null) {
                    ElementHandle lbl = slot.querySelector(".position strong");
                    if (lbl != null) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(lbl.innerText().replaceAll("[^A-Za-z0-9]", "").trim());
                    }
                }
            }
        } catch (Exception ignored) {}
        return sb.length() > 0 ? sb.toString() : "Open";
    }

    // ── JSON API parsing ──────────────────────────────────────────────────────

    /**
     * Tries to extract Assignment objects from a raw JSON string returned by Assignr's API.
     *
     * Assignr's JSON structure (inferred from Rails conventions):
     *   { "games": [ { "id": ..., "start_time": ..., "home_team": ..., ... }, ... ] }
     *   OR a top-level array:  [ { ... }, ... ]
     *
     * If the structure doesn't match, save the JSON to disk and return empty list so
     * the caller can inspect it and update this parser.
     */
    @SuppressWarnings("unchecked")
    private List<Assignment> parseApiJson(String json, String orgName) {
        if (json == null || json.isBlank() || json.equals("null")) return new ArrayList<>();

        try {
            // Use Playwright's page.evaluate to parse JSON in V8 — avoids adding a JSON library
            Object parsed = page.evaluate("(j) => JSON.parse(j)", json);

            List<Map<String, Object>> gameList = null;

            if (parsed instanceof List) {
                // Direct array
                gameList = (List<Map<String, Object>>) parsed;
            } else if (parsed instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) parsed;
                // Try common wrapper keys
                for (String key : new String[]{"games", "assignments", "data", "records", "results", "items"}) {
                    if (map.get(key) instanceof List) {
                        gameList = (List<Map<String, Object>>) map.get(key);
                        break;
                    }
                }
            }

            if (gameList == null || gameList.isEmpty()) return new ArrayList<>();

            List<Assignment> results = new ArrayList<>();
            for (Object item : gameList) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> g = (Map<String, Object>) item;
                Assignment a = assignmentFromMap(g, orgName);
                if (a != null) results.add(a);
            }
            return results;

        } catch (Exception e) {
            System.err.println("  JSON parse error: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private Assignment assignmentFromMap(Map<String, Object> g, String orgName) {
        Assignment a = new Assignment();

        // Home / Away team names — try common key patterns
        a.setHomeTeam(strFrom(g, "home_team", "homeTeam", "home_team_name", "home"));
        a.setAwayTeam(strFrom(g, "away_team", "awayTeam", "away_team_name", "away"));

        // Venue
        Object venueObj = g.get("site");
        if (venueObj == null) venueObj = g.get("venue");
        if (venueObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> v = (Map<String, Object>) venueObj;
            a.setVenueName(strFrom(v, "name", "title"));
            a.setVenueAddress(strFrom(v, "address", "street_address", "full_address"));
            try { a.setLatitude(Double.parseDouble(String.valueOf(v.getOrDefault("latitude", "0")))); } catch (Exception ignored) {}
            try { a.setLongitude(Double.parseDouble(String.valueOf(v.getOrDefault("longitude", "0")))); } catch (Exception ignored) {}
        } else {
            a.setVenueName(strFrom(g, "site_name", "venue_name", "field_name", "location"));
        }

        // League / game type / position / pay
        a.setLeague(strFrom(g, "league", "competition", "league_name"));
        if (a.getLeague().isBlank()) a.setLeague(orgName);
        a.setGameLevel(strFrom(g, "game_type", "gameType", "level", "age_group").toLowerCase(Locale.ENGLISH));
        a.setPosition(strFrom(g, "position", "role", "position_name"));

        Object fee = g.get("fee");
        if (fee == null) fee = g.get("pay");
        if (fee == null) fee = g.get("amount");
        if (fee != null) {
            try { a.setPay(Double.parseDouble(fee.toString().replaceAll("[^0-9.]", ""))); }
            catch (NumberFormatException ignored) {}
        }

        // Date/time
        String startTime = strFrom(g, "start_time", "startTime", "game_time", "date");
        if (!startTime.isBlank()) a.setGameTime(parseDateTime(startTime));

        a.setAccepted(false);
        if (a.getHomeTeam().isBlank() && a.getAwayTeam().isBlank() && a.getPay() == 0.0) return null;
        return a;
    }

    /** Returns the string value of the first key found in the map, or "". */
    private String strFrom(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object val = map.get(key);
            if (val != null && !val.toString().isBlank() && !val.toString().equals("null")) {
                return val.toString().trim();
            }
        }
        return "";
    }

    // ── Date parsing ──────────────────────────────────────────────────────────

    private LocalDateTime parseDateTime(String raw) {
        if (raw == null || raw.isBlank()) return null;

        // ISO 8601 (common in JSON APIs)
        try {
            String clean = raw.trim().replaceAll("[+-]\\d{2}:\\d{2}$", "").replace("Z", "");
            return LocalDateTime.parse(clean, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ignored) {}

        String[] patterns = {
            "MMM d yyyy h:mm a",      // May 26 2026 6:00 PM  ← from data-date + data-description
            "MMM d, yyyy h:mm a",
            "MMMM d, yyyy h:mm a",
            "MM/dd/yyyy h:mm a",
            "MM/dd/yyyy HH:mm",
            "M/d/yyyy h:mm a",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd h:mm a",
            "EEE, MMM d 'at' h:mm a",
        };
        for (String pattern : patterns) {
            try {
                return LocalDateTime.parse(raw.trim(),
                    DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH));
            } catch (DateTimeParseException ignored) {}
        }

        System.err.println("  Could not parse date: '" + raw + "'");
        return null;
    }

    // ── Debug helpers ─────────────────────────────────────────────────────────

    private void screenshot(String name) {
        if (!DEBUG_SCREENSHOTS) return;
        try {
            String path = "debug-" + name + ".png";
            page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get(path)));
            System.out.println("  [DBG] Screenshot: " + path);
        } catch (Exception e) {
            System.err.println("  [DBG] Screenshot failed: " + e.getMessage());
        }
    }

    private void dumpHtml(String filename) {
        if (!DUMP_PAGE_HTML) return;
        try {
            String html = (String) page.evaluate("() => document.documentElement.outerHTML");
            Files.writeString(Paths.get(filename), html);
            System.out.println("  [DBG] HTML saved: " + filename);
        } catch (Exception e) {
            System.err.println("  [DBG] HTML dump failed: " + e.getMessage());
        }
    }

    private void saveApiResponses(List<String[]> responses) {
        if (!DUMP_PAGE_HTML || responses.isEmpty()) return;
        for (int i = 0; i < responses.size(); i++) {
            try {
                String content = "// URL: " + responses.get(i)[0] + "\n\n" + responses.get(i)[1];
                Files.writeString(Paths.get("debug-api-response-" + i + ".json"), content);
                System.out.println("  [DBG] API JSON saved: debug-api-response-" + i + ".json");
            } catch (IOException e) {
                System.err.println("  [DBG] Failed to save API response: " + e.getMessage());
            }
        }
    }
}
