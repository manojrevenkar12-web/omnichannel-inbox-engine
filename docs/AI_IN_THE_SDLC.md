# AI in how we build

We use AI tools (Claude Code, Claude in review) every day. They make us faster only where the
work is checked by something other than the tool itself. These are the rules.

## What the tools may do

- Write code, tests, migrations, Terraform and docs, in a branch, for a person to review.
- Run the fast checks (`scripts/check.sh`) and fix what they break.
- Investigate: read logs, explain a failing drill, propose a fix.

## What they may not do

- Change a held-out evaluation set, or change the agent to pass one (see below).
- Weaken a test or an invariant to make a change pass. The hook in `.claude/settings.json`
  blocks edits to `agent/heldout/*/turns.yaml` and `reports/heldout/*/first_run.json`.
- Push to main, deploy, or touch production data. Those need a person.

## Held-out sets keep the agent honest

`agent/heldout/<set>/turns.yaml` is written by someone (a person, or an agent that has read only
`agent/heldout/WRITING_TURNS.md`) who has not seen the agent's code or its development set. It is
run once; the first run is pinned with the set's sha256 in
`agent/reports/heldout/<set>/first_run.json` and never rewritten. Whoever studies a set's
failures to change the agent marks it `regression` in its `STATUS` file, and a new blind set is
written. This is how the agent went from 65% right actions (turns-1, first run) to 85% (turns-2,
first run) without the second number being tuned.

## Context the tools get

`AGENTS.md` (invariants, fast checks), `CLAUDE.md` (points to it) and the skills in
`.claude/skills/` (for example adding a channel, or releasing a change to the agent) are written
for people and tools alike. When a tool makes the same mistake twice, we fix the context, not just
the code.

## Review

AI-written code gets the same review as anyone's, with two extra questions: does a test fail
without this change, and did it change a test? A reviewer runs the drill or eval the change
touches, not just the unit tests.
