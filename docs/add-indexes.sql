-- Performance indexes for the hot polling queries.
--
-- Safe to run on the live Neon database RIGHT NOW, before any redeploy:
--   * IF NOT EXISTS  -> re-runnable, no error if already present.
--   * CONCURRENTLY   -> builds without locking out writes, so a live auction
--                       keeps working while the index is created. (Must be run
--                       OUTSIDE a transaction — paste/run each statement on its
--                       own in the Neon SQL editor; do not wrap in BEGIN/COMMIT.)
--
-- Indexes never change query RESULTS — only how fast Postgres finds rows — so
-- this cannot change application behaviour. Fully reversible: DROP INDEX <name>;
--
-- These match the @Index declarations on Player.java and Sale.java, so a fresh
-- database created by Hibernate gets the same indexes automatically.

-- player: every dashboard/pool poll filters by tournament_id (+ status for the
-- "on the block" lookup). Composite also serves tournament_id-only filters.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_player_tournament_status
    ON player (tournament_id, status);

-- player: squad lookups (findBySoldToTeamId) and the dashboard's per-team grouping.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_player_sold_to_team
    ON player (sold_to_team_id);

-- sale: the audit log is re-read on every admin/broadcast/ticker poll,
-- filtered by tournament_id and ordered by recorded_at.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sale_tournament_recorded
    ON sale (tournament_id, recorded_at);

-- sale: per-player audit-row deletes on re-auction.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sale_player
    ON sale (player_id);
