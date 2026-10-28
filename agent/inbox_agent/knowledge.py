"""The business's knowledge, and BM25 retrieval over it in English and German."""

from __future__ import annotations

import math
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

import yaml

from inbox_agent.text import search_tokens, stem

KNOWLEDGE_DIR = Path(__file__).resolve().parent.parent / "knowledge"


@dataclass(frozen=True)
class Entry:
    id: str
    keywords: tuple[str, ...]
    text: dict[str, str]


@dataclass(frozen=True)
class Hit:
    entry: Entry
    score: float
    keyword_match: bool
    coverage: float  # share of the question's words the entry knows (keywords or answer text)


class Knowledge:
    def __init__(self, business: str, entries: list[Entry]):
        self.business = business
        self.entries = entries
        self._docs = [self._doc(e) for e in entries]
        self._keys = [{stem(t) for k in e.keywords for t in search_tokens(k)} for e in entries]
        self._df: Counter[str] = Counter()
        for d in self._docs:
            self._df.update(set(d))
        self._avg = sum(len(d) for d in self._docs) / max(len(self._docs), 1)

    @staticmethod
    def _doc(e: Entry) -> list[str]:
        # keywords count three times: they are what a customer types, the answer text is what we say
        words = [stem(t) for k in e.keywords for t in search_tokens(k)] * 3
        for txt in e.text.values():
            words += [stem(t) for t in search_tokens(txt)]
        return words

    @classmethod
    def load(cls, workspace: str, root: Path = KNOWLEDGE_DIR) -> Knowledge:
        path = root / f"{workspace}.yaml"
        if not path.exists():
            return cls("unknown", [])
        raw = yaml.safe_load(path.read_text())
        entries = [Entry(e["id"], tuple(e.get("keywords", [])), {k: e[k] for k in ("en", "de") if k in e}) for e in raw["entries"]]
        return cls(raw.get("business", workspace), entries)

    def search(self, query: str, k: int = 3) -> list[Hit]:
        q = [stem(t) for t in search_tokens(query)]
        n = len(self._docs)
        hits = []
        for e, d, keys in zip(self.entries, self._docs, self._keys, strict=True):
            tf = Counter(d)
            s = 0.0
            for t in set(q):
                if t not in tf:
                    continue
                idf = math.log(1 + (n - self._df[t] + 0.5) / (self._df[t] + 0.5))
                s += idf * tf[t] * 2.2 / (tf[t] + 1.2 * (0.25 + 0.75 * len(d) / self._avg))
            if s > 0:
                qs = set(q)
                hits.append(Hit(e, s, bool(keys & qs), len(qs & tf.keys()) / len(qs)))
        return sorted(hits, key=lambda h: -h.score)[:k]

    def entry(self, id: str) -> Entry | None:
        return next((e for e in self.entries if e.id == id), None)
