![CI](https://github.com/MrSapien/utility-revenue-assurance/actions/workflows/ci.yml/badge.svg)

# Utility Revenue Assurance

**Revenue assurance for metered network delivery — electricity built,
water and gas mapped.**

A DISCOM, water board or city gas company buys a commodity and delivers
it through a network it owns. Some fraction never becomes revenue: it
leaks, it's stolen, it goes to an unmetered connection, it's metered but
mis-billed, or it's billed and never paid.

This system measures that gap at every level of the network, attributes
it to a cause, and drives the investigation that closes it. Not a
report — an operational backend: registry, ingestion, billing, ledger,
reconciliation, and a case workflow field teams work.

---

## Status

**Started 17 September 2026.** Under active development — the sections
below marked *not built* are design, not code.

**Working today**

- PostgreSQL schema under Flyway, versioned from the first commit
- `network_node` — the utility's network tree, effective-dated
- Integration tests against real PostgreSQL 16 via Testcontainers
- CI on every push: clean machine, migrations applied to an empty
  database, full Spring context booted and validated

**Next:** the rest of the registry — service connections, consumer
accounts, occupancies, meters, meter installations, readers, and the
bitemporal entities.

**Not built yet:** read ingestion, the balance engine, the public loss
API, tariffs and the ledger, prepaid billing, the case workflow,
detection rules.

I would rather this list stay honest than look finished.

---

## Why it exists

CEEW studied one high-loss feeder in Uttar Pradesh — 3.15 MW connected
load, 51 distribution transformers, around 2,500 connections. Losses
averaged **42% of energy input** against a 12–15% target.

| Cause | Share of losses |
|---|---|
| HT line loss | 16.9% |
| LT line loss | 16.9% |
| Metering gaps | 4.7% |
| Under-billing (table billing) | 21.9% |
| Theft and other factors | 40.0% |

Commercial causes are two thirds of it. The local problems were fixed in
months; the systemic ones persisted, and two of them are software
problems this system is built against.

**The data model couldn't represent reality.** The billing application
had no way to record a consumer as unmetered if they had previously been
billed on a metered basis, so readers filed them under "defective
metering." Billing data showed 39 unmetered consumers where a survey
found 282.

**The authorisation model had no scoping.** Every reader in a
subdivision could access the whole consumer list. Around 40% of
consumers were billed by readers not assigned to their feeder, and those
bills were systematically lower.

---

## What software can and cannot reach

Software cannot remove the incentive to divert revenue. Consumers pay
meter readers for lower bills; linemen decline to disconnect neighbours
they live among; discretion gets exercised where no system is watching.

What software removes is *ambiguity* — the space discretion hides in.
Scope the authorisation and the same act still happens, but now it needs
an override with a name and a timestamp on it. The behaviour doesn't
become impossible; it becomes attributable.

India's best DISCOMs sit near 6–8% against a world benchmark around 6%,
and nobody serious targets zero. The goal is shrinking the space where
loss happens unattributably, and being honest about the remainder.

---

## Running it

**Requirements:** JDK 21, Docker.

```bash
docker compose up -d          # PostgreSQL 16, wait for (healthy)
./mvnw spring-boot:run        # http://localhost:8080
```

**Tests** — no running database needed; Testcontainers starts its own:

```bash
./mvnw verify
```

**Resetting the database** when a migration goes wrong:

```bash
docker compose down -v && docker compose up -d
```

---

## Stack

| | | |
|---|---|---|
| Java | 21 | current LTS |
| Spring Boot | 4.1.1 | on Spring Framework 7 |
| PostgreSQL | 16 | pinned, via Docker Compose |
| Flyway | | versioned migrations from the first commit |
| Testcontainers | | integration tests against real PostgreSQL |
| Maven | | `mvnw` wrapper, no local install needed |

---

## Design decisions

Full reasoning, including the alternatives I rejected, is in
[`docs/notes.md`](docs/notes.md). The ones that shape everything else:

**Flyway owns the schema; Hibernate only validates it.**
`ddl-auto: validate` means a mismatch between entities and schema fails
loudly at boot. `update` would let an ORM alter tables with no review,
no ordering, no rollback and no record of what ran — which would
contradict a system whose argument is that corrections must be
attributable.

**Append-only for anything used to compute a published figure.**
Readings, invoices and journal entries are never updated or deleted.
Corrections are new rows carrying a reason and an actor. The corrected
value alone cannot tell you that someone got it wrong, who, when or why.
The pair can.

**Intervals are half-open**, `[valid_from, valid_to)`. Mixing inclusive
and exclusive ends surfaces in billing, where a one-day overlap means a
consumer is charged twice. Fixed by convention rather than vigilance.

**Bitemporal for four entities only** — consumer indexing, tariff
versions, conversion factors and the billing arrangement. The test
nothing else passes: *a retroactive correction to this entity changes a
number the system has already published.*

**The application runs in UTC.** Correctness here depends on knowing
which day a fact belongs to. A zone inherited from whichever machine the
process runs on means a laptop, CI and a deployed container can disagree
about where 31 January ends.

**Tests run against real PostgreSQL**, not an in-memory substitute.
Recursive CTEs, window functions, `SKIP LOCKED` and — the one that
matters most — locking and isolation semantics are where this system's
correctness lives, and they are exactly what an in-memory database gets
differently.

---

## Who this serves

This system has **users** and **subjects**, and they are different
people.

Users are utility staff. They log in, they chose to be here, and they
can be trained and held accountable.

Subjects are consumers. They never log in, cannot see what the system
holds about them, and in most cases do not know it exists. But it
decides what they are billed, whether they are flagged as suspicious,
and whether someone is sent to cut their supply.

The people with the least power over this system are the ones it can do
the most to. Several design decisions follow directly from that, and the
harms it can cause are written down alongside the benefits in
`docs/who-this-serves.md`.

---

## Licence