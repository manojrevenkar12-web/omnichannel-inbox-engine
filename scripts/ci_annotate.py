#!/usr/bin/env python3
"""Turn a Gradle build log and JUnit XML reports into GitHub annotations.

Annotations are what reviewers (and anyone without log access) see on a run: Kotlin compiler
errors with file and line, Gradle's "What went wrong" block, failed tests with their message,
and the drill reports as notices.

Usage: ci_annotate.py BUILD_LOG [REPORT_DIR ...]
"""

from __future__ import annotations

import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def esc(s: str) -> str:
    return s.replace("%", "%25").replace("\r", "").replace("\n", "%0A")


def emit(level: str, msg: str, file: str | None = None, line: int | None = None, title: str | None = None) -> None:
    props = []
    if file:
        props.append(f"file={file}")
    if line:
        props.append(f"line={line}")
    if title:
        props.append(f"title={esc(title)}")
    print(f"::{level} {','.join(props)}::{esc(msg[:3000])}")


def kotlin_errors(log: str, root: Path) -> int:
    n = 0
    for m in re.finditer(r"^e: file://(\S+?):(\d+):(\d+) (.*)$", log, re.M):
        path, line, msg = m.group(1), int(m.group(2)), m.group(4)
        try:
            rel = str(Path(path).resolve().relative_to(root))
        except ValueError:
            rel = path
        emit("error", msg, rel, line, "Kotlin")
        n += 1
    return n


def gradle_failures(log: str) -> int:
    n = 0
    for m in re.finditer(r"\* What went wrong:\n(.*?)(?:\n\* Try:|\Z)", log, re.S):
        emit("error", m.group(1).strip()[:2500], title="Gradle")
        n += 1
    return n


def junit_failures(root: Path) -> int:
    n = 0
    for xml in root.rglob("build/test-results/**/*.xml"):
        try:
            tree = ET.parse(xml)
        except ET.ParseError:
            continue
        for case in tree.iter("testcase"):
            for kind in ("failure", "error"):
                el = case.find(kind)
                if el is None:
                    continue
                text = (el.get("message") or "") + "\n" + (el.text or "")
                cls = case.get("classname", "")
                src = next(iter(root.rglob(cls.split(".")[-1].split("$")[0] + ".kt")), None)
                emit("error", text.strip()[:2500], str(src.relative_to(root)) if src else None, None, f"{cls}.{case.get('name')}")
                n += 1
    return n


def reports(dirs: list[Path]) -> None:
    for d in dirs:
        for f in sorted(d.rglob("*.json")):
            try:
                data = json.loads(f.read_text())
            except json.JSONDecodeError:
                continue
            headline = data.get("headline") if isinstance(data, dict) else None
            emit("notice", headline or json.dumps(data)[:2500], title=f"report {f.name}")


def main() -> None:
    root = Path.cwd().resolve()
    log = Path(sys.argv[1]).read_text(errors="replace") if len(sys.argv) > 1 and Path(sys.argv[1]).exists() else ""
    n = kotlin_errors(log, root) + junit_failures(root)
    if n == 0:
        gradle_failures(log)
    reports([Path(p) for p in sys.argv[2:]])


if __name__ == "__main__":
    main()
