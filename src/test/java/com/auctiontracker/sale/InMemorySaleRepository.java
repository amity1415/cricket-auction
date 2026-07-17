package com.auctiontracker.sale;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Test fake for the {@link SaleRepository} port — production uses Spring Data JPA. */
public class InMemorySaleRepository implements SaleRepository {

    private final Map<UUID, Sale> store = new ConcurrentHashMap<>();

    @Override
    public Sale save(Sale sale) {
        store.put(sale.getSaleId(), sale);
        return sale;
    }

    @Override
    public List<Sale> findAllByOrderByRecordedAtAsc() {
        return store.values().stream()
                .sorted(Comparator.comparing(Sale::getRecordedAt))
                .toList();
    }

    @Override
    public void deleteByPlayerId(UUID playerId) {
        store.values().removeIf(s -> s.getPlayerId().equals(playerId));
    }

    @Override
    public void deleteAll() {
        store.clear();
    }
}
