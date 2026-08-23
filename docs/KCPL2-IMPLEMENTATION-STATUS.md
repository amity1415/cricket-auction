# KCPL 2 — Implementation Status & Next Steps

Living tracker for the KCPL 2 format work. **Design spec:** [`KCPL2-FORMAT-DESIGN.md`](KCPL2-FORMAT-DESIGN.md) — read it before touching any KCPL task. Update the checkboxes here as modules land.

## Guiding rules (do not violate)
- Every new `AuctionProperties` field is **nullable and opt-in**; absent ⇒ today's behaviour.
- Carry-forward logic must be gated by `carryForwardEnabled()`; the generic (A–E) and ABPL (role-based) auctions must produce **identical** numbers when the new fields are null.
- No new DB tables/columns — all KCPL config rides in `tournament.rules_json`.
- Adding a field to the `AuctionProperties` record breaks every `new AuctionProperties(...)`; fix all call sites (`TestFixtures` ×2, `AbplSeeder`, `KcplSeeder`).

## Module status
| Module | What | Status |
|---|---|---|
| M1 | Extend `AuctionProperties` (3 fields + helpers, call sites, validation, round-trip) | ✅ done |
| M2 | Carry-forward pricing in `FeasibilityService` (+ retention-exclusion) | ✅ done |
| M6 | `KcplSeeder` — KCPL Season 2 template (10 teams, full rule book) | ✅ done |
| M3 | Editor: carry-forward toggle + group sequence (DOM order) | ✅ done |
| M4 | Editor: unsold-transition graph per group row | ✅ done |
| M5 | Editor: retention multiplier + KCPL labels | ✅ done |
| M7 | Tests + regression | ✅ done |
| M8 | Docs polish | ⬜ not started |
| M9 | Player-pool import from the KCPL CricHeroes stats sheet + expanded stats | ✅ done |

Legend: ⬜ not started · 🟡 in progress · ✅ done

## Recommended order
1. **M1** (schema) → unblocks everything.
2. **M2** (pricing) — the one new mechanic.
3. **M6** (seeder) — makes KCPL loadable end-to-end.
4. **M3–M5** (editor) — expose the placeholders.
5. **M7** (tests) — lock it in.

## Open decisions (see design §8 — confirm with organizer)
- **OD-1** Do the 2 Grade-A Icons count inside "only 4 from Pool A"? (drives `preAuctionCountsInPools` + `A.min/max`)
- **OD-2** Pool-A "min 4": hard min or soft (rely on base-price fallback)?
- **OD-3** "Appears at end of Pool C/D": accept current `seq` order, or re-stamp `seq` on demotion?
- **OD-4** Enforce Σ pool budgets ≤ purse − retentions at save, or warn only?

## Change log
- 2026-08-23 — **Rule re-sync**: `KcplSeeder` now, when "KCPL Season 2" already
  exists, brings an older rule book up to canonical **once** — guarded on
  `retention.maxPerGroup` being null (the marker of a pre-cap seed). It rewrites
  `rules_json` to `kcplRules()` (2 Icon @ ₹12L + 1 Owner @ ₹6L per-category caps,
  budgets 60/50/8/2 L carry-forward, retentions off pool budgets, no ordinal
  demotion) via `TournamentService.updateRules`, preserving teams/players/owners/
  photo folder. Fixes the live tournament (seeded before the per-category cap) on
  next deploy without a manual admin edit. Idempotent: once `maxPerGroup` is set it
  never re-runs. 94 tests green.
- 2026-08-22 — **M9**: `PlayerStats` gained batting innings, highest score (text, keeps the
  `*` not-out marker), bowling innings, and best bowling (text, e.g. `5/13`). The `.xlsx`
  importer now reads the KCPL CricHeroes export directly: `SetupService` collapses the two-row
  `BATTING/BOWLING` banner + column header into one disambiguated header row; `PlayerRowParser`
  maps `Grading`→category (Icon/Owner → the new `PlayerCategory.ICON` pool, base ₹12 L when the
  rule book has no ICON base), tolerates spaced role names, and reads the new stat columns. All
  player views (profile page, modal, list table, auction/broadcast strips) and the setup add/edit
  form show the new fields; the profile page/image space was already present. Verified: real
  200-row sheet maps to A=40/B=60/C=45/D=25/ICON=30. 86 tests green.
- 2026-08-20 — Design spec written.
- 2026-08-20 — M1, M2, M6, M7, M3–M5 implemented & verified (85 tests green; editor round-trip verified in browser). Remaining: M8 docs polish; OD-1…OD-4 to confirm with organizer.
