#!/usr/bin/env bash
# What can run on any machine in under a minute. The core runs in CI (Gradle and PostgreSQL).
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== agent"
(cd agent && ruff check . && ruff format --check . && mypy && pytest -q && inbox-agent eval dev | grep headline)

echo "== web"
(cd web && npm run --silent typecheck && npm test --silent && npm run --silent build >/dev/null)

echo "all checks passed"
