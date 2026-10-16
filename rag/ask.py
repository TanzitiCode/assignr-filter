"""
Command-line entry point. Ask anything — the router decides where it goes:

    python ask.py "Keeper handles a deliberate kick from a teammate. Restart?"
    python ask.py "How long are halves for U15 in ECNL?"     # league detected from the question
    python ask.py --league ECNL "How long are halves for U15?"
    python ask.py "Any varsity center games this Saturday over $60 within 20 miles?"
    python ask.py                      # interactive mode

In interactive mode, type `league ECNL` to fix the league, `league` to clear it,
`sources` to show what the last answer was based on, `queries` to show how the
question was rewritten (multi-query), `steps` to show the self-checks, and `quit` to exit.
"""
import argparse
import os
import sys
from datetime import datetime

import config
import assignments
from assistant import Result, handle
from rules_rag import format_docs, known_leagues, load_vectorstore


def resolve_league(name, leagues):
    """Case-insensitive match so `--league ecnl` works. Returns None if not indexed."""
    if not name:
        return None
    for lg in leagues:
        if lg.lower() == name.lower():
            return lg
    print(f"  (No documents indexed for '{name}'. Indexed leagues: {', '.join(leagues) or 'none'})")
    return None


def show_sources(docs):
    for d in docs:
        m = d.metadata
        page = f" p.{m['page']}" if m.get("page") else ""
        print(f"  - {m['league'] or 'Laws of the Game'}: {m['file']}{page}")


def show_queries(queries):
    for i, q in enumerate(queries):
        print(f"  {'original' if i == 0 else f'rewrite {i}'}: {q}")


def show_route(result: Result):
    r = result.route
    extra = f", league: {result.league_used or 'all'}" if r.destination == "rules" else ""
    print(f"  (routed to: {r.destination}{extra})")


def show_games(ga: assignments.GameAnswer):
    print(f"\nFilters: {assignments.describe(ga.query)}")
    more = f" (showing {len(ga.games)} of {ga.total_matched})" if ga.total_matched > len(ga.games) else ""
    print(f"Games matched: {ga.total_matched}{more}")
    for i, g in enumerate(ga.games):
        print("  " + assignments.format_game(i + 1, g, ga.query.positions))
    if any("est" in assignments.format_pay(g, ga.query.positions) for g in ga.games):
        print("  (~$ est = average of your past pay at that site for that position)")
    age_h = (datetime.now() - ga.exported_at).total_seconds() / 3600
    stale = f"  ⚠ over {config.ASSIGNMENTS_STALE_HOURS}h old — re-run the scraper for current openings" \
        if age_h > config.ASSIGNMENTS_STALE_HOURS else ""
    print(f"Data from {ga.exported_at:%a %b %d %I:%M%p}{stale}")


def print_result(result: Result):
    show_route(result)
    print(result.text)
    if result.games:
        show_games(result.games)


def main():
    parser = argparse.ArgumentParser(description="Ask the referee assistant.")
    parser.add_argument("question", nargs="*", help="Your question (omit for interactive mode)")
    parser.add_argument("--league", help=f"Use this league's rules (folder name under {config.LEAGUES_DIR.name}/); overrides a league named in the question")
    parser.add_argument("--show-context", action="store_true", help="Print the rewritten queries and retrieved chunks (for debugging)")
    parser.add_argument("--no-multi-query", action="store_true", help="Search only the original question (to compare results)")
    parser.add_argument("--no-adaptive", action="store_true", help="Skip the self-checks (relevance grading, answer verification)")
    args = parser.parse_args()

    if not os.getenv("ANTHROPIC_API_KEY"):
        sys.exit("Set ANTHROPIC_API_KEY first, e.g.  export ANTHROPIC_API_KEY=sk-ant-...")

    # No index yet is OK: questions about your games don't need the rulebooks
    store = load_vectorstore() if config.INDEX_DIR.exists() else None
    leagues = known_leagues(store) if store else []
    if not store:
        print("  (No rulebook index yet — rules questions need `python ingest.py` first.)")
    league = resolve_league(args.league, leagues)
    multi_query = not args.no_multi_query
    adaptive = not args.no_adaptive

    if args.question:
        result = handle(store, " ".join(args.question), leagues, league, multi_query, adaptive=adaptive)
        print_result(result)
        if result.docs:
            print("\nSources:")
            show_sources(result.docs)
        if args.show_context and result.trace:
            print("\nSteps:")
            for step in result.trace:
                print(f"  - {step}")
        if args.show_context and result.queries:
            print("\nSearched:")
            show_queries(result.queries)
            print("\n" + format_docs(result.docs))
        return

    print("Referee assistant. Commands: league <NAME> | league | sources | queries | steps | quit")
    print(f"Indexed leagues: {', '.join(leagues) or 'none (Laws only)'}")
    last = None
    while True:
        try:
            q = input(f"\n[{league or 'auto'}] > ").strip()
        except (EOFError, KeyboardInterrupt):
            break
        if not q:
            continue
        if q == "quit":
            break
        if q == "sources":
            show_sources(last.docs if last else [])
            continue
        if q == "steps":
            for step in (last.trace if last else []):
                print(f"  - {step}")
            continue
        if q == "queries":
            show_queries(last.queries if last else [])
            continue
        if q.split()[0] == "league":
            parts = q.split(maxsplit=1)
            league = resolve_league(parts[1], leagues) if len(parts) > 1 else None
            print(f"  League: {league or 'auto (detected from each question)'}")
            continue
        last = handle(store, q, leagues, league, multi_query, adaptive=adaptive)
        print_result(last)


if __name__ == "__main__":
    main()
