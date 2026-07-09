package com.auctiontracker.core;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Test fake for the {@link PlayerRepository} port — production uses Spring Data JPA. */
public class InMemoryPlayerRepository implements PlayerRepository {

    private final Map<UUID, Player> store = new ConcurrentHashMap<>();

    @Override
    public Player save(Player player) {
        store.put(player.getPlayerId(), player);
        return player;
    }

    @Override
    public Optional<Player> findById(UUID playerId) {
        return Optional.ofNullable(store.get(playerId));
    }

    @Override
    public List<Player> findAll() {
        return store.values().stream()
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public Optional<Player> findFirstByStatus(PlayerStatus status) {
        return store.values().stream().filter(p -> p.getStatus() == status).findFirst();
    }

    @Override
    public List<Player> findBySoldToTeamId(UUID teamId) {
        return store.values().stream()
                .filter(p -> teamId.equals(p.getSoldToTeamId()))
                .toList();
    }

    @Override
    public long count() {
        return store.size();
    }

    @Override
    public void deleteAll() {
        store.clear();
    }

    @Override
    public void deleteById(UUID playerId) {
        store.remove(playerId);
    }
}
