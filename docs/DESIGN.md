# Cricket Auction Tracker — Design Document

**Version:** 3.0 (adds live bidding)
**Companion to:** `ARCHITECTURE.md`
**Status:** Ready for implementation

---

## 1. Purpose

This document specifies the exact data model, business rules, API contracts, and testing strategy for the Cricket Auction Tracker. It's written to be implementation-ready — a developer (or coding agent) should be able to build directly from this without needing to infer intent.

---

## 2. Actors

| Actor | Responsibility |
|---|---|
| Admin (Auctioneer) | Only actor who can write data. Registers players/teams pre-auction. During the auction: marks a player under auction, places bids on behalf of whichever team calls one out, confirms the sale, or marks unsold. |
| Team owner | Authenticated, read-only access to their own team's dashboard. |
| Spectator (optional, future) | Public, anonymized dashboard view. Not required for v1. |

---

## 3. Data model

### 3.1 Player

| Field | Type | Notes |
|---|---|---|
| playerId | UUID | Primary key |
| name | String | |
| role | Enum | `BATSMAN`, `BOWLER`, `ALL_ROUNDER`, `WICKETKEEPER` |
| category | Enum | `MARQUEE`, `A`, `B`, `C` — base price tier |
| basePrice | Decimal | Starting price, derived from category (configurable) |
| isOverseas | Boolean | Used for overseas quota checks |
| status | Enum | `AVAILABLE`, `UNDER_AUCTION`, `SOLD`, `UNSOLD` |
| currentBidAmount | Decimal, nullable | **New.** Live price while `UNDER_AUCTION` |
| currentLeadingTeamId | UUID, nullable | **New.** Current leading bidder |
| soldToTeamId | UUID, nullable | Set only when status = `SOLD` |
| soldPrice | Decimal, nullable | Final confirmed price |
| soldAt | Instant, nullable | Timestamp of sale confirmation |

### 3.2 Team

| Field | Type | Notes |
|---|---|---|
| teamId | UUID | Primary key |
| name | String | |
| ownerName | String | |
| startingPurse | Decimal | Total budget at auction start |
| remainingPurse | Decimal | Live remaining budget |
| maxSquadSize | Integer | Configurable squad cap |
| minPerRole | JSON | e.g. `{BOWLER:4, BATSMAN:4, WICKETKEEPER:1}` |
| maxOverseasPlayers | Integer | Overseas quota |
| version | Long | Optimistic locking version |

### 3.3 BidEvent (new)

One row per bid, not just the final sale — gives a full replay of how a player's price climbed.

| Field | Type | Notes |
|---|---|---|
| bidEventId | UUID | Primary key |
| playerId | UUID | Player currently under auction |
| teamId | UUID | Team placing this bid |
| amount | Decimal | Computed by the server (see Section 5), never client-supplied |
| bidNumber | Integer | Sequence within this player's auction (1, 2, 3…) |
| recordedAt | Instant | Server timestamp |

### 3.4 Sale (audit log, unchanged from original HLD)

| Field | Type | Notes |
|---|---|---|
| saleId | UUID | Primary key |
| playerId | UUID | |
| teamId | UUID | Winning team |
| amount | Decimal | Final confirmed price |
| recordedBy | String | Admin user identifier |
| recordedAt | Instant | |

Every confirmed sale and every unsold decision is written here. This table, together with `BidEvent`, gives a complete replayable history of the auction.

---

## 4. Bid increment rules

A configurable lookup table — not hardcoded — so organizers can tune it per auction.

| Current price range | Increment |
|---|---|
| Up to ₹20,00,000 | ₹5,00,000 |
| ₹20,00,000 – ₹1,00,00,000 | ₹10,00,000 |
| ₹1,00,00,000 – ₹2,00,00,000 | ₹20,00,000 |
| Above ₹2,00,00,000 | ₹25,00,000 |

The first bid on a player starts from `basePrice` (no increment applied yet). Every subsequent bid adds the increment matching the *current* price band.

Store this as an ordered list of `{threshold, increment}` in application config (`IncrementRuleConfig`), not in the database, since it rarely changes mid-event and doesn't need per-player overrides for v1.

---

## 5. Core flows

### 5.1 Player/team setup (pre-auction) — unchanged
- Register player (name, role, category, base price, overseas flag)
- Bulk import players via CSV
- Register team (name, owner, starting purse, max squad size, role minimums, overseas quota)

### 5.2 Mark player under auction
`POST /api/admin/players/{id}/mark-under-auction`
- Player must be `AVAILABLE`.
- Clears any other player currently `UNDER_AUCTION` (only one player "on the block" at a time).
- Sets status = `UNDER_AUCTION`, `currentBidAmount` = null, `currentLeadingTeamId` = null.

### 5.3 Place bid — the new core operation
`POST /api/admin/players/{id}/place-bid { teamId }`

**Deliberately no `amount` in the request body.** The server always computes the next price itself. This removes any chance of a mistyped price during a fast-moving auction and guarantees every bid is a legal increment.

**Server computes:**
```
nextAmount = (currentBidAmount == null)
    ? player.basePrice
    : currentBidAmount + incrementFor(currentBidAmount)
```

**Validation, in order:**
1. Player must be `UNDER_AUCTION` (not `AVAILABLE`, `SOLD`, or `UNSOLD`).
2. `teamId` must not already be `currentLeadingTeamId` — a team cannot outbid itself.
3. Team's `remainingPurse ≥ nextAmount`.
4. Squad-feasibility check, run against the *hypothetical* price (same logic as Section 5.5, rule 5, but evaluated before committing — see Section 5.6 for the shared helper).
5. If all checks pass: update `player.currentBidAmount = nextAmount`, `player.currentLeadingTeamId = teamId`, insert a `BidEvent` row with `bidNumber` = previous count + 1.

**On failure:** return a specific, actionable reason (e.g. `"Chennai Chargers can't afford ₹1,20,00,000 — ₹95,00,000 remaining"`), not a generic error — the admin needs to relay this to the room immediately.

**Note:** nothing is deducted from the team's purse at this point. This is a *quote*, not a commitment.

### 5.4 Confirm sale
`POST /api/admin/players/{id}/confirm-sale`

No body required for the normal case — it commits whatever is currently `currentBidAmount` / `currentLeadingTeamId`. (Optionally support an admin override body for correcting a data-entry mistake mid-event; log any override distinctly in the audit trail.)

**Validation, in order:**
1. Player must be `UNDER_AUCTION` with a non-null `currentLeadingTeamId` (i.e., at least one bid was placed).
2. Re-run the same purse and squad-feasibility checks from Section 5.3 as a final guard (defense in depth — state shouldn't have changed between the last bid and confirmation, but never trust that assumption).
3. If all checks pass, **commit atomically** in a single transaction:
   - `player.status = SOLD`, `soldToTeamId`, `soldPrice = currentBidAmount`, `soldAt = now`
   - Clear `currentBidAmount` / `currentLeadingTeamId`
   - `team.remainingPurse -= soldPrice`
   - Add player to team's squad
   - Write a `Sale` audit record
   - A failure partway through rolls back everything — never leave partial state.

### 5.5 Mark unsold
`POST /api/admin/players/{id}/mark-unsold`
- Player must be `AVAILABLE` or `UNDER_AUCTION`.
- Sets status = `UNSOLD`, clears any bid state. No purse impact.
- Handles both "nobody bid" and "leading bidder walks away at the last second" cases.

### 5.6 Shared validation helper: squad feasibility

Used by both `place-bid` (hypothetically) and `confirm-sale` (for real):

```
remainingMandatorySlots(team) =
    sum over each role in team.minPerRole of
        max(0, team.minPerRole[role] - team.currentCountInRole[role])

feasible(team, hypotheticalPrice) =
    (team.remainingPurse - hypotheticalPrice) is enough to
    still fill remainingMandatorySlots(team) at minimumViablePrice each,
    AND team.squadSize < team.maxSquadSize
```

This is the same rule as the original HLD's Section 5.2 rule 5, now reused at bid-time (advisory-turned-hard-block during bidding, since the server is choosing the price) as well as at sale-time.

### 5.7 Dashboard calculation (unchanged formula, still correct with bidding added)

```
maxAffordableBid = remainingPurse − (remainingMandatorySlots − 1) × minimumViablePrice
```

- `remainingPurse` — team's current available budget (not affected by an in-progress bid until it's confirmed).
- `remainingMandatorySlots` — count of mandatory role slots not yet filled (including the player about to be bid on, hence −1).
- `minimumViablePrice` — configurable floor, typically the cheapest category's base price.

This value is advisory during bidding — shown to help team owners (and the admin) see how far a team can safely go — but Section 5.3 rule 4 is what actually hard-blocks a bid that would break squad feasibility.

---

## 6. API surface

### 6.1 Admin endpoints (write access)

| Method | Endpoint | Purpose |
|---|---|---|
| POST | `/api/admin/players` | Register a player |
| POST | `/api/admin/players/bulk-import` | CSV bulk import |
| POST | `/api/admin/teams` | Register a team with purse and squad rules |
| POST | `/api/admin/players/{id}/mark-under-auction` | Put a player on the block |
| POST | `/api/admin/players/{id}/place-bid` | **New.** Record a bid for a team; server computes price |
| POST | `/api/admin/players/{id}/confirm-sale` | Commit the current leading bid |
| POST | `/api/admin/players/{id}/mark-unsold` | Mark unsold, clear bid state |
| GET | `/api/admin/audit` | Full chronological sale/unsold history |
| GET | `/api/admin/players/{id}/bids` | **New.** Full bid history for one player |

### 6.2 Shared read endpoints (admin and team owner)

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/dashboard` | Full dashboard, all teams (admin view) |
| GET | `/api/dashboard/teams/{teamId}` | Single team's dashboard (auth-scoped) |
| GET | `/api/players?status=AVAILABLE` | Browse player pool by status/role/category |
| GET | `/api/players/{id}/current-bid` | **New.** Live price + leading team for the player under auction |

### 6.3 Sample: place-bid

Request:
```json
POST /api/admin/players/p1/place-bid
{
  "teamId": "t2"
}
```

Success response:
```json
{
  "playerId": "p1",
  "status": "UNDER_AUCTION",
  "currentBidAmount": 1500000,
  "currentLeadingTeamId": "t2",
  "bidNumber": 3,
  "nextMinimumIncrement": 500000
}
```

Rejected response (HTTP 409):
```json
{
  "error": "INSUFFICIENT_PURSE",
  "message": "Chennai Chargers can't afford ₹15,00,000 — ₹9,50,000 remaining",
  "teamId": "t2",
  "attemptedAmount": 1500000,
  "remainingPurse": 950000
}
```

### 6.4 Sample: confirm-sale response (dashboard snapshot, unchanged shape from original HLD)

```json
{
  "player": {
    "playerId": "p1",
    "status": "SOLD",
    "soldToTeamId": "t2",
    "soldPrice": 1500000
  },
  "teams": [
    {
      "teamId": "t2",
      "name": "Chennai Chargers",
      "remainingPurse": 8500000,
      "squadFilled": 6,
      "squadOpenSlots": 9,
      "maxAffordableBid": 5500000
    }
  ],
  "lastUpdated": "2026-07-03T10:15:00Z"
}
```

---

## 7. State machine summary

```
AVAILABLE ──mark-under-auction──▶ UNDER_AUCTION
                                       │
                    place-bid (repeatable, 0+ times)
                                       │
                    ┌──────────────────┴──────────────────┐
                    ▼                                      ▼
              confirm-sale                            mark-unsold
                    │                                      │
                    ▼                                      ▼
                  SOLD                                  UNSOLD
            (terminal state)                       (terminal state)
```
`AVAILABLE` can also go straight to `UNSOLD` (mark-unsold without ever going under auction — e.g., a player withdrawn pre-auction).

---

## 8. Testing strategy

### 8.1 Unit tests — highest priority, most bugs live here
- Increment calculation: correct step for every price band, including exact boundary values.
- `place-bid` validation: reject self-outbid, insufficient purse, squad-full, and squad-feasibility-broken cases, each as a separate test.
- `confirm-sale` atomic commit: verify all four state changes (player, purse, squad, audit) happen together; simulate a failure partway through and assert full rollback.
- `maxAffordableBid` formula: edge cases — zero remaining mandatory slots, exactly one slot left, purse of zero.
- State machine: every invalid transition (e.g., `place-bid` on a `SOLD` player, `confirm-sale` with no bids yet) returns the correct error, not a silent no-op.

### 8.2 Integration tests
- Full HTTP flow against a real test database (Testcontainers with Postgres, or an in-memory equivalent): mark-under-auction → place-bid ×3 → confirm-sale → assert dashboard reflects the new purse/squad for the winning team *and* that no other team's data changed.
- Concurrent-request guard: fire two `confirm-sale` calls for the same player in quick succession (simulating a double-click), assert only one succeeds and the second gets a clean "already sold" error, not a corrupted state.

### 8.3 End-to-end tests (Playwright)
- Full admin flow: register a team and player → mark under auction → place several bids across different teams → confirm sale → verify the team-owner dashboard (separate browser context, scoped auth) reflects the update.
- Rejected-bid UX: attempt a bid that exceeds a team's purse, assert the specific error message renders in the admin console.
- Mark-unsold path: player never sold, dashboard unaffected for all teams.

### 8.4 Scenario checklist (manual or scripted) — domain-rule edge cases worth enumerating explicitly
- Team has exactly enough purse for the mandatory-slot reserve and one more bid — does the system correctly block the next increment?
- Overseas quota: does the system need to block a bid (not just a sale) if the team's overseas slots are already full? *(Decide before implementation — the original HLD only hard-blocks this at sale time; confirm whether Section 5.3 should too.)*
- A player is marked under auction, then marked unsold with zero bids placed — does the audit log record this cleanly?
- Rapid-fire bidding: place 10+ bids on one player in quick succession, confirm final price and bid count match.

### 8.5 Load/concurrency
Not a priority for v1 given the single-writer model (see `ARCHITECTURE.md` Section 2), but a basic smoke test — a few dozen sequential `place-bid` calls under 300ms each — is worth scripting (k6 or a simple loop test) to catch performance regressions before a live event, since bid placement is used live, back-to-back, under time pressure.

### 8.6 Suggested test-writing order
Given the phase plan in `ARCHITECTURE.md` Section 9: write unit tests for each module as it's built (increment engine and validation logic first, since they're pure functions with no I/O), add integration tests once `sale` module lands (it's the first place atomicity actually matters), and layer E2E tests on top once there's a frontend to drive.

---

## 9. Open configuration decisions

Flag these for the organizer/admin to confirm before build, since they affect validation logic directly:

1. **Overseas quota enforcement point** — block at bid time, or only at sale time? (Section 8.4 above)
2. **Minimum viable price** — flat floor (cheapest category), or per-role floor (e.g., wicketkeepers might realistically cost more than the cheapest bowler)?
3. **Self-outbid rule** — can a team raise its own leading bid (e.g., to signal commitment), or is that always rejected?
4. **Increment table values** — confirm the bands in Section 4 match the actual auction's currency scale and appetite for price movement.

---

*End of document.*
