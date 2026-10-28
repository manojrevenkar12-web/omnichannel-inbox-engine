"""The agent as a LangGraph graph.

    turn ──► rules ──► (handoff | no reply | greeting)
               │
               ▼
            retrieve ──► (handoff when a question has no entry) ──► draft ──► verify ──► reply | handoff

The graph proposes; the inbox service decides whether the proposal may be sent (window, limits,
confidence). Every path ends in exactly one action and at most one message: on WhatsApp each
message is billed, and a turn that answers three questions answers them in one message.
"""

from __future__ import annotations

import re
from typing import Any, TypedDict

from langgraph.graph import END, START, StateGraph

from inbox_agent import rules
from inbox_agent.knowledge import Entry, Knowledge
from inbox_agent.model import GREETING, Drafter, default_drafter
from inbox_agent.text import is_question, language, sentences
from inbox_agent.verify import unsupported

# An entry answers a question only if the question uses one of the entry's keywords and the entry
# knows at least half of the question's words: BM25 alone finds a "best" entry for any text,
# including "do you do root canals on Sundays?", which shares one word with the opening hours.
MIN_COVERAGE = 0.5
MIN_MARGIN = 1.15  # the best entry must beat the second by this factor, or the question is ambiguous
CLAUSE = re.compile(r"(\band\b|\bund\b|\balso\b|\baußerdem\b|\bplus\b|,)", re.I)


class State(TypedDict, total=False):
    workspace: str
    window_open: bool
    turn: str
    lang: str
    questions: list[str]
    entries: list[Entry]
    scores: list[float]
    draft: str
    cited: list[str]
    model: str
    action: str
    text: str | None
    reason: str
    confidence: float


def build(drafter: Drafter | None = None, knowledge: dict[str, Knowledge] | None = None) -> Any:
    drafter = drafter or default_drafter()
    kb_cache: dict[str, Knowledge] = dict(knowledge or {})

    def kb(ws: str) -> Knowledge:
        if ws not in kb_cache:
            kb_cache[ws] = Knowledge.load(ws)
        return kb_cache[ws]

    def gate(state: State) -> State:  # LangGraph passes the state as `state`

        s = state
        lang = language(s["turn"])
        if not s.get("window_open", True):
            return {"lang": lang, "action": "handoff", "reason": "reply window closed", "confidence": 1.0}
        r = rules.check(s["turn"])
        if r.kind in ("handoff", "injection"):
            return {"lang": lang, "action": "handoff", "reason": r.reason, "confidence": 0.95}
        if r.kind == "ack":
            return {"lang": lang, "action": "no_reply", "reason": r.reason, "confidence": 0.9}
        if r.kind == "greeting":
            return {"lang": lang, "action": "reply", "text": GREETING[lang], "reason": "greeting", "confidence": 0.9, "cited": []}
        return {"lang": lang}

    def after_gate(s: State) -> str:
        return END if s.get("action") else "retrieve"

    def confident(ws: str, q: str) -> tuple[Entry, float] | None:
        hits = [h for h in kb(ws).search(q, k=5) if h.keyword_match]
        if not hits or hits[0].coverage < MIN_COVERAGE:
            return None
        if len(hits) > 1 and hits[0].score < MIN_MARGIN * hits[1].score:
            return None
        return hits[0].entry, hits[0].score

    def answer_for(ws: str, q: str) -> tuple[list[Entry], list[float]] | str:
        """The entries that answer ``q``, or why there are none.

        A sentence that asks two things ("how much is a cleaning and do you treat kids?") is split
        at its conjunctions first; answering only the part that scores highest would leave the
        customer's other question unanswered.
        """
        parts = [p.strip(" ,?") for p in CLAUSE.split(q) if p and p.strip(" ,?") and not CLAUSE.fullmatch(p.strip())]
        if len(parts) > 1:
            found = [confident(ws, part) for part in parts]
            distinct = {f[0].id for f in found if f}
            if all(found) and len(distinct) > 1:
                return [f[0] for f in found if f], [f[1] for f in found if f]
        whole = confident(ws, q)
        if whole:
            return [whole[0]], [whole[1]]
        hits = [h for h in kb(ws).search(q, k=5) if h.keyword_match]
        if not hits:
            return f"not in the knowledge: {q[:80]}"
        if len(hits) == 1 or hits[0].coverage < MIN_COVERAGE:
            return f"only partly covered by {hits[0].entry.id}: {q[:80]}"
        return f"ambiguous between {hits[0].entry.id} and {hits[1].entry.id}"

    def retrieve(state: State) -> State:  # LangGraph passes the state as `state`

        s = state
        questions = [q for q in sentences(s["turn"]) if is_question(q)] or [s["turn"]]
        found: list[Entry] = []
        scores: list[float] = []
        for q in questions:
            r = answer_for(s["workspace"], q)
            if isinstance(r, str):
                return {"questions": questions, "action": "handoff", "reason": r, "confidence": 0.8}
            for e, sc in zip(*r, strict=True):
                if e not in found:
                    found.append(e)
                    scores.append(sc)
        return {"questions": questions, "entries": found, "scores": scores}

    def after_retrieve(s: State) -> str:
        return END if s.get("action") else "draft"

    def draft(state: State) -> State:  # LangGraph passes the state as `state`

        s = state
        d = drafter.draft(s["questions"], s["entries"], s["lang"])
        return {"draft": d.text, "cited": d.cited, "model": d.model}

    def verify(state: State) -> State:  # LangGraph passes the state as `state`

        s = state
        cited = [e for e in s["entries"] if e.id in set(s["cited"])]
        bad = unsupported(s["draft"], cited)
        if bad or not s["draft"].strip():
            return {"action": "handoff", "reason": f"draft not supported by the knowledge: {sorted(bad)[:3]}", "confidence": 0.8}
        conf = min(0.97, 0.55 + 0.05 * min(s["scores"]))
        return {"action": "reply", "text": s["draft"], "reason": "answered from " + ", ".join(s["cited"]), "confidence": round(conf, 3)}

    g = StateGraph(State)
    g.add_node("gate", gate)
    g.add_node("retrieve", retrieve)
    g.add_node("draft", draft)
    g.add_node("verify", verify)
    g.add_edge(START, "gate")
    g.add_conditional_edges("gate", after_gate, {END: END, "retrieve": "retrieve"})
    g.add_conditional_edges("retrieve", after_retrieve, {END: END, "draft": "draft"})
    g.add_edge("draft", "verify")
    g.add_edge("verify", END)
    return g.compile()


def current_turn(messages: list[dict[str, Any]]) -> str:
    """The customer's messages since the business last wrote: everything this turn must answer."""
    out: list[str] = []
    for m in reversed(messages):
        if m.get("author", "").lower() != "customer":
            break
        out.append(str(m.get("text", "")))
    return "\n".join(reversed(out)).strip()
