"""Language, sentence and token handling for English and German customer messages."""

from __future__ import annotations

import re

_TOKEN = re.compile(r"[a-zäöüß0-9]+(?:-[a-zäöüß0-9]+)*", re.IGNORECASE)
_DE = {
    "ich",
    "sie",
    "wir",
    "und",
    "ist",
    "nicht",
    "ein",
    "eine",
    "der",
    "die",
    "das",
    "mit",
    "für",
    "haben",
    "habe",
    "wann",
    "wie",
    "was",
    "kann",
    "können",
    "bitte",
    "danke",
    "hallo",
    "guten",
    "morgen",
    "tag",
    "termin",
    "zahn",
    "noch",
    "auch",
    "mein",
    "meine",
    "gibt",
    "es",
    "sind",
    "uhr",
    "heute",
    "schon",
    "möchte",
    "würde",
    "bei",
    "ihnen",
    "geöffnet",
    "wo",
}
_EN = {
    "i",
    "you",
    "we",
    "and",
    "is",
    "not",
    "a",
    "an",
    "the",
    "with",
    "for",
    "have",
    "when",
    "how",
    "what",
    "can",
    "please",
    "thanks",
    "thank",
    "hello",
    "hi",
    "my",
    "do",
    "does",
    "are",
    "open",
    "today",
    "tomorrow",
    "would",
    "like",
    "where",
    "there",
    "your",
}
_STEM = re.compile(r"(ungen|ung|en|er|es|e|s)$")
_QUESTION_START = re.compile(
    r"^(when|what|where|how|do|does|did|can|could|is|are|will|would|should|which|who|why|wann|was|wo|wie|welche|welcher|kann|können|"
    r"könnten|haben|hat|gibt|ist|sind|muss|darf|bieten|nehmen|machen|behandeln)\b",
    re.IGNORECASE,
)


STOP = {
    "a",
    "an",
    "the",
    "is",
    "are",
    "be",
    "do",
    "does",
    "did",
    "can",
    "could",
    "i",
    "you",
    "we",
    "my",
    "your",
    "our",
    "it",
    "to",
    "of",
    "in",
    "on",
    "at",
    "for",
    "and",
    "or",
    "with",
    "me",
    "there",
    "this",
    "that",
    "how",
    "what",
    "much",
    "many",
    "please",
    "hi",
    "have",
    "has",
    "get",
    "will",
    "would",
    "should",
    "if",
    "any",
    "some",
    "also",
    "hello",
    "thanks",
    "happens",
    "when",
    "der",
    "die",
    "das",
    "ein",
    "eine",
    "einen",
    "ist",
    "sind",
    "ich",
    "sie",
    "wir",
    "mein",
    "meine",
    "ihr",
    "ihre",
    "und",
    "oder",
    "mit",
    "für",
    "zu",
    "im",
    "am",
    "auf",
    "es",
    "kann",
    "können",
    "bitte",
    "wie",
    "was",
    "viel",
    "noch",
    "auch",
    "man",
    "haben",
    "hat",
    "habe",
    "gibt",
    "wird",
    "würde",
    "wenn",
    "hallo",
    "danke",
}


def search_tokens(text: str) -> list[str]:
    return [t for t in tokens(text) if t not in STOP]


def tokens(text: str) -> list[str]:
    return [t.lower() for t in _TOKEN.findall(text)]


def stem(tok: str) -> str:
    """A light suffix strip so 'Termine', 'Terminen' and 'Termin' meet. Short words are left alone."""
    return _STEM.sub("", tok) if len(tok) > 5 else tok


def language(text: str) -> str:
    toks = tokens(text)
    de = sum(t in _DE for t in toks) + sum(ch in text for ch in "äöüß")
    en = sum(t in _EN for t in toks)
    return "de" if de > en else "en"


def sentences(text: str) -> list[str]:
    parts = re.split(r"(?<=[.!?])\s+|\n+", text.strip())
    return [p.strip() for p in parts if p.strip()]


def is_question(sentence: str) -> bool:
    s = sentence.strip()
    return s.endswith("?") or bool(_QUESTION_START.match(s))
