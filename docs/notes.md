# Engineering Log

Decisions taken while building this system, and what was rejected.
Architecture decisions with lasting consequences graduate to
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

Electricity is built. Water and gas are documented mappings.

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

---

## Decisions

### D1 — PostgreSQL in a container, not a native install

Three reasons, in increasing weight.

**Disposable.** A half-applied migration or corrupt test data is fixed
by `docker compose down -v` and `up`, not by uninstalling software.

**Declarative.** `docker-compose.yml` is in the repo. Anyone who clones
this gets the exact PostgreSQL version it was developed against, from
one command.

**It's what makes Testcontainers possible.** Integration tests start a
real PostgreSQL container, run the Flyway migrations against it,
execute, and destroy it. The common alternative — testing against an
in-memory H2 while deploying on PostgreSQL — is quietly wrong: H2
differs in types, locking behaviour and SQL dialect, so the tests pass
against a database that isn't the one in production.

*Rejected:* native install. It would hold host port 5432 and make the
container's port mapping fail.

### D2 — Image tags are pinned, never `latest`

`latest` is not a pointer to the newest release. It is the default tag
applied when none is given, and what it points at changes over time.
Two machines pulling months apart can run different major versions,
which is how a schema works locally and breaks in CI.

Container images are dependencies. Pin them like any other.

### D3 — Healthcheck on the database container

PostgreSQL accepts TCP connections a second or two before it can answer
queries, so a naive "wait for the port" check lets an application
connect and immediately fail. The compose file gates on `pg_isready`,
which reports actual readiness.

Matters more in CI than locally, where start order is less forgiving.

### D4 — Flyway owns the schema; `ddl-auto: validate`

Hibernate never creates or alters tables. Migrations are versioned SQL
files applied in order and recorded in `flyway_schema_history`.

`validate` means Hibernate checks entities against the schema Flyway
built and refuses to start if they disagree, so a mismatch fails loudly
at boot rather than silently at runtime.

*Rejected:* `ddl-auto: update`. It infers schema changes from entity
classes: no review, no ordering, no rollback, no record of what ran.
It also silently skips changes it can't infer, such as dropped columns.

### D5 — Intervals are half-open

Every effective-dated row uses `[valid_from, valid_to)` — start
inclusive, end exclusive. A row valid through 31 January has
`valid_to = 2026-02-01`.

Overlap between `[a1, a2)` and `[b1, b2)` is exactly `a1 < b2 AND b1 <
a2`. Not four comparisons, not case analysis on which starts first.

Mixing inclusive and exclusive ends is a class of bug that surfaces in
billing, where a one-day overlap means a consumer is charged twice.
Fixed by convention rather than by vigilance.

### D6 — `utility_id` from the first migration; no isolation machinery

Root entities carry `utility_id`. Nothing is built on it: no tenant
resolution, no row-level security, no leakage test suite.

Utility deployments are single-tenant by procurement norm — a DISCOM
will not share a database with a neighbouring DISCOM. Building tenant
isolation would demonstrate a SaaS pattern the sector doesn't use. The
column leaves the seam open at near-zero cost.

The same query-layer authorisation skill is exercised later by
reader-to-node scoping, which has real domain motivation behind it.

### D7 — Bitemporal for three entities only

Four timestamps — `valid_from`/`valid_to` for what was true in the
world, `recorded_from`/`recorded_to` for what we believed — on exactly
three entities: consumer indexing, tariff versions, conversion factors.

The test nothing else passes: *a retroactive correction to this entity
changes a number the system has already published.* A corrected meter
tagging changes a loss figure already reported; a corrected phone
number does not.

Everything else gets simple valid-time effective-dating.

*Rejected:* bitemporality everywhere — correct, but most of the cost
buys nothing. *Also rejected:* mutable rows with retroactive
correction, which would make published figures unreproducible.

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