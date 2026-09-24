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

### D9 — Integration tests run against real PostgreSQL, not an in-memory database

Testcontainers starts `postgres:16` — the same image as the compose file
— applies my Flyway migrations to an empty database, boots the full
Spring context, and destroys the container when the JVM exits.

The usual alternative is H2 in memory, on the grounds that it is fast
and needs no setup. It would mean testing against a database I don't
deploy, and the differences land exactly where this system's
correctness lives: UUID and `JSONB` handling, recursive CTEs, window
functions, `FOR UPDATE SKIP LOCKED`, `pg_trgm`, and — the one that
matters most — locking and isolation semantics. The concurrency work
later, proving two payments on one account cannot lose an update, is
meaningless against a database with different locking behaviour. My
migrations might not even run on H2, which would mean maintaining two
schemas and testing the one I don't ship.

The cost is seconds per run instead of milliseconds. For a system whose
whole argument is correctness, that isn't close.

Three things worth knowing about how it works:

- **The port is random**, not 5432, because the compose database already
  holds that port and parallel test runs would collide. `@ServiceConnection`
  (Spring Boot 3.1+) wires the container's URL and credentials into
  Spring, so nothing is hardcoded. Older tutorials use a
  `@DynamicPropertySource` block for this; it isn't wrong, just twelve
  lines where three will do.
- **The container field is `static`**, so it starts once per test class
  rather than once per test method.
- **Ryuk** is a sidecar container Testcontainers starts first, whose only
  job is to kill the containers it created when the JVM exits. That is
  why nothing lingers after a crashed run and why I write no cleanup
  code.

The guarantee this buys: every test run builds the schema from scratch
by applying the migrations. A broken migration fails the build rather
than surfacing on a fresh deploy.

---

### D10 — Uttar Pradesh for both the tariff schedule and net metering

The tariff engine follows UPPCL's categories under UPERC's tariff order,
and prosumer billing follows UPERC's rooftop solar regulations.

The reason is coherence rather than convenience. The evidence this
project is built on is a CEEW study of a UP feeder — the 42% loss
figure, the arrears concentration, the table billing, the unassigned
readers. If the tariff engine priced in another state's slabs while the
generator reproduced a UP feeder's problems, the project would quietly
describe two different places.

UP also exercises the parts that matter: telescopic domestic slabs, a
rural/urban split, a large base of unmetered agricultural connections
(which makes normative billing a real requirement), and one of the
largest prepaid smart-meter rollouts.

*Trade-off accepted:* Delhi's and Maharashtra's tariff orders are cleaner
documents. I'm trading document quality for one consistent world.

*Deliberately not done yet:* fetching the actual rates. Tariff orders
are reissued every year and rating isn't built until later, so I take
the order in force then. What I need now is only the category names,
because they become enum values.

Both are versioned data by design, so another state is a data change,
not a code change.

---

### D11 — The code is divided into layers, and dependencies only point down

The system is one deployable application, but the code inside it is
split into areas, and I've written down which may use which. The rule is
simple: **an area can use the ones below it, never the ones above.**

| Layer | Areas |
|---|---|
| api | controllers, request/response shapes, error handling |
| workflow | `casework`, `notification` |
| money | `billing`, `prepaid`, `supply` |
| derivation | `balance`, `tariff` |
| facts | `reading` |
| state | `registry`, `ledger` |
| foundation | `common` |

Full detail in `docs/module-map.md`. Four choices in it are deliberate.

**The ledger knows nothing about electricity.** No meters, no units, no
transformers — just amounts, accounts, and the rule that the two sides
of every entry balance. Billing tells it what to record. This is what
lets me test the money rules on their own: throw thousands of random
money movements at it and check the books still balance, without
building an electricity network first.

**Casework and notification are reached only through announcements,
never direct calls.** Almost every area needs to raise a case. If they
called casework directly, and casework needed to look at readings to
describe the problem, the two would point at each other. So areas
announce what happened, with enough detail to explain it, and casework
listens. That also forces every case to carry its own evidence — which
the project requires anyway, since an accusation must be explainable to
the person it's about. A practical constraint and a moral one landing on
the same answer is usually a sign the line is in the right place.

**Prepaid depends on supply, not the reverse.** Prepaid decides whether
to cut someone off; supply just carries the instruction. The messenger
shouldn't know why.

**The registry depends only on `common`**, which is why it is built
first.

This map will turn out wrong somewhere — most likely around conversion
factors, which both balance and tariff use. When it does, I change the
map and record here what I learned.

---

### D12 — A customer's connection is not a node in the network tree

The network tree stops at the distribution transformer. A connection is
its own record, linked to a transformer by a separate table.

The obvious design would give a connection a `parent_id` pointing at its
transformer, like every other level. I didn't, because **which
transformer a connection belongs to is a claim, and the claim is often
wrong.** In the CEEW feeder, 8% of consumers were tagged to the wrong
feeder and around 40% weren't mapped to any transformer at all. That
link gets disputed, checked in the field, and corrected — sometimes
after a loss figure has already been published using the wrong answer.

A `parent_id` column holds one answer and forgets what it used to say.
The link needs its own history, with both the date it became true and
the date the system learned it. That is exactly the shape D7 describes.

---

### D13 — Enums are stored by name; dates use `java.time`

**`@Enumerated(EnumType.STRING)` on every enum field.** Hibernate's
default stores an enum's position number — the first value is 0, the
second 1. Insert a new value near the top and every stored number
silently changes meaning. Storing the name avoids that.

The consequence I have to respect: **the name is now the data.** A typo
in an enum value gets written into every row. I caught one before any
data existed — `ElECTRICITY`, with a lowercase `l` — which would have
cost a migration to fix later.

**`LocalDate` and the other `java.time` types, never `java.sql.Date`.**
The old type secretly carries a time as well and converts it using the
machine's time zone — the same machine-dependent behaviour D8 exists to
remove. `LocalDate` is a date and nothing else.


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
- Enum fields always `@Enumerated(EnumType.STRING)`. See D13.
- Dates are `java.time` — `LocalDate`, `Instant`. Never `java.sql.*`.
- Java fields in camelCase (`validFrom`); columns in snake_case
  (`valid_from`). Spring converts between them, so column names are only
  written out when they genuinely differ.
- Relationships are always `fetch = LAZY`. Loading one transformer must
  not load the whole chain above it.
- Entity constructors take enums, not strings, so a wrong value is a
  compile error rather than a runtime crash.
- Every entity has a `protected` no-argument constructor for Hibernate,
  and a real constructor that generates the id.

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

### Tutorials and generated code lag the version I'm on

Spring Boot 4 and Testcontainers 2 renamed and restructured enough that
most material written for Spring Boot 3 / Testcontainers 1 is subtly
wrong:

| Older API | What I actually use |
|---|---|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| one fat `spring-boot-starter-test` | per-starter test companions — `...-data-jpa-test`, `...-flyway-test`, `...-webmvc-test` |
| `org.testcontainers:postgresql` | `org.testcontainers:testcontainers-postgresql` |
| `org.testcontainers.containers.PostgreSQLContainer` | `org.testcontainers.postgresql.PostgreSQLContainer` |
| `PostgreSQLContainer<?>` — self-referential generic | **not generic in 2.x** — the raw type is correct |

The habit: when the compiler and an article disagree, check the
article's version before assuming I made the mistake. *Type does not
have type parameters* is the compiler being precise, not confused.

### Noted for later — Mockito self-attaching

```
Mockito is currently self-attaching to enable the inline-mock-maker.
This will no longer work in future releases of the JDK.
```

Java is tightening dynamic agent loading. Harmless while nothing uses
Mockito; when I start mocking, it becomes a `-javaagent` line in the
surefire config.

### Two guards, and which one catches what

When I broke `NetworkNode` on purpose, the first attempt never reached
the database. I'd changed a field to `String` but left the constructor
and getter as `LocalDate`, so the **compiler** refused. The compiler
only knows about Java — it can catch "a date going into a text field",
but it has no idea what's in the database.

Making the Java consistent but wrong against the table got past the
compiler, and then **Hibernate's startup check** caught it:

```
Schema validation: wrong column type encountered in column [valid_from]
in table [network_node]; found [date], but expecting [varchar(255)]
```

`found` is the database; `expecting` is what Java wanted. `varchar(255)`
is Hibernate's default guess for a `String`.

The order matters: compiler first, then the schema check at startup,
then runtime. Each catches a kind of mistake the one before it can't
see. Having watched the second one fire, I can trust it without
thinking about it.

### Finding the one useful line in a huge log

A startup failure produced about 400 lines. Most of it was a block
headed **CONDITIONS EVALUATION REPORT** — Spring listing every automatic
setup it considered and why each did or didn't switch on. Useful for a
different kind of problem, noise for this one.

Two habits get straight to the answer:

- **Search for `Caused by` and take the last one.** The bottom-most
  cause is the real one.
- **Search for a word that describes the likely problem** —
  `Schema validation`, `Connection refused`, `TimeZone`.

Nobody reads these top to bottom.

### Mapping a relationship: the parent is an object, not an id

My first `NetworkNode` got the parent mapping wrong in three ways at
once, all from one misunderstanding:

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "id")          // wrong column
@Column(nullable = false)         // can't combine, and the root has no parent
private UUID parent_id;           // should be the object, not its id
```

In the database a parent is an id in the `parent_id` column. In Java a
parent is **another `NetworkNode`**. Translating between those two is
exactly Hibernate's job, so the field type is `NetworkNode`.

`@JoinColumn(name = ...)` names the column **in this table** that holds
the link — `parent_id` — not the column it points at. Naming `id` would
have mapped the primary key twice.

The parent is nullable, because the substation at the top has none. And
`@Column` never goes on a relationship; `@JoinColumn` already does that
job.

Correct version:

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "parent_id")
private NetworkNode parent;
```

### A repository test that couldn't fail

Hibernate keeps every object it saves or loads in memory for the rest of
the transaction. Asking for one again returns the same object without
touching the database.

So a test that saves a node and immediately reads it back passes even if
the database mapping is broken. I proved it: with `entityManager.clear()`
removed, my tree test still went green, but the parent it walked up to
was the Java object I'd built in the test, never read back from
PostgreSQL. If `parent_id` were saved wrong, that test would still have
passed.

Every repository test now calls `flush()` (send pending writes) then
`clear()` (forget everything in memory) before reading back, so the read
has to make a real round trip. The general rule I took from it: *if my
code were broken, would this go red?* If not, the test is decoration.

The SQL log made the rest visible too. The three INSERTs appeared
together at `flush()`, not at each `save()`, and the parent SELECTs
came from my assert lines rather than from `findById` — lazy loading
fetching each parent only when something actually used it.

### Open issue: a SELECT before every save

The same log showed a SELECT by id before each INSERT. Spring Data
decides whether an entity is new by checking whether its id is null. My
ids are generated in the constructor (see the UUID choice under the network_node table), so
every entity arrives with one set, Spring can't tell it's new, and it
checks the database first.

Two costs. Every save is two round trips instead of one — negligible
now, significant once meter readings are ingested in bulk. And on that
path `save()` returns a different instance from the one passed in,
leaving the original detached, so later changes to it would be silently
lost.

Fix pending: the entity will tell Spring directly whether it's new.