# Cricket Auction Tracker

Modular Spring Boot monolith implementing `docs/ARCHITECTURE.md` and `docs/DESIGN.md`.

**This is v1.5**: persistence is Spring Data JPA — an embedded H2 database by default (zero
setup, data lost on restart), or any PostgreSQL (e.g. a free [Neon](https://neon.tech) instance)
via `DATABASE_URL` for durable storage. No authentication yet. The frontend is served by the
same application — no separate deployment.

## Run

```bash
./mvnw spring-boot:run                # embedded H2, no setup

DATABASE_URL='jdbc:postgresql://<host>/<db>?sslmode=require' \
DATABASE_USERNAME=... DATABASE_PASSWORD=... \
./mvnw spring-boot:run                # real Postgres (Neon etc.), survives restarts
```

Requires Java 21+ (the Maven wrapper downloads Maven itself).

| URL | What |
|---|---|
| http://localhost:8081/ | Setup page (landing): bulk-import players from .csv/.xlsx, register teams |
| http://localhost:8081/auction.html | Admin console (put on block, bid, confirm/unsold, audit) |
| http://localhost:8082/team.html | Team-owner dashboard (read-only, polls every 3s) |
| http://localhost:8082/broadcast.html | Live spectator dashboard (current player + all team purses, updates every 1s) |
| http://localhost:8082/player.html?playerId=… | Full player profile (career stats, auction state, bid history) — click any player name |
| http://localhost:8081/swagger-ui.html | Swagger / OpenAPI docs |

Four demo teams (₹15 Cr purse, squad cap 8, 3 overseas slots, role minimums 2 BAT / 2 BWL / 1 AR / 1 WK)
and twenty demo players are seeded at startup. Disable with `auction.seed-demo-data=false`.

## Tests

```bash
./mvnw test
```

Unit tests cover the DESIGN.md §8.1 priorities: increment band boundaries, every place-bid
rejection case, the confirm-sale commit (player + purse + squad + audit together, double-click
safety), and `maxAffordableBid` edge cases.

## API

Write endpoints (admin, DESIGN.md §6.1) under `/api/admin`, reads under `/api`. Quick smoke test:

```bash
# Pick a player and team id
PLAYER=$(curl -s 'localhost:8081/api/players?status=AVAILABLE' | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["playerId"])')
TEAM=$(curl -s localhost:8081/api/dashboard | python3 -c 'import json,sys; print(json.load(sys.stdin)["teams"][0]["teamId"])')

curl -X POST localhost:8081/api/admin/players/$PLAYER/mark-under-auction
curl -X POST localhost:8081/api/admin/players/$PLAYER/place-bid \
     -H 'Content-Type: application/json' -d "{\"teamId\":\"$TEAM\"}"
curl -X POST localhost:8081/api/admin/players/$PLAYER/undo-bid   # pop the last bid (cache-only)
curl -X POST localhost:8081/api/admin/players/$PLAYER/place-bid \
     -H 'Content-Type: application/json' -d "{\"teamId\":\"$TEAM\"}"
curl -X POST localhost:8081/api/admin/players/$PLAYER/confirm-sale
curl -s localhost:8081/api/admin/audit
```

CSV bulk import — append semantics, header optional, no commas in names. Full column layout
(basePrice and the six stats columns are optional; stats become the player's profile shown
while under auction):

```
name,role,category,overseas[,basePrice][,matches,runs,battingAvg,strikeRate,wickets,economy]
```

```bash
curl -X POST localhost:8081/api/admin/players/bulk-import \
     -H 'Content-Type: text/csv' \
     --data-binary $'name,role,category,overseas\nRavi Kumar,BOWLER,B,false\nJack Doyle,BATSMAN,A,true'
```

Replace import (what the setup page uses) — accepts a `.csv` **or `.xlsx`** upload, deletes all
existing players, and resets auction progress (bids, sales, squads, purses) in one transaction:

```bash
curl -X POST localhost:8081/api/admin/players/bulk-import-replace -F file=@players.xlsx
```

## The rule book (`application.yml` → `auction.*`)

Every rule-based number lives in the property file, not in code. Players belong to **groups A
(top tier) … E (lowest)**; each group has a configurable base price (`auction.base-prices`).

| Rule | Config key | Current value |
|---|---|---|
| Base price per group | `auction.base-prices` | A ₹6L · B ₹4L · C ₹2L · D ₹1L · E ₹50K |
| Bid increment bands | `auction.increment-rules` | ₹10K→₹2.5L steps by band |
| Max players per team per group | `auction.category-rules.<G>.max-per-team` | A 4 · B 5 · C 4 · D 4 · E 4 |
| Min players per team per group | `auction.category-rules.<G>.min-per-team` | all 0 |
| **Group-A budget ceiling (Rule 1)** | `auction.category-rules.A.budget` | ₹50L hard cap on total Group-A spend (only Group A) |
| **Group-A reserve per slot (Rule 1)** | `auction.category-rules.A.reserve-per-slot` | ₹6L (= base) |
| Pre-auction retention caps | `auction.retention` | 3 per team: max 2 from group A, 1 from B–E |
| **Retention cost (Rule 2)** | `auction.retention.cost-group-a` / `cost-other-groups` | ₹12L (A) · ₹6L (B–E) |
| Unsold demotion | `auction.demote-unsold-players` | true (group E is a sticky floor — stays sellable) |
| Feasibility floor | `auction.min-viable-price` | ₹50K (cheapest group) |
| Team registration defaults | `auction.team-defaults` | ₹1.5Cr purse, squad 20 |

**Rule 1** has two parts:
- **Group-A ceiling (Group A only):** total Group-A spend is hard-capped at ₹50L, and within it a
  bid must leave the remaining allowed A slots buyable at base. So max Group-A bid =
  `₹50L − spent-in-A − (remaining A slots after) × ₹6L` → 1st A player caps at **₹32L**, then ₹38L,
  ₹44L, ₹50L cumulative. Enforced as `GROUP_BUDGET_EXCEEDED`.
- **Squad-completion reserve (all groups):** every bid must leave enough purse to still fill the
  team's remaining squad slots at base price (cheapest completion). This — not a per-group cap — is
  what bounds B/C/D/E, so money not spent on Group A flows freely to the other groups. Enforced as
  `SQUAD_FEASIBILITY_BROKEN`.

**Rule 2 (flat retention cost):** retaining a player deducts a flat fee by group
(`cost-group-a` for A, `cost-other-groups` for B–E), not the player's base price.

**Group E is never terminally unsold:** a group-E player marked unsold while under auction
returns to the pool as AVAILABLE, so it can always be put back on the block.

There is deliberately **no overseas quota and no role minimum** — overseas and role make-up
are shown on dashboards as information only. **Retention** (`POST
/api/admin/teams/{teamId}/retain/{playerId}`) puts an AVAILABLE player on the team's squad at
base price (deducted from the purse, `RETAINED` status, audit-logged); release
(`POST /api/admin/players/{id}/release-retention`) refunds and returns them to the pool. The
setup page has a retention manager UI.

The setup page also does full CRUD via modal dialogs: add/edit/remove players
(`POST/PUT/DELETE /api/admin/players[/{id}]` — locked once retained/on the block/sold) and
edit/remove teams (`PUT/DELETE /api/admin/teams/{id}` — purse edits preserve the spent amount;
removal requires an empty squad).

- **Group max** is hard-blocked at bid time *and* sale time (`CATEGORY_QUOTA_FULL`).
- **Group min** feeds the mandatory-slot purse reserve the same way role minimums do. Since one
  signing can satisfy a role minimum and a group minimum simultaneously, the reserve is the
  **max** of the two deficits, not their sum.
- **Unsold demotion**: a player marked unsold while under auction drops one group (A→B→…→E),
  takes the lower group's base price, and returns to the pool as `AVAILABLE` for re-auction.
  A group-E player can't drop further but **still returns to `AVAILABLE`** — the sell option for
  a lowest-group player never goes away. Withdrawing a player who was never put on the block is
  always terminal `UNSOLD` (no demotion).
- The rule book is served read-only at `GET /api/config` for the UI.

## Decisions taken on DESIGN.md §9 open questions

All are config-tunable or isolated in one place; flag disagreements and they're quick to change.

1. **Overseas quota enforcement point** — enforced at **bid time** (and re-checked at sale time).
   Since the server picks the price, a bid that could never legally convert to a sale is noise;
   blocking early keeps the room honest. One `if` in `FeasibilityService.assertCanAcquire`.
2. **Minimum viable price** — **flat floor**, set to the cheapest group's base price
   (`auction.min-viable-price`). Per-role floors can come later.
3. **Self-outbid** — **always rejected** (`SELF_OUTBID`), per DESIGN.md 5.3 rule 2.
4. **Increment table** — the DESIGN.md §4 bands, in `application.yml` under
   `auction.increment-rules`. Band upper bounds are **inclusive** (a current price of exactly
   ₹20,00,000 still steps by ₹5,00,000).

Also assumed: amounts are **whole rupees stored as `long`** (auction prices never need paise).
The original "UNSOLD is terminal" assumption is superseded by the demotion rule above.

## Module map (ARCHITECTURE.md §4)

```
com.auctiontracker
├── core/       Player (+PlayerStats profile), Team, repositories (JPA), CoreService,
│               PlayerRowParser (CSV/row parsing), FeasibilityService
├── bidding/    BidEvent, IncrementRuleEngine, BiddingService (mark-under-auction, place-bid)
├── sale/       Sale audit record, SaleService (confirm-sale, mark-unsold)
├── setup/      SetupService — replace-import of the player pool (.csv/.xlsx via POI)
├── dashboard/  DashboardService + controller (read-side projections)
├── audit/      AuditController (GET /api/admin/audit)
├── web/        AdminController, PlayerQueryController, error handling, DTOs
└── config/     AuctionProperties (increment table etc.), DataSeeder
```

Dependency direction: `web` → `sale`/`bidding`/`dashboard`/`audit` → `core`. Modules only call
each other's `@Service` facades, never foreign repositories or entities.

## Database

- Each repository is a plain interface (`PlayerRepository`, `TeamRepository`, `BidEventRepository`,
  `SaleRepository`) implemented by a one-line Spring Data JPA interface (`*JpaRepository`) — all
  queries are derived from method names. Unit tests use `InMemory*` fakes of the same ports.
- **Default: embedded H2** in PostgreSQL-compatibility mode — zero setup, fresh (re-seeded)
  database every start.
- **Postgres: set `DATABASE_URL`** (plus `DATABASE_USERNAME` / `DATABASE_PASSWORD`) as shown in
  the Run section. Works with any Postgres; tested with Neon's free tier. Data survives restarts —
  the seeder skips itself when teams already exist.
- Schema is created/evolved by Hibernate (`ddl-auto: update`) — switch to Flyway migrations
  before a real event.
- Write paths (`confirmSale`, `markUnsold`, registration, bulk import) are `@Transactional`;
  `Team` carries a JPA `@Version` for optimistic locking. The `AuctionLock` monitor stays as a
  same-instance double-click guard.
- **Live bids never touch the database.** Each `place-bid` goes into an in-memory
  `LiveBidSession` (instant, and undoable via `POST …/undo-bid`); the polling frontend reads it
  through the dashboard API. The full trail is flushed to `BidEvent` rows inside the same
  transaction as confirm-sale / mark-unsold, so the audit replay still commits atomically with
  the outcome. Trade-off: a crash mid-bidding loses in-flight bids — the player stays
  UNDER_AUCTION and bidding restarts from base price.

## Known gaps (deliberate, per phase plan)

- No auth (`security` module is phase 5) — team dashboard is open, "recordedBy" is hardcoded `admin`.
- Single instance only (JVM-local `AuctionLock`; optimistic locking is the DB-level backstop).
- Polling, not WebSocket push (phase 7).
- Hibernate-managed schema, no migration history yet (Flyway is the next persistence step).
