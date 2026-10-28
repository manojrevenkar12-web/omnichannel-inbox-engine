"""Rules that decide before any model is asked: what must go to a person, what needs no answer.

A model is good at wording an answer. Whether a customer in pain, a complaint, a request for a
person or a message trying to instruct the assistant goes to a human is a business rule, so it is
decided here, in code, and tested.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from inbox_agent.text import is_question

_HANDOFF: list[tuple[str, re.Pattern[str]]] = [
    (
        "asked for a person",
        re.compile(
            r"\b(human|real person|a person|receptionist|call me|phone me|call me back|speak (to|with)|talk (to|with)|"
            r"(someone|somebody) (call|ring|phone|get back)|"
            r"mitarbeiter\w*|mensch|jemand\w* (an|zurück)?(rufen|ruft|melden|meldet)|persönlich|rückruf|ruf\w* mich|sprechen mit)\b",
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
            r"mein (befund|röntgenbild|heil- und kostenplan|kostenplan)|"
            r"(change|update|correct) my (address|phone|number|email|details|data|name)|i('ve| have)? moved|delete my (data|details)|"
            r"(adresse|telefonnummer|nummer|daten|e-mail|namen?) (ändern|aktualisieren|löschen|korrigieren)|umgezogen|meine daten)\b",
            re.I,
        ),
    ),
]

_INJECTION = re.compile(
    r"(ignore (all |the )?(previous|prior|above) (instructions|messages)|disregard (your|the) (rules|instructions)|"
    r"system ?prompt|you are now|from now on|act as|pretend (to be|you)|role ?play|developer mode|respond only (in|as|with)|"
    r"ignoriere (alle |die )?(vorherigen|bisherigen) (anweisungen|nachrichten)|vergiss (alle|deine)|"
    r"du bist (jetzt|nun|ab jetzt|ab sofort)|ab (jetzt|sofort) (bist|antwortest|sprichst)|antworte(st)? nur noch|tu so als)",
    re.I,
)
_ACK_WORDS = set(
    [
        "ok",
        "okay",
        "k",
        "kk",
        "thanks",
        "thank",
        "thx",
        "ty",
        "you",
        "so",
        "much",
        "great",
        "perfect",
        "super",
        "cool",
        "alright",
        "got",
        "it",
        "fine",
        "nice",
        "awesome",
        "lovely",
        "wonderful",
        "sounds",
        "good",
        "will",
        "do",
        "sure",
        "yes",
        "yep",
        "see",
        "then",
        "later",
        "bye",
        "cheers",
        "danke",
        "dankeschön",
        "schön",
        "sehr",
        "vielen",
        "dank",
        "alles",
        "klar",
        "prima",
        "passt",
        "gut",
        "top",
        "perfekt",
        "toll",
        "klasse",
        "bis",
        "dann",
        "bald",
        "später",
        "tschüss",
        "ciao",
        "ja",
        "genau",
        "gerne",
        "hat",
        "geklappt",
        "wunderbar",
    ]
)
_ACK_STARTERS = set(
    [
        "ok",
        "okay",
        "thanks",
        "thank",
        "thx",
        "danke",
        "super",
        "perfect",
        "perfekt",
        "great",
        "prima",
        "alles",
        "cool",
        "top",
        "toll",
        "klasse",
        "wunderbar",
    ]
)
_CLOSING = re.compile(r"\b(see you|bis (bald|dann|später|morgen|montag|dienstag|mittwoch|donnerstag|freitag|samstag))\b", re.I)
_GREETING = re.compile(
    r"^(h+i+|hey+( there)?|hello( there)?|good (morning|afternoon|evening)|hallo( zusammen)?|guten (morgen|tag|abend)|moin( moin)?|servus|hi there)[,.!? ]*$",
    re.I,
)
_WORD = re.compile(r"[a-zäöüß']+", re.I)
_REQUEST = re.compile(
    r"\b(need|want|would like|move|cancel|book|change|reschedule|send|brauche|möchte|will|würde gern|verschieben|absagen|buchen|ändern|schicken)\b",
    re.I,
)


def _is_ack(line: str) -> bool:
    """Thanks, ok, emoji, a closing like "see you Thursday": nothing that asks or tells us anything to act on."""
    if "?" in line or _REQUEST.search(line) or is_question(line):
        return False
    words = [w.lower() for w in _WORD.findall(line)]
    if not words:
        return True  # emoji or punctuation only
    if all(w in _ACK_WORDS for w in words):
        return True
    return bool(_CLOSING.search(line)) or (
        words[0] in _ACK_STARTERS and sum(w not in _ACK_WORDS for w in words) <= 4 and "bitte" not in words
    )


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
    if lines and all(_is_ack(ln) for ln in lines):
        return RuleResult("ack", "acknowledgement: nothing to answer")
    if lines and all(_GREETING.match(ln) for ln in lines):
        return RuleResult("greeting", "greeting only")
    return RuleResult("none")
