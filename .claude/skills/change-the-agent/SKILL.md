---
name: change-the-agent
description: Change how the AI agent answers or hands over (rules, retrieval, knowledge, drafting); use for any change under agent/ that can change what a customer receives.
---

# Change the agent

1. Say what should change, with two or three example customer turns. Add them to `agent/eval/dev.yaml`.
2. Make the change. Rules (who goes to a person) live in `inbox_agent/rules.py`; retrieval in
   `knowledge.py` and `graph.py`; facts only in `knowledge/*.yaml`.
3. Run `pytest -q` and `inbox-agent eval dev`. `tests/test_sets.py` fails if the dev set or any
   regression set ever replies when a person was needed or sends an unsupported claim.
4. Do not open `agent/heldout/*/turns.yaml` for a set whose STATUS is `blind`.
5. When the change is ready, ask for a new blind set: a person or an agent that reads only
   `agent/heldout/WRITING_TURNS.md` writes `agent/heldout/turns-N/`. Run it once with
   `inbox-agent eval turns-N` and commit the pinned first run. Report that number, not the dev number.
