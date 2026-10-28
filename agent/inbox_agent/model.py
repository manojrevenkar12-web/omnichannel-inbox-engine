"""The model that words an answer from retrieved knowledge.

The default is a deterministic stand-in (reproducible, free, and honest about what it is). A
Claude-backed drafter is used when ``ANTHROPIC_API_KEY`` is set; both are held to the same
verifier, so a drafted claim that is not in the knowledge never reaches a customer.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Protocol

import httpx

from inbox_agent.knowledge import Entry

GREETING = {
    "en": "Hello! How can we help you today?",
    "de": "Hallo! Wie können wir Ihnen helfen?",
}


@dataclass(frozen=True)
class Draft:
    text: str
    cited: list[str]
    model: str


class Drafter(Protocol):
    name: str

    def draft(self, questions: list[str], entries: list[Entry], lang: str) -> Draft: ...


class SimulatedDrafter:
    """Joins the answers to every question of the turn into one message, in the customer's language."""

    name = "simulated"

    def draft(self, questions: list[str], entries: list[Entry], lang: str) -> Draft:
        seen: list[Entry] = []
        for e in entries:
            if e not in seen:
                seen.append(e)
        text = " ".join(e.text.get(lang) or e.text["en"] for e in seen)
        return Draft(text, [e.id for e in seen], self.name)


class ClaudeDrafter:
    """Claude through the Messages API, told to answer only from the given entries, in one message."""

    name = "claude"

    def __init__(self, model: str | None = None):
        self.model = model or os.environ.get("AGENT_MODEL", "claude-sonnet-4-5")
        self.key = os.environ["ANTHROPIC_API_KEY"]

    def draft(self, questions: list[str], entries: list[Entry], lang: str) -> Draft:
        facts = "\n".join(f"[{e.id}] {e.text.get(lang) or e.text['en']}" for e in entries)
        prompt = (
            "You answer a customer of a small business in one short message, in "
            + ("German" if lang == "de" else "English")
            + ". Use only the facts below and change no number, time, price, address or link. "
            "Answer every question; if a fact is missing, say a colleague will follow up.\n\nFacts:\n"
            + facts
            + "\n\nCustomer:\n"
            + "\n".join(questions)
        )
        r = httpx.post(
            "https://api.anthropic.com/v1/messages",
            headers={"x-api-key": self.key, "anthropic-version": "2023-06-01", "content-type": "application/json"},
            json={"model": self.model, "max_tokens": 300, "messages": [{"role": "user", "content": prompt}]},
            timeout=20,
        )
        r.raise_for_status()
        text = "".join(b.get("text", "") for b in r.json().get("content", []))
        return Draft(text.strip(), [e.id for e in entries], self.model)


def default_drafter() -> Drafter:
    return ClaudeDrafter() if os.environ.get("ANTHROPIC_API_KEY") else SimulatedDrafter()
