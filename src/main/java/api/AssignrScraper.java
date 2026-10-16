package api;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import model.Assignment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    // ── Saved login session ───────────────────────────────────────────────────
    // After a successful login (including any verification code), the browser's cookies are saved
    // here so later runs skip the login + code. Contains login cookies: kept out of git, owner-only.
    // Delete this file to force a fresh login.
    private static final Path SESSION_FILE = Paths.get(".assignr-session.json");
    // How long to wait for you to enter Assignr's verification code in the browser window
    private static final int VERIFY_TIMEOUT_MINUTES = 5;

    // ─────────────────────────────────────────────────────────────────────────

    private final String  email;
    private final String  password;
    private final boolean headless;

    private Playwright     playwright;
    private Browser        browser;
    private BrowserContext context;
    private Page           page;

    // Site display names by subdomain, e.g. "dcreferees" -> "DC Referees" (for matching pay statements)
    private final Map<String, String> siteNames = new HashMap<>();

    public AssignrScraper(String email, String password, boolean headless) {
        this.email    = email;
        this.password = password;
        this.headless = headless;
    }

    // ── Public entry point ────────────────────────────────────────────────────

    public List<Assignment> scrapeAvailableAssignments() {
        try {
            setup(headless);
            if (!login()) {
                // Assignr wants a verification code. A headless browser can't show you the page,
                // so reopen a visible one and log in again there.
                if (headless) {
                    System.out.println("Assignr is asking for a verification code — opening a browser window so you can enter it...");
                    teardown();
                    setup(false);
                    if (login()) { saveSession(); return scrapeAllOrgs(); }
                }
                waitForVerification();
            }
            saveSession();
            return scrapeAllOrgs();
        } finally {
            teardown();
        }
    }

    // ── Browser lifecycle ─────────────────────────────────────────────────────

    private void setup(boolean runHeadless) {
        System.out.println("Launching " + (runHeadless ? "headless" : "visible") + " browser...");
        playwright = Playwright.create();

        browser = playwright.chromium().launch(
            new BrowserType.LaunchOptions()
                .setHeadless(runHeadless)
                .setSlowMo(runHeadless ? 0 : 80)
                .setArgs(Arrays.asList(
                    "--disable-blink-features=AutomationControlled",
                    "--no-sandbox",
                    "--disable-dev-shm-usage",
                    "--disable-infobars"
                ))
        );

        Browser.NewContextOptions opts = new Browser.NewContextOptions()
            .setViewportSize(1400, 900)
            .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                          "AppleWebKit/537.36 (KHTML, like Gecko) " +
                          "Chrome/124.0.0.0 Safari/537.36")
            .setLocale("en-US")
            .setTimezoneId("America/New_York");

        // Reuse the saved session (cookies) from a previous successful login, if there is one
        if (Files.exists(SESSION_FILE)) opts.setStorageStatePath(SESSION_FILE);
        try {
            context = browser.newContext(opts);
        } catch (PlaywrightException e) {
            // Corrupt/unreadable session file — discard it and start fresh
            System.out.println("Saved session couldn't be loaded — logging in fresh.");
            try { Files.deleteIfExists(SESSION_FILE); } catch (IOException ignored) {}
            context = browser.newContext(opts.setStorageStatePath(null));
        }

        context.addInitScript(
            "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});"
        );

        page = context.newPage();
    }

    private void teardown() {
        // Catch Throwable (not just Exception) because Playwright sometimes throws
        // NoClassDefFoundError for CloseOptions on certain JVM/version combos.
        try { if (browser    != null) browser.close();    } catch (Throwable ignored) {}
        try { if (playwright != null) playwright.close(); } catch (Throwable ignored) {}
        browser = null; playwright = null; context = null; page = null;
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    /** True for Assignr's "enter the code we sent you" pages (e.g. /user_session/request_code). */
    private static boolean isVerificationPage(String url) {
        String u = url.toLowerCase(Locale.ENGLISH);
        return u.contains("request_code") || u.contains("verif") || u.contains("two_factor") || u.contains("mfa");
    }

    private static boolean isLoginPage(String url) {
        return url.contains("sign_in") || url.contains("login");
    }

    /**
     * Logs in. Returns true when fully logged in, or false when Assignr is asking for a
     * verification code (the caller then lets you enter it in a visible browser).
     */
    private boolean login() {
        // 1. Try the saved session first — if it's still valid, no password or code needed
        if (Files.exists(SESSION_FILE)) {
            page.navigate(ORGS_URL);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            String url = page.url();
            if (!isLoginPage(url) && !isVerificationPage(url)) {
                System.out.println("Using saved Assignr session.");
                return true;
            }
            System.out.println("Saved session expired — logging in again.");
        }

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
        if (isVerificationPage(url)) {
            return false;
        }
        if (isLoginPage(url)) {
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
        return true;
    }

    /**
     * Waits while YOU complete Assignr's verification step in the visible browser window
     * (request the code, then type in the code Assignr sends you). The scraper never reads
     * or enters the code itself — it only watches for the page to move past verification.
     */
    private void waitForVerification() {
        System.out.println();
        System.out.println("┌──────────────────────────────────────────────────────────────┐");
        System.out.println("│  ACTION NEEDED: Assignr sent (or will send) you a code.        │");
        System.out.println("│  Complete the verification step in the browser window.         │");
        System.out.printf ("│  Waiting up to %d minutes...                                    │%n", VERIFY_TIMEOUT_MINUTES);
        System.out.println("└──────────────────────────────────────────────────────────────┘");

        long deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MINUTES * 60_000L;
        while (System.currentTimeMillis() < deadline) {
            String url;
            try {
                page.waitForTimeout(2000);
                url = page.url();
            } catch (PlaywrightException e) {
                throw new RuntimeException("The browser window was closed before verification finished.");
            }
            if (!isVerificationPage(url) && !isLoginPage(url)) {
                page.waitForLoadState(LoadState.NETWORKIDLE);
                System.out.println("Verification complete. URL: " + url);
                return;
            }
        }
        throw new RuntimeException(
            "Timed out after " + VERIFY_TIMEOUT_MINUTES + " minutes waiting for the Assignr verification code.\n" +
            "  Run again and enter the code in the browser window when it opens.");
    }

    /** Saves cookies so the next run can skip login + verification. Owner-only file permissions. */
    private void saveSession() {
        try {
            context.storageState(new BrowserContext.StorageStateOptions().setPath(SESSION_FILE));
            try {
                Files.setPosixFilePermissions(SESSION_FILE, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {} // non-POSIX file systems (Windows)
        } catch (Exception e) {
            // Not fatal: you'll just be asked to log in (and maybe verify) again next time
            System.err.println("WARNING: Could not save Assignr session — " + e.getMessage());
        }
    }

    // ── Scraping: all organizations ───────────────────────────────────────────

    private List<Assignment> scrapeAllOrgs() {
        System.out.println("Loading organization list...");
        page.navigate(ORGS_URL);
        page.waitForLoadState(LoadState.NETWORKIDLE);
        screenshot("04-orgs-page");

        List<String> orgWelcomeUrls = extractOrgUrls();
        System.out.printf("Found %d organization(s).%n", orgWelcomeUrls.size());
        readSiteNames();

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

        if (!orgWelcomeUrls.isEmpty()) addPayEstimates(all, orgWelcomeUrls.get(0).replaceFirst("/welcome.*", ""));
        return all;
    }

    @SuppressWarnings("unchecked")
    private void readSiteNames() {
        try {
            List<List<String>> rows = (List<List<String>>) page.evaluate(
                "() => Array.from(document.querySelectorAll('table tbody tr'))" +
                "        .map(tr => [ (tr.querySelector('td') || {}).innerText || '', (tr.querySelector('a.btn') || {}).href || '' ])" +
                "        .filter(r => r[1].includes('.assignr.com'))"
            );
            for (List<String> r : rows) siteNames.put(subdomainOf(r.get(1)), r.get(0).trim());
        } catch (Exception e) {
            System.err.println("  Could not read site names: " + e.getMessage().split("\n")[0]);
        }
    }

    private static String subdomainOf(String url) {
        return url.replaceFirst("https://", "").replaceFirst("\\.assignr\\.com.*", "");
    }

    /**
     * Assignors rarely publish fees on open games, so estimate them from YOUR past payments at the
     * same site and position (see PayHistory). Games with a real published fee keep it.
     */
    private void addPayEstimates(List<Assignment> games, String anySiteBase) {
        PayHistory history = PayHistory.load();
        try {
            history.update(page, anySiteBase);
        } catch (Exception e) {
            System.err.println("  Could not update pay history (using cached data): " + e.getMessage().split("\n")[0]);
        }
        int estimated = 0;
        for (Assignment a : games) {
            String site = siteNames.get(a.getAssignrName());
            Map<String, Double> bySite = history.estimatesForSite(site);
            Map<String, Double> forGame = new TreeMap<>();
            for (String slot : a.getPosition().split(",")) {
                String kind = PayHistory.positionKind(slot.trim());
                if (bySite.containsKey(kind)) forGame.put(kind, bySite.get(kind));
            }
            if (!forGame.isEmpty()) {
                a.setPayEstimates(forGame);
                a.setPayEstimateGames(history.gamesForSite(site));
                estimated++;
            }
        }
        System.out.printf("Pay: %d game(s) with a published fee, %d with an estimate from your pay history.%n",
            games.stream().filter(g -> g.getPay() > 0).count(), estimated);
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

        // Fetch coordinates for each venue via popup API
        if (!games.isEmpty()) enrichCoordinates(games);

        // Real fees, when the assignor has published them (usually they haven't: $0.00)
        if (!games.isEmpty()) applyPublishedFees(base, games);

        // Tag each game with the org name
        for (Assignment a : games) {
            a.setAssignrName(orgName);
            if (a.getLeague() == null || a.getLeague().isBlank()) a.setLeague(orgName);
        }

        return games;
    }

    /**
     * Reads the "Games as CSV" export of the unassigned list (a plain GET, read-only) and copies any
     * published fee onto the matching game (matched on Assignr's game id = the card's data-game-id).
     */
    private void applyPublishedFees(String base, List<Assignment> games) {
        try {
            APIResponse r = page.request().get(base + PATH_UNASSIGNED + ".csv?style=games");
            if (r.status() != 200) return;
            List<List<String>> rows = parseCsv(r.text());
            if (rows.isEmpty()) return;
            List<String> header = rows.get(0);
            int idCol = header.indexOf("Assignr Database ID");
            int actualCol = header.indexOf("Actual Fee"), defaultCol = header.indexOf("Default Fee");
            if (idCol < 0 || (actualCol < 0 && defaultCol < 0)) return;

            Map<Integer, Double> fees = new HashMap<>();
            for (List<String> row : rows.subList(1, rows.size())) {
                if (row.size() <= idCol) continue;
                double actual  = actualCol  >= 0 && row.size() > actualCol  ? PayHistory.parseDollars(row.get(actualCol))  : 0;
                double deflt   = defaultCol >= 0 && row.size() > defaultCol ? PayHistory.parseDollars(row.get(defaultCol)) : 0;
                double fee = actual > 0 ? actual : deflt;
                if (fee <= 0) continue;
                try { fees.put(Integer.parseInt(row.get(idCol).trim()), fee); } catch (NumberFormatException ignored) {}
            }
            int applied = 0;
            for (Assignment a : games) {
                Double fee = fees.get(a.getId());
                if (fee != null && a.getPay() <= 0) { a.setPay(fee); applied++; }
            }
            if (applied > 0) System.out.printf("  Published fees found for %d game(s).%n", applied);
        } catch (Exception e) {
            System.err.println("  Fee lookup skipped: " + e.getMessage().split("\n")[0]);
        }
    }

    /** Minimal CSV parser: handles quoted fields with commas, doubled quotes, and newlines. */
    static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                else if (c == '"') quoted = false;
                else field.append(c);
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString()); field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(field.toString()); field.setLength(0);
                rows.add(row); row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) { row.add(field.toString()); rows.add(row); }
        return rows;
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

        // Assignr's game id (matches "Assignr Database ID" in the CSV export, used for fee lookup)
        String gameId = row.getAttribute("data-game-id");
        if (gameId != null) {
            try { a.setId(Integer.parseInt(gameId.trim())); } catch (NumberFormatException ignored) {}
        }

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

        // Venue: try link first (contains venue ID for coordinate lookup), then plain-text fallback.
        // Some orgs don't link the venue, so we also check .venue-name and col-md-5 h4.
        ElementHandle venueLink = row.querySelector("a[href*='venue_maps']");
        if (venueLink != null) {
            a.setVenueName(venueLink.innerText().trim());
            String href = venueLink.getAttribute("href");
            if (href != null) {
                Matcher idMatcher = Pattern.compile("id=(\\d+)").matcher(href);
                if (idMatcher.find()) a.setVenuePopupId(Integer.parseInt(idMatcher.group(1)));
            }
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

        // Pay is not shown on the "All Unassigned Games" page. A published fee (rare) is filled in later
        // by applyPublishedFees(), and an estimate from your pay history by addPayEstimates().
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

    /**
     * Fetches each venue's popup URL (once per unique ID) and extracts lat/lon from
     * the embedded Google Maps LatLng constructor: new google.maps.LatLng(lat, lng)
     */
    private void enrichCoordinates(List<Assignment> games) {
        Pattern latLngPat = Pattern.compile(
            "google\\.maps\\.LatLng\\(([\\-\\d.]+),\\s*([\\-\\d.]+)\\)"
        );
        Map<Integer, double[]> cache = new HashMap<>();

        for (Assignment a : games) {
            int id = a.getVenuePopupId();
            if (id <= 0) continue;

            if (!cache.containsKey(id)) {
                try {
                    String popup = (String) page.evaluate(
                        "(url) => fetch(url).then(r => r.text())",
                        "/venue_maps/popup?id=" + id
                    );
                    Matcher m = latLngPat.matcher(popup != null ? popup : "");
                    if (m.find()) {
                        cache.put(id, new double[]{
                            Double.parseDouble(m.group(1)),
                            Double.parseDouble(m.group(2))
                        });
                    } else {
                        cache.put(id, null); // mark as fetched but no coords found
                    }
                } catch (Exception ignored) {
                    cache.put(id, null);
                }
            }

            double[] coords = cache.get(id);
            if (coords != null) {
                a.setLatitude(coords[0]);
                a.setLongitude(coords[1]);
            }
        }

        long withCoords = games.stream().filter(a -> a.getLatitude() != 0.0 || a.getLongitude() != 0.0).count();
        System.out.printf("  Coordinates: %d/%d venues resolved.%n", withCoords, games.size());
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
