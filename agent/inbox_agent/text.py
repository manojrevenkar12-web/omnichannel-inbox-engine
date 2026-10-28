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
    r"^(?:(?:and|und|also|so|plus|ok|oh|btw|hi|hey|moin|hallo)[,!]?\s+)*"
    r"(whats|what's|hows|wheres|wieviel|when|what|where|how|do|does|did|can|could|is|are|will|would|should|which|who|why|wann|was|wo|wie|welche|welcher|kann|können|"
    r"könnten|haben|hat|gibt|ist|sind|muss|darf|bieten|nehmen|machen|behandeln|any|anyone)\b",
    re.IGNORECASE,
)
# German yes/no questions put the verb first: "Habt ihr …", "Liefert ihr …", "Spricht jemand …".
_DE_VERB_FIRST = re.compile(r"^(?:(?:und|also|hallo|moin)[,!]?\s+)*[a-zäöüß]+(?:t|st|e|en)\s+(ihr|du|sie|ich|man|jemand|es)\b", re.I)


# Function words: they say nothing about what is asked. Generic lists, not tuned to any answer.
_STOP_EN = """
a about above after again all also am an and any are as at be because been before being both but by can could
did do does doing dont don't down during each else even ever every exactly few for from further get gets got had has
have having he her here hers him his how however i if im i'm in into is isnt it its it's itself just kind let lets
me more most much must my myself no nor not now of off often ok on once only or other our ours out over own
please possible probably quite rather really same she shes she's should so some somehow something soon sorry still
such than that thats that's the their them then there these they this those though through to too under until up
us very was we were what whats what's when whenever where whether which while who whom why will with would yet you
your yours hi hii hello hey thanks thank happens maybe anyway again u ur r pls plz roughly approximately just
"""
_STOP_DE = """
aber alle allem allen aller alles also am an ander andere anderem anderen anderer anderes auch auf aus bei bin bis
bist bitte da damit dann das dass dein deine dem den denn der des dessen deshalb die dies diese diesem diesen dieser
dieses doch dort du durch ein eine einem einen einer eines einfach er es etwa etwas euch euer eure für gibt gerne
habe haben hat hatte hätte hier ihm ihn ihnen ihr ihre im in ist ja jetzt kann kein keine können könnte man mal
mein meine mich mir mit muss müssen nach nicht noch nur ob oder ohne schon sehr sein seine selbst sich sie sind so
soll sollte sonst um und uns unser unsere vom von vor war wäre was weil welche welcher wenn wer wie wieder will wir
wird wo wohl würde zu zum zur hallo danke moin viel genau eigentlich ich guten tag abend
"""
# Question words that carry a topic (where → address, wann → hours) stay searchable.
STOP = (set(_STOP_EN.split()) | set(_STOP_DE.split())) - {"where", "wo", "wann"}


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
    return s.endswith("?") or bool(_QUESTION_START.match(s) or _DE_VERB_FIRST.match(s))
