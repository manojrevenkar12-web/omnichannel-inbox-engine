# How this team works

A playbook for leading the 3–4 engineers who own the inbox: the core (Kotlin, Quarkus), the agent
(Python, LangGraph), the console (React) and the infrastructure (Terraform). The lead codes about
half the time, and spends the other half making the others effective.

## The week

| When | What | Why |
|---|---|---|
| Monday, 30 min | Planning: the week's goal in one sentence, who owns what, what we are not doing | One goal keeps four people from making four plans |
| Daily, async | A written update in the team channel: done, next, stuck | No stand-up meeting; a stuck person is noticed by noon |
| Wednesday, 45 min | Design review of open RFCs | Decisions in the open, before code |
| Thursday, 30 min | Delivery risk review (below) | Surprises are found on Thursday, not at the demo |
| Friday, 30 min | Demo of what shipped to customers, from production | "Done" means a customer can use it |
| Every two weeks | 1:1 with each engineer, 30 min | Their agenda first |

## 1:1s

The engineer's agenda first, written in a shared doc the day before. Then mine. Topics that come
back: what is slowing you down, what you want to learn next, feedback both ways, career. I write
down what I promised and start the next 1:1 with it.

## Feedback

Specific, soon, private when it is critical, public when it is praise. Situation, behaviour,
impact: "In yesterday's incident (situation) you posted the timeline every 15 minutes (behaviour);
support could answer customers without asking us (impact)." I ask for feedback on myself in every
1:1, and once a quarter through an anonymous form.

## Code review

- Small pull requests: under 400 lines changed, one purpose. Larger work lands behind a release
  setting in `config/`, never a long-lived branch.
- A reviewer answers within four working hours. Review the design first, then the code.
- The author owns the merge and the deploy. Main is always deployable.
- A comment says what it is: **must** (blocks), **should** (fix or explain), **nit** (take or leave).
- Invariants in `AGENTS.md` are checked by tests; a review checks that new code is covered by them.

## RFCs

Anything that changes a contract (webhook handling, the outbox, the agent API, the database
schema) or costs more than a week gets a one-to-two page RFC in `docs/rfc/`: problem, options with
trade-offs, decision, how we will know it worked, how we roll it back. Comments for three working
days; the owner decides; disagreement is recorded, then we commit. Accepted RFCs become ADRs in
`docs/adr/`.

## Delivery risk review (Thursday)

For each piece of work in flight, three questions: what could make this late, what would we cut
first, who outside the team do we depend on. Anything at risk gets an owner and a date to decide
by. Product hears about risks the same day, with options, not just a problem.

## Incidents

Whoever notices declares it in the incident channel. One person leads, one person talks to
support and customers. Mitigate first, investigate after. A blameless review within five working
days: timeline, what made it possible, what we change, with owners. Every review adds a drill or a
test (the fault drills in `core/src/test/kotlin/inbox/it/DrillTest.kt` started this way).

## Hiring

Process: a 30-minute intro call, a 90-minute pairing session on a real (small) change in this
repository, a 60-minute system design conversation (for example: "WhatsApp sends us the same
webhook three times and out of order; design the ingestion"), and a 45-minute conversation about
how they work with others. Everyone who interviews writes their evidence before reading anyone
else's. We decide in a 20-minute debrief against the rubric.

| | Below the bar | At the bar | Above |
|---|---|---|---|
| Problem solving | Jumps to a solution, misses failure cases | Asks what can fail, handles duplicates and retries | Finds the case the interviewer did not plan for |
| Code | Works only for the happy path | Clear, tested, small steps | Leaves the code easier to change |
| Product sense | Builds what is asked | Asks who it is for and how we will know it works | Proposes a simpler thing that solves more |
| Collaboration | Defends, does not listen | Explains trade-offs, takes feedback | Makes others' ideas better |
| Ownership | Stops at "merged" | Watches it in production | Fixes the process that let the bug in |

## Onboarding: 30, 60, 90 days

- **First week**: laptop to first merged change on day 2 (`scripts/check.sh` must run green on a
  fresh machine). A buddy who is not the lead. Read the ADRs; run the drills; sit with support for
  an afternoon.
- **30 days**: owns a small feature end to end, including the release and a week of watching it.
  Has done a code review that changed someone's design.
- **60 days**: on call with a shadow. Has written or co-written an RFC.
- **90 days**: on call alone. Owns one area (for example the agent's evaluation sets) and is the
  person others ask about it.

## Growing people

Each engineer has one development goal per half-year, agreed in a 1:1 and written down: a skill,
an area to own, or a stretch project. The lead's job is to find work that serves both the goal
and the product, and to give credit in public when it lands.
