package com.auctiontracker.core;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Test fake for the {@link TeamRepository} port — production uses Spring Data JPA. */
public class InMemoryTeamRepository implements TeamRepository {

    private final Map<UUID, Team> store = new ConcurrentHashMap<>();

    @Override
    public Team save(Team team) {
        team.setVersion(team.getVersion() + 1); // mimics JPA @Version bumping
        store.put(team.getTeamId(), team);
        return team;
    }

    @Override
    public Optional<Team> findById(UUID teamId) {
        return Optional.ofNullable(store.get(teamId));
    }

    @Override
    public List<Team> findAll() {
        return store.values().stream()
                .sorted(Comparator.comparing(Team::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public long count() {
        return store.size();
    }

    @Override
    public void deleteById(UUID teamId) {
        store.remove(teamId);
    }
}
