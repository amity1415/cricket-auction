# Cricket Auction Tracker — Architecture Document

**Version:** 3.0 (adds live bidding, finalizes monolith architecture)
**Supersedes:** Cricket_Auction_Tracker_HLD.docx v2.0
**Status:** Ready for implementation

---

## 1. Purpose

This document defines how the Cricket Auction Tracker is built and deployed. It picks up where the original HLD left off: that document scoped the system down to a single-writer (admin), multi-reader (team owners) model. This revision adds **live bidding** as a first-class feature — the admin records each bid as it happens in the room, the system enforces preset increment rules, and the final sale still commits through the same atomic transaction as before.

Companion document: `DESIGN.md` covers the detailed data model, API contracts, business rule ordering, and testing strategy. Read this document first for the "how it's structured," then `DESIGN.md` for the "exactly what each piece does."

---

## 2. Architecture style: modular monolith

**Decision: single deployable Spring Boot application, internally organized into clearly bounded modules.**

### Why not microservices

- There is exactly **one writer** — a human admin, clicking one button at a time. There is no concurrent-write race condition to solve, which is the problem microservice-style event buses and distributed locks usually exist to solve.
- Bid → sale → purse deduction → squad update → audit log is **one atomic unit of work**. Splitting these across services turns a single database transaction into a distributed transaction (2PC) or an eventually-consistent saga — both are strictly worse for a domain where "purse must never go negative" is a hard invariant.
- Traffic is low, bursty, and short-lived (a few hours, one room, a handful of teams). Nothing here needs independent scaling.
- Operational simplicity matters most during a live event — one JVM, one database, one log stream to watch while an auction is actually running.

### Why modular anyway

A tangled monolith is expensive to ever split later. A **modular** one — with clean internal boundaries and no module reaching into another's internals — gets most of the benefit people reach for microservices for (separation of concerns, independent testability, clear ownership) at a fraction of the cost. If a genuine scaling reason shows up later (see Section 8, Future Evolution), extracting a module into its own service is a lift-and-shift, not a rewrite.

---

## 3. Tech stack

| Layer | Choice | Notes |
|---|---|---|
| Backend | Spring Boot (Java) | Matches original HLD ownership (Backend Engineering / Spring Boot) |
| Persistence | PostgreSQL | Relational; transactional guarantees are load-bearing here |
| ORM | Spring Data JPA / Hibernate | `@Transactional` service methods for atomic commits |
| Frontend | Server-rendered fragments (Thymeleaf) + htmx, or a thin static HTML/JS client hitting the JSON API | See Section 6; no SPA framework needed for v1 |
| Auth | Spring Security, session or JWT — admin vs team-owner roles | Team-owner reads scoped strictly to their own team |
| Realtime (optional, Phase 6+) | Polling first; WebSocket (Spring's STOMP support) only if/when needed | Not required for correctness — see Section 8 |
| Build/deploy | Single fat JAR, one Postgres instance | No message queue, no Redis, no service mesh for v1 |

No distributed locking, event bus, or cache layer is needed for v1. Do not add Redis "just in case" — it solves a concurrency problem this system doesn't have.

---

## 4. Module structure

Organize as separate packages within one Spring Boot app, each with a narrow public interface (a `@Service` facade) — other modules call the facade, never repositories or entities belonging to another module.

```
com.auctiontracker
├── core/                    # Player, Team registry — the shared nouns
│   ├── Player.java
│   ├── Team.java
│   ├── PlayerRepository.java
│   ├── TeamRepository.java
│   └── CoreService.java     # register player/team, CRUD, CSV bulk import
│
├── bidding/                 # Live bid session + increment rules
│   ├── BidEvent.java
│   ├── BidEventRepository.java
│   ├── IncrementRuleConfig.java   # preset price-step table
│   └── BiddingService.java  # placeBid(), markUnderAuction() — depends on core.CoreService
│
├── sale/                    # Confirm sale / mark unsold — the atomic commit
│   ├── Sale.java            # audit record
│   ├── SaleRepository.java
│   └── SaleService.java     # confirmSale(), markUnsold() — depends on core + bidding
│
├── dashboard/                # Read-side projections, computed at request time
│   ├── DashboardService.java # per-team snapshot, maxAffordableBid calc
│   └── DashboardController.java
│
├── audit/                    # Chronological reporting
│   └── AuditController.java  # full sale history, final roster export
│
├── web/                      # HTTP layer
│   ├── AdminController.java
│   └── TeamController.java   # team-owner scoped reads
│
├── security/                 # Admin vs team-owner roles, auth config
│
└── config/                   # Application config, increment table values, etc.
```

**Dependency direction:** `web` → `sale`/`bidding`/`dashboard`/`audit` → `core`. Nothing in `core` depends on anything above it. This is what makes future extraction possible without a rewrite.

---

## 5. Component overview

```
┌─────────────────────────────────────────────────────────────┐
│                      Spring Boot monolith                     │
│                                                                 │
│   ┌──────────┐   ┌───────────┐   ┌──────────┐   ┌──────────┐ │
│   │   core    │◄──│  bidding  │◄──│   sale   │   │dashboard │ │
│   │ Player/   │   │ BidEvent, │   │ (atomic  │   │ (read-   │ │
│   │  Team     │   │ increment │   │  commit) │   │  only    │ │
│   │ registry  │   │  rules    │   │          │   │ compute) │ │
│   └──────────┘   └───────────┘   └──────────┘   └──────────┘ │
│         ▲               ▲              ▲              ▲       │
│         └───────────────┴──────────────┴──────────────┘       │
│                          web (REST controllers)                │
└────────────────────────────┬────────────────────────────────┘
                              │
                    ┌─────────┴─────────┐
                    │    PostgreSQL      │
                    │  (single database, │
                    │  single writer)    │
                    └─────────┬─────────┘
                              │
              ┌───────────────┴───────────────┐
              │                                │
       Admin console (write)          Team-owner dashboard (read-only,
       — place bid, confirm sale       polled every ~3s, scoped to
       — one browser tab, one admin    that team's own data)
```

---

## 6. Frontend approach

Consistent with the original HLD's own guidance ("no polished consumer frontend is required for v1"), keep the frontend as lightweight as the backend is simple:

- **Admin console:** a form-driven page — player pool table, "place bid" and "confirm sale" actions. No client-side state management needed; the server is the single source of truth after every action.
- **Team-owner dashboard:** a read-only page, polling `GET /api/dashboard/teams/{teamId}` every few seconds, or receiving Thymeleaf-rendered HTML fragments via htmx (`hx-trigger="every 3s"`).
- **No SPA framework, no build pipeline required for v1.** If/when Phase 6 (WebSocket push) lands, a small React or Vue layer becomes more justifiable for handling push-driven state — but don't build that speculatively.

---

## 7. Non-functional requirements

### 7.1 Consistency
Single relational transaction covers: bid acceptance (player + `BidEvent`), and separately, sale confirmation (player + team purse + team squad + `Sale` audit record). No distributed locking required — see Section 2.

### 7.2 Performance
- Bid placement must respond well under 300ms — it's used live, in the room, back-to-back.
- Dashboard recalculation for all teams must complete under 500ms even with 10–20 teams and several hundred players (unchanged from original HLD).
- Persist computed `remainingPurse` and squad state on the `Team` record itself rather than re-deriving from full history on every read.

### 7.3 Security
- Admin write endpoints (including `place-bid`) require admin authentication.
- Team-owner endpoints are strictly read-only and scoped to that team's own data.
- No raw stack traces in error responses.

### 7.4 Reliability
- If a bid or sale-confirmation transaction fails partway, nothing commits — prior state is preserved so the admin can safely retry.
- Duplicate-submission guard: reject a bid or sale confirmation for a player not in a valid state for that action (see `DESIGN.md` Section 5 for exact state machine).

### 7.5 Auditability
Every bid (not just the final sale) is written to `BidEvent`, giving a full replay of how each player's price climbed — useful for dispute resolution during the event itself, not just post-hoc reporting.

---

## 8. Future evolution path

The module boundaries in Section 4 are deliberately drawn so that, if any of the following ever becomes real, the corresponding module can be lifted into its own service without redesigning the data model:

- **Multiple concurrent, independent auctions (multi-tenant)** → `bidding` + `sale` could become a per-tenant scaled service, with `core` (player/team registry) staying centralized or also tenant-scoped.
- **WebSocket fan-out to many team-owner clients** → a `realtime` module/service can subscribe to sale/bid events and push to open connections, decoupled from the transactional core, which has a very different scaling shape (many long-lived connections vs. low-volume strong-consistency writes).
- **Player/team registry reused elsewhere** (stats site, other auction formats) → `core` is already the cleanest candidate to expose as a standalone service, since it has no bidding-specific logic in it.

None of these apply today. Do not build toward them speculatively — the modular boundary is the preparation; the extraction is future work, done only if a real need shows up.

---

## 9. Suggested build phases

| Phase | Deliverable |
|---|---|
| 1 | `core` module: Player and Team CRUD, no auction logic. Working backend with Swagger. |
| 2 | `bidding` module: place-bid endpoint, increment rule engine, bid validation. |
| 3 | `sale` module: confirm-sale (now sourced from the live bid state) and mark-unsold, full atomic commit. |
| 4 | `dashboard` module: all-teams snapshot, max-affordable-bid calculation. |
| 5 | `security`: admin vs team-owner roles, scoped read access. |
| 6 | `audit`: full sale/bid history, CSV bulk import, final roster export. |
| 7 (optional) | Polling-based or WebSocket-based live refresh for team-owner dashboards. |

This is the same phase structure as the original HLD, with bidding inserted as its own phase (2) ahead of sale confirmation, since confirm-sale now depends on it.

---

*End of document. See `DESIGN.md` for data model, API contracts, and detailed business rules.*
