package com.auctiontracker.core;

import com.auctiontracker.tournament.RuleBook;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@link PlayerRepository} the services see. It scopes every collection read,
 * count and bulk-delete to the active tournament, and stamps the active tournament
 * on each newly-saved player. Id lookups ({@code findById}, {@code findBySoldToTeamId})
 * need no scoping — a player id (and a team id) belongs to exactly one tournament.
 *
 * Before any tournament exists (fresh boot) the active id is null and this behaves
 * exactly like the old unscoped repository.
 */
@Repository
public class ScopedPlayerRepository implements PlayerRepository {

    private final PlayerJpaRepository jpa;
    private final RuleBook ruleBook;

    public ScopedPlayerRepository(PlayerJpaRepository jpa, RuleBook ruleBook) {
        this.jpa = jpa;
        this.ruleBook = ruleBook;
    }

    @Override
    public Player save(Player player) {
        if (player.getTournamentId() == null) {
            player.setTournamentId(ruleBook.activeTournamentId());
        }
        return jpa.save(player);
    }

    @Override
    public Optional<Player> findById(UUID playerId) {
        return jpa.findById(playerId);
    }

    @Override
    public List<Player> findAll() {
        UUID tid = ruleBook.activeTournamentId();
        return tid == null ? jpa.findAll() : jpa.findByTournamentId(tid);
    }

    @Override
    public Optional<Player> findFirstByStatus(PlayerStatus status) {
        UUID tid = ruleBook.activeTournamentId();
        return tid == null
                ? jpa.findAll().stream().filter(p -> p.getStatus() == status).findFirst()
                : jpa.findFirstByStatusAndTournamentId(status, tid);
    }

    @Override
    public List<Player> findBySoldToTeamId(UUID teamId) {
        return jpa.findBySoldToTeamId(teamId);
    }

    @Override
    public long count() {
        UUID tid = ruleBook.activeTournamentId();
        return tid == null ? jpa.count() : jpa.countByTournamentId(tid);
    }

    @Override
    public void deleteAll() {
        UUID tid = ruleBook.activeTournamentId();
        if (tid == null) {
            jpa.deleteAllInBatch();
        } else {
            jpa.deleteByTournamentId(tid);
        }
    }

    @Override
    public void deleteById(UUID playerId) {
        jpa.deleteById(playerId);
    }
}
