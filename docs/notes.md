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

---

## Problems hit, and how I read them

### Reading a Java stack trace

Four exceptions nested by `Caused by`. **Read from the bottom** — the
last `Caused by` is the root cause and everything above it is Spring
wrapping the failure as it bubbled up.

The `~[jar-name]` tag on each frame says which library it came from. My
own code is tagged `~[classes/:na]`, and in a real bug that is usually
the line I want.

Three connection failures that look similar and mean different things:

| Message | Means |
|---|---|
| `Connection refused` | nothing is listening on that port |
| timeout | something is there but not answering — usually a firewall |
| `authentication failed` | the server is up and rejected my credentials |

### The application started before the database

`Connection refused` at `localhost:5432` — the container had stopped on
a machine restart. `docker compose up -d` and wait for `(healthy)`.

The trace was worth keeping for a different reason: it showed
`entityManagerFactory` depending on `flywayInitializer`. Spring Boot
wires that deliberately, so **migrations always run before Hibernate
validates**. Without that ordering, `ddl-auto: validate` would check
entities against a schema that didn't exist yet — D4 depends on it.

### `invalid value for parameter "TimeZone": "Asia/Calcutta"`

The JDBC driver sends the JVM's default zone as a connection parameter.
Java on Windows reports the Indian zone under its legacy alias; the
Debian-based `postgres:16` image no longer ships tzdata's
backward-compatibility links. Fixed as D8 — the whole application runs
in UTC.

### The JDK version was different in four places

Windows `JAVA_HOME`, the PATH, IntelliJ's project SDK and language
level, IntelliJ's Maven importer, and `<java.version>` in the pom. All
of them can disagree, and the symptom is code that compiles in the IDE
and fails in `mvnw`, or passes locally and breaks in CI.

Diagnosis: `where.exe java` shows what the PATH resolves to;
`& "$env:JAVA_HOME\bin\java.exe" --version` shows what `JAVA_HOME`
names. When those two disagree, that is the bug.

Two things that made it confusing. Environment variables are read at
**process start**, so an open terminal — including IntelliJ's, which
inherits IntelliJ's environment — keeps stale values until a full
restart. And IntelliJ had silently downloaded its own JDK, independent
of anything in the Windows environment.

The pom is the one that matters for CI; it is the only one a build
server reads.

---

## Things I had to get straight

**Why do most dependencies have no `<version>`?**
`spring-boot-starter-parent` pins a tested set of versions for hundreds
of libraries. I inherit that rather than picking my own.

**What does `mvn verify` do that `mvn compile` doesn't?**
Maven phases run in order, and naming one runs everything before it —
validate, compile, test, package, verify. That is why CI runs one
command.

**Why would a controller in the wrong package 404 instead of erroring?**
`@SpringBootApplication` scans its own package downwards only. A class
outside it is invisible to Spring — no bean, no route, no error.

**Image vs container vs volume.**
Image is a read-only template, container is an instance with a thin
writable layer, volume is storage Docker manages outside the container's
lifecycle. `docker compose down` destroys the container, keeps the
volume. Only `down -v` deletes the data.

**Why did changing `POSTGRES_PASSWORD` do nothing?**
The image reads those variables only when initialising an **empty** data
directory. An existing volume means the entrypoint skips initialisation
entirely. Change it in SQL, or `down -v` and start clean.

**Which side of `"5432:5432"` is mine?**
The left. Map `"5433:5432"` and Postgres still listens on 5432 inside,
but my JDBC URL has to say 5433.

**Does `connection.close()` close the connection?**
No. HikariCP hands out a proxy; `close()` returns it to the pool. The
socket stays open for the life of the application. That is why pool
size, not connection setup cost, is what caps concurrent queries.

**`TEXT` or `VARCHAR(n)`?**
In PostgreSQL they are stored and perform identically; `VARCHAR(n)` just
adds a length check. `TEXT` unless the length is a real business rule.

**Why `CHECK (valid_to IS NULL OR valid_to > valid_from)` and not just
the comparison?**
A comparison against null is *unknown*, not false, and `CHECK` only
rejects a row when the expression is definitively false. The same
three-valued logic bites in queries: `WHERE x != 5` silently excludes
rows where x is null.

**Why name constraints?**
The violation error quotes the name. Unnamed, Postgres invents
`table_check1`, which tells me nothing when a table has four checks.

**Why does `parent_id` need an explicit index when `id` doesn't?**
A primary key gets its index automatically. PostgreSQL does **not**
index foreign key columns, which makes parent lookups and joins slow
until I add one. Every tree walk in the balance engine runs that index.

**Why is `valid_from` in the unique index?**
`(utility_id, code)` alone would mean a node can exist only once in
history. Effective dating needs the same code to appear more than once —
a superseding correction, or a decommissioned code reissued.

**Why UUID and not `BIGSERIAL`?**
A sequential key needs a database round trip to learn an entity's
identity and leaks the row count. UUIDs are generated in application
code, which matters once ingestion is batched. The cost is a wider
index.

**Why is the tree not a structure in the database?**
It is one flat table with a column pointing back at itself. Direct
children are one indexed lookup; everything beneath a node at unknown
depth is not, and needs a recursive CTE.

**What does `revass-#` mean in psql?**
The trailing dash means psql is mid-statement, waiting for a semicolon.
`;` submits what is buffered, `\r` throws it away. Backslash for psql
commands — `\dt`, `\d`, `\l`, `\q` — never a forward slash.