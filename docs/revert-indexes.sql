-- ROLLBACK for docs/add-indexes.sql — drops the performance indexes.
--
-- Run in the Neon SQL editor if you ever need to undo the indexes. Safe and
-- re-runnable (IF EXISTS). CONCURRENTLY avoids locking writes, so it's safe even
-- during a live auction; run each statement on its own (not inside a transaction).
--
-- Note: dropping these only removes the speedup — it cannot corrupt or change data.

DROP INDEX CONCURRENTLY IF EXISTS idx_player_tournament_status;
DROP INDEX CONCURRENTLY IF EXISTS idx_player_sold_to_team;
DROP INDEX CONCURRENTLY IF EXISTS idx_sale_tournament_recorded;
DROP INDEX CONCURRENTLY IF EXISTS idx_sale_player;
