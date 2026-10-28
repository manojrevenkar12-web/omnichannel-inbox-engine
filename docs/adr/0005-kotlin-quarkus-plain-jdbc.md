# 0005: Kotlin on Quarkus with plain JDBC

Status: accepted

## Context

The core is a set of small transactions whose exact SQL matters: `SKIP LOCKED` claims, `ON
CONFLICT` inserts against a partial unique index, `greatest()` for windows that never move back.

## Decision

Kotlin 2.1 on Quarkus 3.20 (REST, Agroal, Flyway, scheduler, Micrometer). Transactions are opened
in code (`Db.tx`), not by annotations, and every statement is written out. Domain rules (window,
billing, limits, assignment, agent turns) are pure Kotlin with no framework types, tested without
a database.

## Consequences

- What runs is what is read in review; there is no ORM between a reviewer and the lock a query takes.
- More code to write for CRUD-like screens. Acceptable: there are few, and they are read models.
- Quarkus starts in about a second on the JVM, so tests use the real application with Dev
  Services PostgreSQL.
