package com.auctiontracker.core;

import com.auctiontracker.tournament.RuleBook;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@link TeamRepository} the services see — scopes list/count to the active
 * tournament and stamps it on newly-saved teams. {@code findById}/{@code deleteById}
 * are id-based and need no scoping.
 */
@Repository
public class ScopedTeamRepository implements TeamRepository {

    private final TeamJpaRepository jpa;
    private final RuleBook ruleBook;

    public ScopedTeamRepository(TeamJpaRepository jpa, RuleBook ruleBook) {
        this.jpa = jpa;
        this.ruleBook = ruleBook;
    }

    @Override
    public Team save(Team team) {
        if (team.getTournamentId() == null) {
            team.setTournamentId(ruleBook.activeTournamentId());
        }
        return jpa.save(team);
    }

    @Override
    public Optional<Team> findById(UUID teamId) {
        return jpa.findById(teamId);
    }

    @Override
    public List<Team> findAll() {
        UUID tid = ruleBook.activeTournamentId();
        return tid == null ? jpa.findAll() : jpa.findByTournamentId(tid);
    }

    @Override
    public long count() {
        UUID tid = ruleBook.activeTournamentId();
        return tid == null ? jpa.count() : jpa.countByTournamentId(tid);
    }

    @Override
    public void deleteById(UUID teamId) {
        jpa.deleteById(teamId);
    }
}
