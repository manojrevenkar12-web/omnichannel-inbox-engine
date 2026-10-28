"""inbox-agent serve | eval [SET]"""

from __future__ import annotations

import argparse
import json


def main() -> None:
    p = argparse.ArgumentParser(prog="inbox-agent")
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("serve")
    s.add_argument("--host", default="127.0.0.1")
    s.add_argument("--port", type=int, default=8090)
    e = sub.add_parser("eval")
    e.add_argument("set", nargs="?", default="dev")
    a = p.parse_args()
    if a.cmd == "serve":
        import uvicorn

        uvicorn.run("inbox_agent.app:app", host=a.host, port=a.port)
    else:
        from inbox_agent.evaluate import run

        print(json.dumps(run(a.set), indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
