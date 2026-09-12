# Engineering Log

Decisions I've made building this system, and what I rejected.
Anything with lasting architectural consequences graduates to
`docs/adr/`.

---

## What this is

A DISCOM, water board or city gas company buys a commodity and delivers
it through a network it owns. Some fraction never becomes revenue: it
leaks, it's stolen, it goes to an unmetered connection, it's metered but
mis-billed, or it's billed and never paid.

This system measures that gap at every level of the network, attributes
it to a cause, and drives the investigation that closes it. Not a
report — an operational backend: registry, ingestion, billing, ledger,
reconciliation, and a case workflow field teams work.

I'm building electricity. Water and gas are documented mappings.

---

## Stack

| Choice | Version | Why |
|---|---|---|
| Java | 21 | Current LTS, and what most of the market runs |
| Spring Boot | 4.1.1 | On Spring Framework 7; minimum Java 17 |
| PostgreSQL | 16 | Pinned, via Docker Compose |
| Flyway | — | Versioned migrations from the first commit |
| Testcontainers | — | Integration tests against real PostgreSQL |
| Build | Maven | `mvnw` wrapper, so no local Maven install needed |

I had Java 26 installed and deliberately moved this project down to 21.
26 is a six-month feature release; 17 and 21 are what employers run,
and I'd rather this compile on a normal machine than look like a lab
experiment.

---

## Decisions

### D1 — PostgreSQL in a container, not a native install

Three reasons, in increasing weight.

**Disposable.** When I break a migration or corrupt test data,
`docker compose down -v` and `up` gives me a clean database in seconds.
With a native install I'd be uninstalling software.

**Declarative.** `docker-compose.yml` is in the repo, so anyone who
clones this gets the exact PostgreSQL version I developed against from
one command.

**It's what makes Testcontainers possible**, and that's the reason that
actually decided it. My integration tests start a real PostgreSQL
container, run the Flyway migrations against it, execute, and destroy
it. The common alternative — testing against an in-memory H2 while
deploying on PostgreSQL — is quietly wrong: H2 differs in types,
locking behaviour and SQL dialect, so the tests pass against a database
that isn't the one in production.

*Rejected:* native install. It would hold host port 5432 and make the
container's port mapping fail.

### D2 — Image tags are pinned, never `latest`

`latest` isn't a pointer to the newest release. It's the default tag
applied when none is given, and what it points at changes over time.
Two machines pulling months apart can end up on different major
versions, which is how a schema works locally and breaks in CI.

I treat container images as dependencies and pin them like any other.

### D3 — Healthcheck on the database container

PostgreSQL accepts TCP connections a second or two before it can answer
queries, so a naive "wait for the port" check lets the application
connect and immediately fail. I gate on `pg_isready` instead, which
reports actual readiness.

Matters more in CI than locally, where start order is less forgiving.

### D4 — Flyway owns the schema; `ddl-auto: validate`

Hibernate never creates or alters tables here. Migrations are versioned
SQL files applied in order and recorded in `flyway_schema_history`.

`validate` makes Hibernate check my entities against the schema Flyway
built and refuse to start if they disagree, so a mismatch fails loudly
at boot rather than silently at runtime.

*Rejected:* `ddl-auto: update`. It infers schema changes from entity
classes: no review, no ordering, no rollback, no record of what ran.
It also silently skips changes it can't infer, such as dropped columns.
For a system whose entire argument is that corrections must be
attributable, letting an ORM mutate the schema unrecorded would
contradict the project.

### D5 — Intervals are half-open

Every effective-dated row uses `[valid_from, valid_to)` — start
inclusive, end exclusive. A row valid through 31 January carries
`valid_to = 2026-02-01`.

Overlap between `[a1, a2)` and `[b1, b2)` is exactly `a1 < b2 AND b1 <
a2`. Not four comparisons, not case analysis on which starts first.

I fixed this by convention rather than vigilance because mixing
inclusive and exclusive ends surfaces in billing, where a one-day
overlap means a consumer is charged twice.

### D6 — `utility_id` from the first migration; no isolation machinery

Root entities carry `utility_id`. I've built nothing on it: no tenant
resolution, no row-level security, no leakage test suite.

Utility deployments are single-tenant by procurement norm — a DISCOM
won't share a database with a neighbouring DISCOM. Building tenant
isolation would demonstrate a SaaS pattern the sector doesn't use. The
column leaves the seam open at near-zero cost.

I get the same query-layer authorisation skill later from
reader-to-node scoping, which has real domain motivation behind it.

### D7 — Bitemporal for four entities only

Four timestamps — `valid_from`/`valid_to` for what was true in the
world, `recorded_from`/`recorded_to` for what the system believed — on
exactly four entities: consumer indexing, tariff versions, conversion
factors, and the billing arrangement.

The test nothing else passes: *a retroactive correction to this entity
changes a number the system has already published.* A corrected meter
tagging changes a loss figure already reported; a corrected phone
number doesn't. A mis-dated switch between postpaid and prepaid changes
invoices and deductions already issued, which is why the billing
arrangement qualifies.

Two things that look like they qualify and don't: the power purchase
cost and the emission factor. They feed views, never a bill, so
append-only versioned reference data plus a version stamp on every
published figure is enough to reproduce it. **Full bitemporality is
reserved for entities that feed billing.**

Everything else gets simple valid-time effective-dating.

*Rejected:* bitemporality everywhere — correct, but most of the cost
buys nothing. *Also rejected:* mutable rows with retroactive
correction, which would make published figures unreproducible and make
the reconciliation work unfalsifiable.

---

### D8 — The application runs in UTC

`TimeZone.setDefault(UTC)` as the first statement in `main()`, and
`-Duser.timezone=UTC` for surefire so tests match.

I hit this as a startup failure: the PostgreSQL JDBC driver sends the
JVM's default zone as a connection parameter, Java on Windows reports
the Indian zone under its legacy alias `Asia/Calcutta`, and the
Debian-based `postgres:16` image no longer ships tzdata's
backward-compatibility aliases. The canonical name has been
`Asia/Kolkata` since 1993.

Setting the zone to `Asia/Kolkata` would have fixed the symptom. I set
UTC instead, because the ambient default was the real problem.

This system's correctness depends on knowing which day a fact belongs
to. Effective-dated intervals, period close, and the rule that a late
read is priced at its origin period's closing position all turn on a
date boundary. If the zone is inherited from whatever machine the
process happens to run on, my laptop, CI and a deployed container can
disagree about where 31 January ends — and a one-day disagreement at a
period boundary bills a consumer in the wrong month.

Where IST genuinely matters — time-of-day tariff windows, friendly-hours
cutoff rules — it is a policy and presentation concern, converted
explicitly where it is needed rather than inherited from a default.

---

## Conventions

- Half-open intervals everywhere. See D5.
- Flyway migrations in `src/main/resources/db/migration`, named
  `V<n>__<description>.sql`. Never edited once applied.
- `open-in-view: false`. The Spring default holds a database session
  open for the whole HTTP request, which hides N+1 problems until
  production.
- Domain enums stored as `TEXT` with constraints in code, not
  PostgreSQL enum types, which are painful to alter.