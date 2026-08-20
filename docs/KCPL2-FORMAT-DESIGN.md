# KCPL 2 Auction Format — Design (HLD + LLD + Tasks)

**Status:** Design ready for implementation by a coding agent.
**Author intent:** Turn every rule in *KCPL 2 – Auction Format* into **dynamically configurable placeholders** on the auction-setup page, add a **group-budget carry-forward** pricing strategy, and wire the KCPL demotion + lottery rules — **without breaking the existing generic (STANDARD_POOL / A–E) or ABPL (ROLE_BASED_CASCADE) auctions.**

> Read this top-to-bottom once, then implement module by module (Section 7). Each LLD module has its own self-contained task list so a lower-capability agent can pick up one module at a time.

---

## 1. Source rules (KCPL 2, distilled)

From `KCPL 2 - Auction Format.pdf`:

| # | Rule | Where it maps in our engine |
|---|---|---|
| Teams | 10 teams | `team` rows (data, not config) |
| Squad | 20 players, mandatory | `teamDefaults.maxSquadSize = 20` |
| Purse | ₹1.5 Cr = ₹150 L per team | `teamDefaults.startingPurse = 15_000_000` |
| Pre-auction | ≤3 picks: 2 Icon @ ₹12 L fixed + 1 Owner/Rep @ ₹6 L fixed = ₹30 L; max 2 from Grade A, 1 from B/C/D | **Retention** feature |
| Pools | A (top 40, base ₹3 L), B (base ₹1 L), C (base ₹50 K), D (base ₹20 K) | `basePrices` per group |
| Sequence | A → B → C → D | auctioneer drives order (informational) |
| Pool budgets | A ≤ ₹60 L; B ₹50 L + carry; C ₹8 L + carry; D ₹2 L + carry | **NEW: carry-forward budget chain** |
| Pool A caps | ≤ ₹54 L for 2 players, ≤ ₹57 L for 3, ≤ ₹60 L for 4 | existing `budget` + `reservePerSlot` ceiling |
| Team comp | A: exactly/max 4 · B: min 3, max 6 · C: min 3 · D: min 2 | `categoryRules` min/max |
| Demotion | **A: none** · B→C · C→D · **never past D** | **`unsoldTransitions`** (config-driven) |
| Unsold D | returns to auction; if still unbought → lottery (manual amount) | self-transition D→D + existing **manual/floor bid** |
| Increments | 20–50 K: +5 K · 50 K–1 L: +10 K · 1–3 L: +20 K · 3 L+: +25 K | `incrementRules` + `defaultIncrement` |
| Leftover | remaining players divided mutually | **Non-goal** (offline) |
| League rule | 3-match minimum, penalties | **Non-goal** (not auction software) |

**Money reconciliation (the key invariant):** ₹150 L purse = ₹30 L pre-auction (retentions) + ₹120 L pools (60 + 50 + 8 + 2). The pool budgets must be measured against **auction spend only** — retention fees are deducted from the purse but must **not** consume pool budgets. See §4.3.

---

## 2. What already exists (do NOT rebuild)

The engine is already almost entirely data-driven per tournament. Grounding files:

- **Rule book model:** `config/AuctionProperties.java` — a `record` holding `basePrices`, `incrementRules`, `categoryRules` (max/min/reservePerSlot/**budget**), `retention`, `teamDefaults`, `demoteUnsoldPlayers`, `unsoldTransitions`, `retentionBasePriceMultiplier`.
- **Per-tournament rules:** `tournament/RuleBook.java` serializes `AuctionProperties` to the `tournament.rules_json` column; `ConfigController` exposes it at `GET /api/config`.
- **Pricing / feasibility:** `core/FeasibilityService.java` — purse check, squad-full check, **per-group budget ceiling** (`budget − spent − remainingSlots×reserve`), and squad-completion reserve.
- **Unsold cascade:** `sale/SaleService.markUnsold()` — already honours `unsoldTransitions` (move group + re-price + return AVAILABLE) and falls back to ordinal A→E demotion.
- **Pre-auction picks:** `sale/SaleService.retainPlayer()` + retention config + the setup-page retention UI (`setup.js`).
- **Manual / floor / lottery bid:** `BiddingService.placeBid(playerId, teamId, customAmount)` + the "✍️ Manual bid" control in `auction.html` / `app.js`.
- **Increments:** `bidding/IncrementRuleEngine.java`.
- **Rule editor UI:** `static/auctions.html` + `static/auctions.js` — the auction-setup editor. It already renders a per-group row with **Base / Max / Min / Reserve-per-slot / Budget** inputs, increment bands, retention fields, team defaults, and the demote toggle.

**Conclusion:** ~80% of KCPL 2 is already expressible by *filling in the existing config*. The genuinely new work is:

1. **Carry-forward budget chain** across groups (backend + one config flag). — §4
2. **Pool-budget spend must exclude retentions**, and separate pre-auction picks from pool quotas. — §4.3, §5
3. **UI placeholders** the editor doesn't yet expose: carry-forward toggle, per-group **unsold-transition (demotion target)** editor, retention base-price multiplier, and clearer group labels. — §6
4. **A KCPL Season 2 seeder** that ships the whole config as a ready template. — §7 M6

---

## 3. High-Level Design

### 3.1 Principle — configuration, not new code paths

KCPL 2 is **not** a new auction "engine". It is a **STANDARD_POOL tournament with a fully-populated rule book** plus one new *opt-in* pricing modifier (carry-forward). Everything the auctioneer tunes lives in `AuctionProperties` (serialized per tournament). This keeps the generic and ABPL auctions untouched: a rule book that doesn't set the new fields behaves exactly as today.

### 3.2 Component view

```
                         ┌─────────────────────────────┐
  auctions.html/js ────► │  POST/PUT /api/admin/        │
  (rule editor,          │  tournaments  (rules JSON)   │
   NEW placeholders)     └──────────────┬──────────────┘
                                        ▼
                            TournamentService.updateRules
                                        ▼
                            RuleBook  (parse/serialize,
                                       per-tournament cache)
                                        ▼
         ┌──────────────────────────────┼───────────────────────────────┐
         ▼                              ▼                                ▼
  FeasibilityService            BiddingService                    SaleService
  (NEW: carry-forward           (increments, manual/              (unsoldTransitions:
   budget ceiling;              lottery bid — unchanged)           A→A, B→C, C→D, D→D)
   exclude retentions
   from pool spend)
```

### 3.3 Data-model impact

- **No new tables.** All KCPL config rides in the existing `tournament.rules_json`.
- **`AuctionProperties` gains up to 3 nullable fields** (§4.1). Because it is a Java `record`, **every constructor call site must be updated** (compile-time break otherwise) — enumerated in §7 M1.
- Optional (only if §5 "separate pre-auction" decision is taken): a boolean flag `preAuctionCountsInPools`. No new player column is required if we key off the existing `RETAINED` status.

### 3.4 Backward-compatibility contract (must hold)

| New field | Absent / null / false ⇒ |
|---|---|
| `budgetCarryForward` | Per-group `budget` behaves as today (static independent ceiling; A–E generic has none) |
| `groupSequence` | Falls back to `configuredGroups()` insertion order |
| `preAuctionCountsInPools` | Defaults **true** = legacy (retained players count in quotas & spend) |

Add regression assertions that the existing STANDARD_POOL and ROLE_BASED_CASCADE fixtures produce identical numbers with the new fields null. — §7 M7.

---

## 4. Low-Level Design — Carry-forward budget chain (the one new mechanic)

### 4.1 Config additions (`AuctionProperties`)

Add these record components (all nullable ⇒ opt-in):

```java
// Groups auctioned in order; leftover budget of an earlier group rolls into the
// next. Null ⇒ derive order from basePrices/categoryRules insertion order.
List<PlayerCategory> groupSequence,

// When true, a group's spendable budget is cumulative: its own budget PLUS
// everything earlier groups in `groupSequence` didn't spend. Null/false ⇒ each
// group's budget is an independent static ceiling (today's behaviour).
Boolean budgetCarryForward,

// When false, RETAINED (pre-auction / icon / owner) players are excluded from
// BOTH pool budget-spend and group max/min quota counting. Null ⇒ true (legacy).
Boolean preAuctionCountsInPools
```

Add accessors mirroring the existing style:

```java
public List<PlayerCategory> effectiveGroupSequence() {
    if (groupSequence != null && !groupSequence.isEmpty()) return groupSequence;
    return new ArrayList<>(configuredGroups());   // preserves LinkedHashMap order
}
public boolean carryForwardEnabled()   { return Boolean.TRUE.equals(budgetCarryForward); }
public boolean preAuctionCountsInPools(){ return budgetCarryForward == null || Boolean.TRUE.equals(preAuctionCountsInPools); }
```

> ⚠️ Jackson deserializes JSON objects into `LinkedHashMap`, so `basePrices` key order survives a round-trip — but do **not** rely on it for correctness. `groupSequence` is the authoritative order when carry-forward is on; validate at save time that every configured group with a budget appears in it (§7 M1).

### 4.2 The math

For a team bidding on a player in group **G**, define along `effectiveGroupSequence()`:

```
cumulativeBudget(G) = Σ  budget(k)        for every k up to and including G
                     k≤G
cumulativeSpend(G)  = Σ  poolSpend(k)      for every k strictly before G
                     k<G
effectiveBudget(G)  = cumulativeBudget(G) − cumulativeSpend(G)
                    = budget(G) + carriedLeftover(before G)
```

Hard ceiling on a bid in G (replaces the static `budget` term inside the existing ceiling formula):

```
maxBidInGroup(G) = effectiveBudget(G) − poolSpend(G) − remainingSlotsAfter × reservePerSlot(G)
```

where `remainingSlotsAfter = max(0, maxPerTeam(G) − heldInG − 1)` (unchanged), and `poolSpend(k)` is **auction spend only** (§4.3).

Because auction order is strictly A→B→C→D and earlier pools are closed before later ones open, `cumulativeSpend(G)` is stable at the moment of bidding — no need to track an explicit "current pool" phase.

**Worked KCPL example** (team, group budgets A 60 / B 50 / C 8 / D 2 L, all in ₹L):
- In A: effectiveBudget(A) = 60. Buys 4 A players for 45 total ⇒ leftover 15.
- In B: effectiveBudget(B) = (60+50) − 45 = 65. (= own 50 + carried 15.) ✓
- Spends 55 in B ⇒ cumulativeSpend through B = 100, cumulativeBudget = 110.
- In C: effectiveBudget(C) = (60+50+8) − 100 = 18. (= own 8 + carried 10.) ✓
- In D: effectiveBudget(D) = 120 − (spend A+B+C).

### 4.3 Pool spend excludes retentions

`FeasibilityService.groupSpend(...)` currently sums `soldPrice` for every squad member in the group **including RETAINED** icons. For KCPL that would wrongly charge the ₹24 L of Grade-A icons against Pool A's ₹60 L.

Change: when `!rules.preAuctionCountsInPools()`, `groupSpend` counts only `status == SOLD` players. The retention fee is still deducted from `remainingPurse` (so the completion-reserve and purse checks already see the ₹30 L gone), but it does not touch pool budgets — reproducing the 150 = 30 + 120 split exactly.

### 4.4 Where the code changes

`core/FeasibilityService.java` — three touch points, all additive/guarded:

1. **`assertCanAcquire`** — the budget block is today nested under `if (maxInGroup != null)`. Pull the budget ceiling **out** so it applies even when a group has no max (KCPL's C/D have min but may have no max). Replace the static `budget` with `effectiveBudgetFor(cat, squad)` when `carryForwardEnabled()`.
2. **`maxBidFor`** — same substitution so the dashboard's per-team "max bid" reflects carry-forward.
3. **`groupSpend`** — add the RETAINED exclusion from §4.3.

New private helper:

```java
private long effectiveBudgetFor(PlayerCategory group, List<Player> squad) {
    AuctionProperties r = ruleBook.current();
    List<PlayerCategory> seq = r.effectiveGroupSequence();
    long cumulativeBudget = 0, spentBefore = 0;
    for (PlayerCategory g : seq) {
        Long b = r.budgetFor(g);
        if (b != null) cumulativeBudget += b;
        if (g == group) break;
        spentBefore += groupSpend(squad, g);   // SOLD-only when preAuction excluded
    }
    return cumulativeBudget - spentBefore;
}
```

Guard: if `!carryForwardEnabled()`, keep calling the existing static-`budget` path so nothing changes for other tournaments.

### 4.5 Invariants / tests to assert (§7 M7)

- Sum of the 4 pool budgets equals purse − total retention fees (soft check / warning at save time only; not enforced hard because organizers may leave slack).
- A bid that would push `cumulativeSpend(G)` above `cumulativeBudget(G)` is rejected with a clear message ("… Pool C has ₹18 L available after carry-forward, ₹x already spent …").
- With `budgetCarryForward` null, numbers equal today's for the existing fixtures.

---

## 5. Low-Level Design — Demotion & lottery (mostly config)

KCPL demotion is expressed **entirely through `unsoldTransitions`** — `SaleService.markUnsold()` already implements the mechanic (move category, re-price to destination base, return AVAILABLE). Self-transitions are allowed by the current code (it just sets the same category back).

KCPL transition graph:

| Group | Transition | Effect |
|---|---|---|
| A | **A → A** | No demotion. Unsold Grade-A returns to the pool at ₹3 L, re-auctioned in A. |
| B | B → C | Demotes to C at ₹50 K, appears at end of C. |
| C | C → D | Demotes to D at ₹20 K, appears at end of D. |
| D | **D → D** | Sticky floor — never past D. Returns AVAILABLE at ₹20 K, re-auctioned; if still unbought, auctioneer awards by **lottery via the manual/floor bid** (types the agreed amount, picks the winning team). |

`demoteUnsoldPlayers = false` (transitions drive everything; the ordinal A→E path is bypassed when a transition entry exists).

**Ordering note ("appears at the end of Pool C/D"):** the pool list sorts by `player.seq` (`PlayerJpaRepository`). A freshly demoted player keeps its original `seq`, so it will **not** automatically sort to the end of the destination pool. This is a **known limitation** — see Open Decision OD-3. Options: (a) accept current behaviour (auctioneer just picks it up whenever); (b) re-stamp `seq` to `maxSeq+1` on demotion so it lands last. Recommend (a) for v1 (no code), (b) as a small follow-up if the organizer insists.

**Code change required:** none for the transition mechanic itself. Only the **editor UI** must let the admin author this graph (§6), and the KCPL seeder must set it (§7 M6). Optionally add a one-line guard in `SaleService` documenting that a self-transition = "no demotion, stay available" so a future reader doesn't 'fix' it.

---

## 6. Low-Level Design — Editor placeholders (`auctions.html` + `auctions.js`)

Goal: **every KCPL number is editable in the auction-setup page** — nothing hardcoded. The editor already covers base/max/min/reserve/**budget** per group, increment bands, retention, team defaults, and the demote toggle. Add:

### 6.1 Carry-forward toggle
- HTML: a checkbox `#f-carryForward` in the **Player groups** fieldset, label: *"Carry a group's unspent budget forward into the next group (Pool A → B → C → D)."*
- JS `fillEditor`: `$('f-carryForward').checked = !!rules.budgetCarryForward;`
- JS `readRules`: `budgetCarryForward: $('f-carryForward').checked`.

### 6.2 Group sequence
- The order of the group rows in `#groupRules` **is** the sequence. In `readRules`, emit `groupSequence` = the group codes in DOM order (top-to-bottom). Add up/down reorder buttons (or leave manual re-add) — minimum viable: read DOM order. Document that row order = auction/carry order.

### 6.3 Unsold-transition (demotion target) editor — *the ABPL feature to bring to the generic editor*
- Per group row, add a **"Unsold →"** `<select>` (`.gr-transition`) listing: *"(finally unsold)"*, *"(stay — no demotion)"* = self, and every other configured group. Optional numeric `.gr-transition-price` (blank ⇒ destination base).
- `fillEditor`: populate each row's select from `rules.unsoldTransitions[code]` (map `destination` back to the option; self ⇒ "stay").
- `readRules`: build `unsoldTransitions` object from the selects. "(finally unsold)" ⇒ omit the entry; "(stay)" ⇒ `{destination: sameCode, destinationBasePrice: price||null}`.
- Keep the existing `editorBaseRules` spread so anything unmodeled still round-trips.

### 6.4 Retention base-price multiplier — *ABPL feature to generic editor*
- In the **Retention** fieldset add `#f-ret-multiplier` (number, blank = use flat costs). `fillEditor`/`readRules` map `retentionBasePriceMultiplier`.
- KCPL leaves it blank (flat ₹12 L / ₹6 L). ABPL uses 3.

### 6.5 Labels / helper text
- Above the group table, note: *"Row order is the auction order; with carry-forward on, unspent budget flows down this list."*
- Retention fieldset heading gets sub-text: *"KCPL: pre-auction Icon/Owner picks — 2 from A @ ₹12 L, 1 from B–D @ ₹6 L (≤3, ₹30 L)."*

### 6.6 Constants
- `auctions.js` `GROUPS`/`ALL_GROUPS` already include A–E. No change; KCPL uses A, B, C, D.

No API change needed: `TournamentController.CreateRequest` already carries the whole `AuctionProperties rules` object, and Jackson will (de)serialize the new fields automatically once they exist on the record.

---

## 7. Implementation modules & tasks

> Order matters: **M1 → M2 → M6** are the backbone. M3–M5 (UI) and M7 (tests) can proceed in parallel after M1. Each task is phrased so it can be done and verified in isolation.

### M1 — Extend the rule-book schema (`AuctionProperties`)
- [ ] **T1.1** Add the 3 nullable record components `groupSequence`, `budgetCarryForward`, `preAuctionCountsInPools` to `config/AuctionProperties.java` (end of the component list, after `retentionBasePriceMultiplier`).
- [ ] **T1.2** Add helpers `effectiveGroupSequence()`, `carryForwardEnabled()`, `preAuctionCountsInPools()` (§4.1).
- [ ] **T1.3** Update **every constructor call site** (compile break). Known sites: `TestFixtures.props()`, `TestFixtures.realisticProps()`, `AbplSeeder.roleBasedCascadeRules()`, and the future `KcplSeeder` (M6). Grep for `new AuctionProperties(` before compiling.
- [ ] **T1.4** In `TournamentService.validateSquadFeasibility` (or a new `validateRules`), when `carryForwardEnabled()`: assert every group that has a `budget` also appears in `groupSequence`; warn (log, don't fail) if Σ budgets > `startingPurse`.
- [ ] **T1.5** Confirm `RuleBook.parse/serialize` round-trips the new fields (Jackson, no annotations needed) — add a tiny unit test.

### M2 — Carry-forward pricing in `FeasibilityService`
- [ ] **T2.1** Add `effectiveBudgetFor(PlayerCategory, List<Player>)` per §4.4.
- [ ] **T2.2** In `assertCanAcquire`, lift the budget ceiling out of the `if (maxInGroup != null)` block so it also applies to max-less groups (C/D). Use `effectiveBudgetFor` when `carryForwardEnabled()`, else the existing static `budgetFor`.
- [ ] **T2.3** Mirror the same in `maxBidFor` (dashboard figure).
- [ ] **T2.4** In `groupSpend`, exclude `RETAINED` players when `!preAuctionCountsInPools()`.
- [ ] **T2.5** Improve the rejection message to name the carry-forward budget ("Pool C: ₹18 L available after carry-forward, …").
- [ ] **T2.6** Guard so that when carry-forward is off, code path and numbers are byte-identical to today.

### M3 — Editor: carry-forward + sequence (§6.1–6.2, 6.5)
- [ ] **T3.1** Add `#f-carryForward` checkbox + helper text to `auctions.html` groups fieldset.
- [ ] **T3.2** Wire it in `fillEditor` / `readRules`.
- [ ] **T3.3** Emit `groupSequence` from DOM row order in `readRules`; add a short "row order = auction/carry order" hint. (Optional: ↑/↓ buttons on each group row.)

### M4 — Editor: unsold-transition graph (§6.3)
- [ ] **T4.1** Extend `addGroupRow` markup with a `.gr-transition` select (+ optional `.gr-transition-price`) and a column header.
- [ ] **T4.2** Populate options from current group codes (+ "(finally unsold)" + "(stay — no demotion)").
- [ ] **T4.3** `fillEditor`: preselect from `rules.unsoldTransitions`.
- [ ] **T4.4** `readRules`: assemble `unsoldTransitions`; keep `editorBaseRules` spread.
- [ ] **T4.5** Re-populate transition dropdowns when a group is added/removed (they reference other groups).

### M5 — Editor: retention multiplier + labels (§6.4–6.5)
- [ ] **T5.1** Add `#f-ret-multiplier` to the Retention fieldset; wire fill/read.
- [ ] **T5.2** Add the KCPL pre-auction helper text.

### M6 — KCPL Season 2 seeder
- [ ] **T6.1** Create `config/KcplSeeder.java` modelled on `AbplSeeder` (idempotent by name, `@Order(LOWEST_PRECEDENCE)`, runs after `TournamentBootstrap`). Name: `"KCPL Season 2"`, `auctionRuleType = "STANDARD_POOL"`.
- [ ] **T6.2** Build the rule book (values below), register 10 teams (₹1.5 Cr, squad 20).
- [ ] **T6.3** Do **not** seed players (organizer imports the real 40/60/45/25 pool via the setup page). Optionally seed a tiny demo pool behind a flag.
- [ ] **T6.4** Leave every other tournament untouched (same guard style as `AbplSeeder`).

**KCPL rule book (exact, whole rupees):**
```
minViablePrice        = 20_000
basePrices            = {A:300_000, B:100_000, C:50_000, D:20_000}
incrementRules        = [{upTo:50_000, inc:5_000}, {upTo:100_000, inc:10_000}, {upTo:300_000, inc:20_000}]
defaultIncrement      = 25_000                       // ₹3 L onwards
categoryRules         = {
  A: {max:4, min:4, reservePerSlot:300_000, budget:6_000_000},  // ₹60 L, 4-player reserve
  B: {max:6, min:3, reservePerSlot:null,    budget:5_000_000},  // ₹50 L
  C: {max:null, min:3, reservePerSlot:null, budget:  800_000},  // ₹8 L
  D: {max:null, min:2, reservePerSlot:null, budget:  200_000},  // ₹2 L
}
retention             = {maxPerTeam:3, maxFromGroupA:2, maxFromLowerGroups:1,
                         costGroupA:1_200_000, costOtherGroups:600_000}   // 2×12L + 1×6L = 30L
retentionBasePriceMultiplier = null                  // flat fees
teamDefaults          = {startingPurse:15_000_000, maxSquadSize:20}
demoteUnsoldPlayers   = false
unsoldTransitions     = {A:{A,null}, B:{C,null}, C:{D,null}, D:{D,null}}
groupSequence         = [A, B, C, D]
budgetCarryForward    = true
preAuctionCountsInPools = false
```
> Verify: Σ budgets = 60+50+8+2 = ₹120 L = ₹150 L − ₹30 L retentions. ✓
> A `min:4` assumes the "4 from Pool A" are auction buys separate from icons (OD-1). If icons count inside the 4, set `A.min` per OD-1 resolution.

### M7 — Tests
- [ ] **T7.1** `CarryForwardBudgetTest` (unit, mirror `CategoryRulesTest`/`FeasibilityServiceTest` style, use in-memory repos): A→B→C→D leftover flows; ceiling rejects an over-budget bid; `maxBidFor` reflects carry.
- [ ] **T7.2** Retention-exclusion test: a Grade-A icon retained for ₹12 L does **not** reduce Pool A's ₹60 L budget but **does** reduce `remainingPurse`.
- [ ] **T7.3** Demotion test: B unsold → C at ₹50 K AVAILABLE; D unsold → D (sticky) AVAILABLE; A unsold → A (no demotion), never terminal UNSOLD.
- [ ] **T7.4** Manual/lottery bid on a re-auctioned D player at a typed amount ≥ base succeeds and passes feasibility.
- [ ] **T7.5** Regression: existing `FeasibilityServiceTest`, `CategoryRulesTest`, `SaleServiceTest`, `RetentionTest` still pass with the new null fields (they should compile via T1.3 and behave identically).
- [ ] **T7.6** `RuleBook` JSON round-trip includes the 3 new fields.

### M8 — Docs
- [ ] **T8.1** Add a KCPL section to `docs/DESIGN.md` (or link this file) describing carry-forward.
- [ ] **T8.2** Update `README` / setup help text if it enumerates supported formats.

---

## 8. Open decisions (confirm with the organizer before/while coding)

- **OD-1 — Do the 2 pre-auction Icons (Grade A) count inside "only 4 from Pool A"?**
  - If **no** (recommended, matches the 2+1+4+3+3+2… squad breakdown): keep `preAuctionCountsInPools=false` so retained icons don't consume Pool-A slots/budget; set `A.min=4`, `A.max=4` for auction buys.
  - If **yes**: set `preAuctionCountsInPools=true`, `A.max=4` (icons + buys), and drop `A.min` to ~2.
- **OD-2 — Pool-A "min 4":** enforce as a hard min (bid engine already reserves base for min slots) or leave soft and rely on rule 13's "last player given at base price" fallback (manual bid)? Default: soft (set `A.min` to reserve budget but allow the manual-award fallback).
- **OD-3 — "Appears at end of Pool C/D":** accept current `seq`-order (no code) or re-stamp `seq` on demotion (small change in `SaleService.markUnsold`)? Default: accept for v1.
- **OD-4 — Hard vs soft budget-sum check:** enforce Σ pool budgets ≤ purse − retentions at save, or warn only? Default: warn only (organizers may leave slack).

## 9. Non-goals
- 10-team / player-count enforcement (data, not rules).
- "Leftover players divided mutually" (offline).
- 3-match league participation / penalties.
- Auto-advancing pool phases (auctioneer drives sequence manually, as today).

## 10. No-conflict guarantee (checklist for the reviewer)
- [ ] All new `AuctionProperties` fields are nullable and default to legacy behaviour.
- [ ] `carryForwardEnabled()` gates every new `FeasibilityService` branch.
- [ ] `groupSpend` change is behind `!preAuctionCountsInPools()`.
- [ ] No new DB columns/tables; only `tournament.rules_json` content changes.
- [ ] ABPL (ROLE_BASED_CASCADE) and generic (STANDARD_POOL A–E) fixtures unchanged (T7.5).
- [ ] Editor still saves/loads ABPL and generic rule books (transition + multiplier round-trip via `editorBaseRules`).
```
