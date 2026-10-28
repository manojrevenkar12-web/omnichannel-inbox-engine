"""Checks a drafted answer against the knowledge it cites before it may be sent.

Every number, time, price, phone number, link and email address in the draft must appear in a
cited entry. A draft that fails is not sent; the turn goes to a person.
"""

from __future__ import annotations

import re

from inbox_agent.knowledge import Entry

_FACT = re.compile(
    r"(https?://\S+|[\w.-]+\.(?:example|de|com)\b|\+?\d[\d \-]{6,}\d|\d{1,2}:\d{2}|\d+(?:[.,]\d+)?\s?(?:eur|€|%)|\d+)",
    re.I,
)


def facts(text: str) -> set[str]:
    return {re.sub(r"\s+", "", m.group(0)).lower().rstrip(".,") for m in _FACT.finditer(text)}


def unsupported(draft: str, cited: list[Entry]) -> set[str]:
    allowed: set[str] = set()
    for e in cited:
        for t in e.text.values():
            allowed |= facts(t)
    return {f for f in facts(draft) if f not in allowed}
