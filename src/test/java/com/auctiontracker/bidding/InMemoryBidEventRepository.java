package com.auctiontracker.bidding;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Test fake for the {@link BidEventRepository} port — production uses Spring Data JPA. */
public class InMemoryBidEventRepository implements BidEventRepository {

    private final Map<UUID, BidEvent> store = new ConcurrentHashMap<>();

    @Override
    public BidEvent save(BidEvent event) {
        store.put(event.getBidEventId(), event);
        return event;
    }

    @Override
    public List<BidEvent> findByPlayerIdOrderByBidNumberAsc(UUID playerId) {
        return store.values().stream()
                .filter(e -> e.getPlayerId().equals(playerId))
                .sorted(Comparator.comparingInt(BidEvent::getBidNumber))
                .toList();
    }

    @Override
    public long countByPlayerId(UUID playerId) {
        return store.values().stream().filter(e -> e.getPlayerId().equals(playerId)).count();
    }

    @Override
    public void deleteAll() {
        store.clear();
    }
}
