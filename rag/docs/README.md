# Documents for the referee assistant

Put rule documents here, then run `python ingest.py` from the `rag/` folder.
Supported formats: `.pdf`, `.md`, `.txt`.

```
docs/
  laws/                  IFAB Laws of the Game, plus any federation memos / Q&As
  leagues/
    CSOA/  NAL/  MLS-NEXT/  ECNL/  NCSL/  UPSL/  UPSL-Academy/
    DCPL/  APSL/  APL/  USCCS/  VHSL/
```

- **Folder name = league name.** It's what you pass to `--league`, so keep it short.
- **To add a league**, just create a new folder under `leagues/`. Optionally add its full name to
  `LEAGUE_NAMES` in `config.py` so questions using the full name find it.
- **Rules on a web page, not a PDF?** Save it as PDF from the browser (File → Print → Save as PDF),
  or paste the text into a `.md` file.
- **Use the current season's documents** and delete old ones. Leagues change rules yearly,
  and an outdated rule will be retrieved just as confidently as a current one.
- Name files so the season is clear, e.g. `ecnl-rules-2026-27.pdf`. The file name appears in citations.

Where to look:
- **Laws of the Game:** the IFAB website (theifab.com) publishes the current edition as a free PDF.
  Your national federation (e.g. U.S. Soccer) may publish its own edition and memos.
- **Leagues:** usually a "Rules", "Policies", "Handbook" or "Referee resources" page on the league's
  website. Your assignor can also send you the current versions.

## What's in each folder (as of 2026-10-16)

Each league folder has a `_quick-reference-<league>.md` sheet: half lengths by age group, substitution rules
(moments, limits, re-entry), and overtime, taken from the official documents beside it. These sheets are committed
to git; the rulebooks themselves are not (see `.gitignore`).

| Folder | Documents | Season | Notes |
|---|---|---|---|
| laws/ | IFAB Laws of the Game | 2026/27 | Base rules for every league except VHSL |
| APL | APL rules (provided by you) | Spring 2022 | Ask for a newer edition: dates/fees may be outdated |
| APSL | Regulations & Club Manual | 2026-27 | No game-length change: IFAB 2 x 45 |
| CSOA | Fall Private / Public Schools quick rules | Fall 2026 | Add the spring sheet (VHSL season) when CSOA posts it |
| DCPL | League rules (web page) | 2026 | |
| ECNL | Competition Rules | 2026-27 | |
| MLS-NEXT | Academy Division standards; Homegrown Division standards | 2026-27 / 2025-26 | Replace Homegrown with 2026-27 when published |
| NAL | Technical standards (web page); referee handout | 2026-27 / 2025-26 | |
| NCSL | Rules for Referees (web page) | 2026 | |
| UPSL | Rules & Regulations Handbook | Spring 2023 | Most recent found: ask your assignor for current |
| UPSL-Academy | Academy Handbook | 2024-25 | Most recent found: ask your assignor for current |
| USCCS | By-Laws / League Handbook (web page) | 2026 | IFAB-based, not NCAA |
| VHSL | NFHS vs NCAA vs IFAB comparative guide | 2026-27 | VHSL's own modifications (OT, tie-breakers) still missing: add the VHSL Handbook soccer section |
