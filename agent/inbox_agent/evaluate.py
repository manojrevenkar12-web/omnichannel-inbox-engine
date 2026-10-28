"""Evaluate the agent on a set of turns: did it take the right action, cite the right knowledge,
answer in the customer's language, and never send a claim the knowledge does not support?

Also measures what the turn-based design saves on WhatsApp, where every message is billed: a bot
that answers each fragment separately sends one message per fragment.
"""

from __future__ import annotations

import hashlib
import json
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import yaml

from inbox_agent.graph import build
from inbox_agent.knowledge import Knowledge
from inbox_agent.text import language
from inbox_agent.verify import unsupported

ROOT = Path(__file__).resolve().parent.parent


def load_set(name: str) -> tuple[list[dict[str, Any]], Path]:
    path = ROOT / "eval" / f"{name}.yaml" if name == "dev" else ROOT / "heldout" / name / "turns.yaml"
    return yaml.safe_load(path.read_text())["turns"], path


def evaluate(turns: list[dict[str, Any]]) -> dict[str, Any]:
    g = build()
    rows = []
    for t in turns:
        text = "\n".join(t["fragments"])
        s = g.invoke({"workspace": t["ws"], "window_open": t.get("window_open", True), "turn": text})
        action = s.get("action", "handoff")
        cited = list(s.get("cited", [])) if action == "reply" else []
        kb = Knowledge.load(t["ws"])
        entries = [e for e in (kb.entry(c) for c in cited) if e]
        bad = sorted(unsupported(s.get("text") or "", entries)) if action == "reply" else []
        expect = t["expect"]
        row = {
            "id": t["id"],
            "expect": expect,
            "action": action,
            "reason": s.get("reason", ""),
            "right_action": action == expect,
            "right_entries": action != "reply" or expect != "reply" or sorted(cited) == sorted(t.get("entries", [])),
            "lang_right": action != "reply"
            or not s.get("text")
            or language(s["text"]) == t.get("lang", language(text))
            or not t.get("entries"),
            "unsupported": bad,
            "fragments": len(t["fragments"]),
            "messages_sent": 1 if action == "reply" else 0,
        }
        rows.append(row)
    n = len(rows)
    should_handoff = [r for r in rows if r["expect"] == "handoff"]
    should_reply = [r for r in rows if r["expect"] == "reply"]
    answered = [r for r in should_reply if r["action"] == "reply"]
    naive = sum(r["fragments"] for r in rows)  # a per-fragment bot answers every message, even "thanks"
    by_why: dict[str, list[dict[str, Any]]] = {}
    for t, r in zip(turns, rows, strict=True):
        if t["expect"] == "handoff":
            by_why.setdefault(t.get("why", "unknown"), []).append(r)

    def pct(a: int, b: int) -> float | None:
        return round(a / b, 4) if b else None

    out = {
        "turns": n,
        "right_action": pct(sum(r["right_action"] for r in rows), n),
        "handoff_recall": pct(sum(r["action"] == "handoff" for r in should_handoff), len(should_handoff)),
        "replied_when_a_person_was_needed": sum(r["action"] == "reply" for r in should_handoff),
        "answered_when_answerable": pct(len(answered), len(should_reply)),
        "right_knowledge_when_answered": pct(sum(r["right_entries"] for r in answered), len(answered)),
        "right_language_when_answered": pct(sum(r["lang_right"] for r in answered), len(answered)),
        "unsupported_claims_sent": sum(len(r["unsupported"]) for r in rows),
        "messages_sent": sum(r["messages_sent"] for r in rows),
        "messages_a_per_fragment_bot_sends": naive,
        "actions": dict(Counter(r["action"] for r in rows)),
        "handoff_recall_by_why": {w: pct(sum(r["action"] == "handoff" for r in rs), len(rs)) for w, rs in sorted(by_why.items())},
        "rows": rows,
    }
    sent, nv = out["messages_sent"], out["messages_a_per_fragment_bot_sends"]
    out["headline"] = (
        f"{n} turns: right action {100 * (out['right_action'] or 0):.1f}%, handoff recall {100 * (out['handoff_recall'] or 0):.1f}% "
        f"(replied when a person was needed: {out['replied_when_a_person_was_needed']}), answered {100 * (out['answered_when_answerable'] or 0):.1f}% "
        f"of answerable turns, unsupported claims sent {out['unsupported_claims_sent']}; {sent} messages instead of {nv} for a per-fragment bot."
    )
    return out


def set_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(name: str) -> dict[str, Any]:
    turns, path = load_set(name)
    report = evaluate(turns)
    out_dir = ROOT / "reports"
    out_dir.mkdir(exist_ok=True)
    if name == "dev":
        (out_dir / "dev.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
        return {k: v for k, v in report.items() if k != "rows"}
    out_dir = out_dir / "heldout" / name
    out_dir.mkdir(parents=True, exist_ok=True)
    first = out_dir / "first_run.json"
    sha = set_sha256(path)
    if first.exists():
        pinned = json.loads(first.read_text())
        if pinned["sha256"] != sha:
            raise SystemExit(f"{name}: the set changed after its first run")
        (out_dir / "latest.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    else:
        status = (path.parent / "STATUS").read_text().strip() if (path.parent / "STATUS").exists() else "blind"
        record = {
            "set": name,
            "status": status,
            "sha256": sha,
            "ran_at": datetime.now(UTC).isoformat(timespec="seconds"),
            "drafter": "simulated",
            **report,
        }
        first.write_text(json.dumps(record, indent=2, ensure_ascii=False) + "\n")
    return {k: v for k, v in report.items() if k != "rows"}
