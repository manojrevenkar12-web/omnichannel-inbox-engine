# 0003: Models word answers; rules decide what may be sent

Status: accepted

## Context

A practice's assistant that replies to someone in pain, invents a price, or follows "ignore your
instructions" does damage that no number of correct answers makes up for.

## Decision

The agent is a LangGraph graph with four nodes: gate, retrieve, draft, verify.

- **Gate** (rules, before any model): symptoms, complaints, requests for a person, requests for
  records or data changes, and prompt injection go to a person. Acknowledgements get no reply.
  A closed window means no AI reply at all.
- **Retrieve**: an entry answers a question only if the question uses one of its keywords, the
  entry knows almost a third of the question's topic words, and the entry contains the kind of
  fact asked for (a price needs an amount, a duration a duration, a date a date). Two close entries
  are ambiguous, and ambiguity goes to a person.
- **Draft**: a model words the answer from the cited entries only (a deterministic drafter by
  default, Claude when `ANTHROPIC_API_KEY` is set).
- **Verify**: every number, time, price, phone number and link in the draft must appear in a cited
  entry. A draft that fails is not sent.

## Consequences

- 0 unsupported claims sent on every set, by construction.
- The agent hands off more than it could: 73% of answerable turns answered on the blind set
  turns-2. A wrong answer costs more than a handover.
- Rules are code and are tested; changing what goes to a person is a reviewed change, not a prompt
  edit.
