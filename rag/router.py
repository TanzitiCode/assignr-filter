"""
ROUTING (video 0:52) — decide where a question should go.

Uses "logical routing" from the video: the LLM is told what each data source contains and
returns a structured object (a Pydantic model) instead of free text, so the code can
branch on it reliably.

Destinations:
  rules        -> Laws of the Game + league rulebooks (rules_rag.py)
  assignments  -> your scraped Assignr games in assignments.json (assignments.py, step 5)
  general      -> refereeing questions the rulebooks don't cover (game management,
                  fitness, positioning tips...) or small talk — answered from the LLM's
                  own knowledge and clearly labeled as such

The router also picks out a league if the question names one ("U12 NCSL half length"),
so you don't always need --league.
"""
from typing import Literal, Optional

from langchain_core.language_models import BaseChatModel
from langchain_core.prompts import ChatPromptTemplate
from pydantic import BaseModel, Field


class Route(BaseModel):
    destination: Literal["rules", "assignments", "general"] = Field(
        description="Where to send the question"
    )
    league: Optional[str] = Field(
        default=None,
        description="The league named or clearly implied in the question, exactly as written in the known-leagues list; null if none",
    )


ROUTER_PROMPT = ChatPromptTemplate.from_messages([
    ("system", """You route questions from a soccer referee to the right data source.

- rules: anything answered by the Laws of the Game or a league's rules — fouls, misconduct, restarts,
  offside, procedures, game length, substitutions, age-group rules, roster/pass checks, league policies.
- assignments: the referee's available or upcoming games from Assignr — which games are open, dates,
  times, pay, distance, venues, levels, positions, "what should I take this weekend".
- general: refereeing questions a rulebook doesn't answer (handling coaches, fitness, positioning tips,
  career/upgrade advice) and anything else.

Known leagues: {leagues}
If the question names one of these leagues (or an obvious variant, e.g. "MLS Next" for "MLS-NEXT"),
return it exactly as spelled in the list. Otherwise return null."""),
    ("human", "{question}"),
])


def route_question(question: str, leagues: list[str], llm: BaseChatModel) -> Route:
    # json_schema = Claude's native structured outputs (forced tool calling isn't supported on newer models)
    router = ROUTER_PROMPT | llm.with_structured_output(Route, method="json_schema")
    raw = router.invoke({"question": question, "leagues": ", ".join(leagues) or "none"})
    return normalize(raw, leagues)


def normalize(route: Route, leagues: list[str]) -> Route:
    """Keep the league only if it's one we actually have documents for (case/punctuation-insensitive)."""
    if not route.league:
        return route
    simplify = lambda s: "".join(ch for ch in s.lower() if ch.isalnum())
    match = next((lg for lg in leagues if simplify(lg) == simplify(route.league)), None)
    return route.model_copy(update={"league": match})
