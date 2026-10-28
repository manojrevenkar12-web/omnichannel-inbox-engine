#!/usr/bin/env python3
"""Block edits to blind evaluation sets and their pinned first runs (see AGENTS.md, held-out rule)."""

import json
import re
import sys

event = json.load(sys.stdin)
path = str(event.get("tool_input", {}).get("file_path", ""))
if re.search(r"agent/heldout/[^/]+/turns\.yaml$|agent/reports/heldout/[^/]+/first_run\.json$", path):
    print(
        "Held-out sets and their first runs are not edited. To study a set, write 'regression' to its STATUS "
        "and have a new blind set written from agent/heldout/WRITING_TURNS.md.",
        file=sys.stderr,
    )
    sys.exit(2)
