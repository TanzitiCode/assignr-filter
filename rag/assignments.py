"""
QUERY CONSTRUCTION (video 0:59) — turn a plain-English question about your games into
a structured filter, then run it against assignments.json (written by the Java scraper).

    "varsity center games this Saturday over $60 within 20 miles"
        -> GameQuery(weekdays=["Saturday"], date_from=..., levels=["varsity"],
                     positions=["center"], min_pay=60, max_distance_miles=20)
        -> filter + sort the games in Python (exact, no guessing)
        -> the LLM writes a short answer using only the matching games

Like the Java filter, pay = 0 and distance = 0 mean "unknown", and those games are kept
(and labeled) rather than silently dropped.
"""
import json
from dataclasses import dataclass
from datetime import date, datetime, time
from typing import Literal, Optional

from langchain_core.language_models import BaseChatModel
from langchain_core.output_parsers import StrOutputParser
from langchain_core.prompts import ChatPromptTemplate
from pydantic import BaseModel, Field

import config

WEEKDAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]


class GameQuery(BaseModel):
    """Every field is optional — only set what the question asks for."""
    date_from: Optional[str] = Field(None, description="Earliest game date, YYYY-MM-DD")
    date_to: Optional[str] = Field(None, description="Latest game date, YYYY-MM-DD (inclusive)")
    weekdays: Optional[list[Literal["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]]] = None
    earliest_time: Optional[str] = Field(None, description="Earliest kickoff, 24h HH:MM")
    latest_time: Optional[str] = Field(None, description="Latest kickoff, 24h HH:MM")
    min_pay: Optional[float] = Field(None, description="Minimum pay in dollars")
    max_distance_miles: Optional[float] = None
    leagues: Optional[list[str]] = Field(None, description="League names, e.g. ECNL, VHSL, MLS NEXT")
    levels: Optional[list[str]] = Field(None, description="Levels or age groups, e.g. varsity, jv, U15, collegiate")
    positions: Optional[list[Literal["center", "AR", "4th"]]] = Field(
        None, description="center = the referee in the middle; AR = assistant referee / linesman")
    keyword: Optional[str] = Field(None, description="A team, school, club, or venue name to look for")
    sort_by: Literal["time", "pay", "distance", "score"] = Field(
        "time", description="score = the referee's own ranking from their preferences; use for 'best'/'recommend'")
    limit: int = Field(10, description="Max games to return")


QUERY_PROMPT = ChatPromptTemplate.from_messages([
    ("system", """Convert a soccer referee's question about their available games into search filters.
Today is {today} ({weekday}). Resolve relative dates ("this weekend", "next Saturday", "tomorrow") into exact dates.
"This weekend" = the coming Saturday and Sunday (today too, if today is one of them).
Only set filters the question actually asks for. Leave everything else null.
Leagues that appear in the data: {leagues}
Levels that appear in the data: {levels}"""),
    ("human", "{question}"),
])

ANSWER_PROMPT = ChatPromptTemplate.from_template(
    """You are helping a soccer referee pick games. Answer their question using ONLY the games listed below.
Be brief. Refer to games by their # number. If no games are listed, say nothing matched and suggest which filter to relax.
"pay ?" or "dist ?" means unknown — mention that if it matters for the recommendation.

Filters applied: {filters}
Games ({count} shown{more}):
{games}

Question: {question}"""
)


# ── Loading ────────────────────────────────────────────────────────────────────

@dataclass
class GameData:
    games: list[dict]
    exported_at: datetime


def load_games() -> GameData:
    path = config.ASSIGNMENTS_FILE
    if not path.exists():
        raise FileNotFoundError(
            f"{path.name} not found. Run the Java scraper first (java -jar target/assignr-filter-1.0-SNAPSHOT.jar).")
    with open(path, encoding="utf-8") as f:
        games = [clean(g) for g in json.load(f)]
    return GameData(games, datetime.fromtimestamp(path.stat().st_mtime))


def clean(g: dict) -> dict:
    """The scraper sometimes captures extra lines, e.g. league = "DC SCORES\\nAssignor: Jonathan Loney".
    Keep the first line for league/level, and move the assignor name into its own field."""
    g = dict(g)
    for key in ("league", "gameLevel"):
        value = g.get(key) or ""
        first, *rest = value.split("\n")
        g[key] = first.strip()
        for line in rest:
            if line.lower().strip().startswith("assignor:") and not g.get("assignor"):
                g["assignor"] = line.split(":", 1)[1].strip()
    return g


def distinct(games: list[dict], key: str) -> list[str]:
    return sorted({g[key] for g in games if g.get(key)})


# ── Filtering (plain Python — exact, testable, no LLM) ────────────────────────

def simplify(s: Optional[str]) -> str:
    return "".join(ch for ch in (s or "").lower() if ch.isalnum())


def game_time(g: dict) -> Optional[datetime]:
    try:
        return datetime.fromisoformat(g["gameTime"]) if g.get("gameTime") else None
    except ValueError:
        return None


def position_kinds(position: str) -> set[str]:
    """'Referee, AR1, AR2' -> {'center', 'AR'}. 'Open' or blank -> unknown (empty set)."""
    kinds = set()
    for p in (position or "").split(","):
        p = simplify(p)
        if p.startswith("ar") or p.startswith("asst") or p.startswith("assistant") or p.startswith("line"):
            kinds.add("AR")
        elif p.startswith("4") or p.startswith("fourth"):
            kinds.add("4th")
        elif p in ("r", "cr", "ref", "center", "centre") or p.startswith("referee"):
            kinds.add("center")
    return kinds


def parse_date(s: Optional[str]) -> Optional[date]:
    try:
        return date.fromisoformat(s) if s else None
    except ValueError:
        return None


def parse_time(s: Optional[str]) -> Optional[time]:
    try:
        return time.fromisoformat(s) if s else None
    except ValueError:
        return None


def matches(g: dict, q: GameQuery) -> bool:
    t = game_time(g)
    d_from, d_to = parse_date(q.date_from), parse_date(q.date_to)
    t_from, t_to = parse_time(q.earliest_time), parse_time(q.latest_time)

    # Date/time filters: a game with no time can't be confirmed, so it's excluded when one is set
    if (d_from or d_to or q.weekdays or t_from or t_to) and not t:
        return False
    if d_from and t.date() < d_from:
        return False
    if d_to and t.date() > d_to:
        return False
    if q.weekdays and WEEKDAYS[t.weekday()] not in q.weekdays:
        return False
    if t_from and t.time() < t_from:
        return False
    if t_to and t.time() > t_to:
        return False

    # Pay/distance: 0 means unknown -> keep (same rule as AssignmentFilter.java)
    if q.min_pay is not None and g.get("pay") and g["pay"] < q.min_pay:
        return False
    if q.max_distance_miles is not None and g.get("distanceMiles") and g["distanceMiles"] > q.max_distance_miles:
        return False

    if q.leagues and not any(simplify(lg) in simplify(f"{g.get('league')} {g.get('gameLevel')}") for lg in q.leagues):
        return False
    if q.levels and not any(simplify(lv) in simplify(g.get("gameLevel")) for lv in q.levels):
        return False
    if q.positions:
        kinds = position_kinds(g.get("position"))
        if kinds and not kinds & set(q.positions):  # unknown positions are kept
            return False
    if q.keyword:
        haystack = simplify(" ".join(str(g.get(k) or "") for k in
                                     ("homeTeam", "awayTeam", "venueName", "venueAddress", "league",
                                      "gameLevel", "assignrName", "assignor")))
        if simplify(q.keyword) not in haystack:
            return False
    return True


def sort_games(games: list[dict], sort_by: str) -> list[dict]:
    far_future = datetime.max
    keys = {
        "time": lambda g: game_time(g) or far_future,
        "pay": lambda g: -(g.get("pay") or 0),
        "distance": lambda g: g.get("distanceMiles") or float("inf"),  # unknown distance last
        "score": lambda g: -(g.get("score") or 0),
    }
    return sorted(games, key=keys[sort_by])


def run_query(games: list[dict], q: GameQuery) -> tuple[list[dict], int]:
    """Returns (games to show, total number that matched)."""
    matched = sort_games([g for g in games if matches(g, q)], q.sort_by)
    return matched[: max(1, q.limit)], len(matched)


# ── Display ────────────────────────────────────────────────────────────────────

def format_game(i: int, g: dict) -> str:
    t = game_time(g)
    when = t.strftime("%a %b %d %I:%M%p") if t else "time TBD"
    pay = f"${g['pay']:.0f}" if g.get("pay") else "pay ?"
    dist = f"{g['distanceMiles']:.1f} mi" if g.get("distanceMiles") else "dist ?"
    return (f"#{i} {when} | {g.get('homeTeam') or '?'} vs {g.get('awayTeam') or '?'} | "
            f"{g.get('league') or '-'} {g.get('gameLevel') or ''} | {g.get('position') or '-'} | "
            f"{pay} | {dist} | {g.get('venueName') or '-'}")


def describe(q: GameQuery) -> str:
    set_fields = q.model_dump(exclude_none=True, exclude={"sort_by", "limit"})
    parts = [f"{k}={v}" for k, v in set_fields.items()]
    return f"{', '.join(parts) or 'none'} (sorted by {q.sort_by})"


# ── Main entry ─────────────────────────────────────────────────────────────────

@dataclass
class GameAnswer:
    text: str
    query: GameQuery
    games: list[dict]
    total_matched: int
    exported_at: datetime


def build_query(question: str, data: GameData, llm: BaseChatModel, today: Optional[date] = None) -> GameQuery:
    today = today or date.today()
    builder = QUERY_PROMPT | llm.with_structured_output(GameQuery, method="json_schema")
    return builder.invoke({
        "question": question,
        "today": today.isoformat(),
        "weekday": WEEKDAYS[today.weekday()],
        "leagues": ", ".join(distinct(data.games, "league")) or "unknown",
        "levels": ", ".join(distinct(data.games, "gameLevel")) or "unknown",
    })


def answer(question: str, llm: BaseChatModel, data: Optional[GameData] = None) -> GameAnswer:
    data = data or load_games()
    q = build_query(question, data, llm)
    shown, total = run_query(data.games, q)
    games_text = "\n".join(format_game(i + 1, g) for i, g in enumerate(shown)) or "(none)"
    text = (ANSWER_PROMPT | llm | StrOutputParser()).invoke({
        "filters": describe(q),
        "count": len(shown),
        "more": f" of {total} matching" if total > len(shown) else "",
        "games": games_text,
        "question": question,
    })
    return GameAnswer(text, q, shown, total, data.exported_at)
