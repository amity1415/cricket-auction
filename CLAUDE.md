# Cricket Auction — project notes for Claude

Spring Boot auction tracker. Multi-tournament: each auction owns its players/teams/sales
and a per-tournament rule book (`AuctionProperties` serialized into `tournament.rules_json`).
Two formats coexist — `STANDARD_POOL` (A–E tiers) and `ROLE_BASED_CASCADE` (ABPL). Rules are
data, not code: base prices, quotas, budgets, increments, retention, unsold-transitions.

## KCPL 2 format work — READ BEFORE ANY KCPL TASK
Before starting or continuing KCPL 2 work, consult these and keep the status file current:
- Design spec: `docs/KCPL2-FORMAT-DESIGN.md` (HLD + LLD + per-module tasks)
- Status / next steps: `docs/KCPL2-IMPLEMENTATION-STATUS.md` (update checkboxes as you go)

Non-negotiables: new rule-book fields are nullable/opt-in; carry-forward is gated so the
generic and ABPL auctions are byte-identical when the new fields are null; no new DB
tables/columns; fix every `new AuctionProperties(...)` call site when the record changes.

## Build / test
- Build: `./mvnw -q compile`
- Tests: `./mvnw -q test`

## Conventions
- Match surrounding code style; amounts are whole rupees.
- Commit/push only when asked; branch off `main` first.
