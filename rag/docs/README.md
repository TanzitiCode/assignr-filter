# Documents for the referee assistant

Put rule documents here, then run `python ingest.py` from the `rag/` folder.
Supported formats: `.pdf`, `.md`, `.txt`.

```
docs/
  laws/                  IFAB Laws of the Game, plus any federation memos / Q&As
  leagues/
    CSOA/  NAL/  MLS-NEXT/  ECNL/  NCSL/  UPSL/  UPSL-Academy/
    DCPL/  APSL/  APL/  USCCS/
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
