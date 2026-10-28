"""Rules that decide before any model is asked: what must go to a person, what needs no answer.

A model is good at wording an answer. Whether a customer in pain, a complaint, a request for a
person or a message trying to instruct the assistant goes to a human is a business rule, so it is
decided here, in code, and tested.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

_HANDOFF: list[tuple[str, re.Pattern[str]]] = [
    (
        "asked for a person",
        re.compile(
            r"\b(human|real person|a person|someone|somebody|staff|receptionist|call me|phone me|speak to|talk to|"
            r"mitarbeiter\w*|mensch|jemand\w*|persönlich|rückruf|ruf\w* mich|sprechen mit)\b",
            re.I,
        ),
    ),
    (
        "symptoms: a clinician decides",
        re.compile(
            r"\b(pain\w*|hurts?|hurting|ache|aching|swollen|swelling|bleed\w*|fever|abscess|broken tooth|chipped|cracked|"
            r"schmerz\w*|weh|tut weh|geschwollen|schwellung|blut\w*|fieber|abszess|abgebrochen|entzünd\w*)\b",
            re.I,
        ),
    ),
    (
        "complaint or money dispute",
        re.compile(
            r"\b(complain\w*|refund\w*|money back|overcharged|lawyer|unacceptable|disappointed|angry|rude|"
            r"beschwer\w*|erstatt\w*|geld zurück|anwalt|unverschämt|enttäuscht|frech|zu viel berechnet)\b",
            re.I,
        ),
    ),
    (
        "needs the customer's records",
        re.compile(
            r"\b(my (invoice|bill|x-?ray|results?|records?|file|treatment plan)|meine (rechnung|röntgen\w*|befund\w*|akte|unterlagen)|"
            r"mein (befund|röntgenbild|heil- und kostenplan|kostenplan))\b",
            re.I,
        ),
    ),
]

_INJECTION = re.compile(
    r"(ignore (all |the )?(previous|prior|above) (instructions|messages)|disregard (your|the) (rules|instructions)|"
    r"system prompt|you are now|act as|pretend to be|developer mode|ignoriere (alle |die )?(vorherigen|bisherigen) (anweisungen|nachrichten))",
    re.I,
)
_ACK = re.compile(
    r"^(ok(ay)?|thanks?( you)?( so much)?|thank you|great|perfect|super|cool|alright|got it|danke( schön| sehr)?|vielen dank|"
    r"alles klar|prima|passt|gut|top|👍|🙏|😊)[.! ]*$",
    re.I,
)
_GREETING = re.compile(r"^(hi|hello|hey|good (morning|afternoon|evening)|hallo|guten (morgen|tag|abend)|moin|servus)[,.! ]*$", re.I)


@dataclass(frozen=True)
class RuleResult:
    kind: str  # handoff | injection | ack | greeting | none
    reason: str = ""


def check(turn_text: str) -> RuleResult:
    if _INJECTION.search(turn_text):
        return RuleResult("injection", "message tries to instruct the assistant")
    for reason, pattern in _HANDOFF:
        if pattern.search(turn_text):
            return RuleResult("handoff", reason)
    lines = [ln.strip() for ln in turn_text.splitlines() if ln.strip()]
    if lines and all(_ACK.match(ln) for ln in lines):
        return RuleResult("ack", "acknowledgement: nothing to answer")
    if lines and all(_GREETING.match(ln) for ln in lines):
        return RuleResult("greeting", "greeting only")
    return RuleResult("none")
