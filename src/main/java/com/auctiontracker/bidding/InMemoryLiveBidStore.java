package com.auctiontracker.bidding;

import com.auctiontracker.core.AuctionLock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-JVM {@link LiveBidStore}: the original in-memory {@link LiveBidSession}
 * per tournament, plus each lot's auto-close deadline, all guarded by the shared
 * {@link AuctionLock}. This is the default store — used whenever Redis is not
 * enabled — and is byte-for-byte the behaviour the app shipped with. Lost on
 * restart by design (the player stays UNDER_AUCTION and bidding restarts).
 */
public class InMemoryLiveBidStore implements LiveBidStore {

    /** Key used before any tournament exists (fresh boot / tests). */
    private static final UUID NO_TOURNAMENT = new UUID(0L, 0L);

    private final AuctionLock lock;
    private final Map<UUID, LiveBidSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> deadlines = new ConcurrentHashMap<>();

    public InMemoryLiveBidStore(AuctionLock lock) {
        this.lock = lock;
    }

    private static UUID key(UUID tournamentId) {
        return tournamentId == null ? NO_TOURNAMENT : tournamentId;
    }

    private LiveBidSession session(UUID tournamentId) {
        return sessions.computeIfAbsent(key(tournamentId), k -> new LiveBidSession());
    }

    private static LiveBidStore.Step toStep(LiveBidSession.Step s) {
        return s == null ? null : new LiveBidStore.Step(s.teamId(), s.amount(), s.at());
    }

    private void armDeadline(UUID tournamentId, Integer timerSeconds) {
        if (timerSeconds != null && timerSeconds > 0) {
            deadlines.put(key(tournamentId), Instant.now().plusSeconds(timerSeconds));
        } else {
            deadlines.remove(key(tournamentId));
        }
    }

    @Override
    public UUID currentPlayer(UUID tournamentId) {
        synchronized (lock) {
            return session(tournamentId).currentPlayerId();
        }
    }

    @Override
    public void open(UUID tournamentId, UUID playerId, Integer timerSeconds) {
        synchronized (lock) {
            session(tournamentId).open(playerId);
            armDeadline(tournamentId, timerSeconds);
        }
    }

    @Override
    public void ensureOpen(UUID tournamentId, UUID playerId, Integer timerSeconds) {
        synchronized (lock) {
            LiveBidSession s = session(tournamentId);
            if (!s.isFor(playerId)) {
                s.open(playerId);
                armDeadline(tournamentId, timerSeconds);
            }
        }
    }

    @Override
    public void close(UUID tournamentId, UUID playerId) {
        synchronized (lock) {
            LiveBidSession s = session(tournamentId);
            if (s.isFor(playerId)) {
                s.close();
                deadlines.remove(key(tournamentId));
            }
        }
    }

    @Override
    public boolean isFor(UUID tournamentId, UUID playerId) {
        synchronized (lock) {
            return session(tournamentId).isFor(playerId);
        }
    }

    @Override
    public PushOutcome tryPush(UUID tournamentId, UUID playerId, UUID teamId, long amount,
                               long basePrice, Integer timerSeconds) {
        synchronized (lock) {
            LiveBidSession s = session(tournamentId);
            if (!s.isFor(playerId)) {
                return new PushOutcome(PushStatus.NOT_LIVE, 0, null);
            }
            LiveBidSession.Step last = s.last();
            if (last == null) {
                if (amount < basePrice) {
                    return new PushOutcome(PushStatus.BELOW_BASE, 0, null);
                }
            } else {
                if (last.teamId().equals(teamId)) {
                    return new PushOutcome(PushStatus.SELF_OUTBID, s.count(), last.amount());
                }
                if (amount <= last.amount()) {
                    return new PushOutcome(PushStatus.TOO_LOW, s.count(), last.amount());
                }
            }
            s.push(teamId, amount);
            armDeadline(tournamentId, timerSeconds);
            return new PushOutcome(PushStatus.OK, s.count(), amount);
        }
    }

    @Override
    public Step popLast(UUID tournamentId, UUID playerId, Integer timerSeconds) {
        synchronized (lock) {
            LiveBidSession s = session(tournamentId);
            if (!s.isFor(playerId)) {
                return null;
            }
            LiveBidSession.Step removed = s.popLast();
            if (removed != null) {
                armDeadline(tournamentId, timerSeconds);
            }
            return toStep(removed);
        }
    }

    @Override
    public Step last(UUID tournamentId, UUID playerId) {
        synchronized (lock) {
            LiveBidSession s = session(tournamentId);
            return s.isFor(playerId) ? toStep(s.last()) : null;
        }
    }

    @Override
    public int count(UUID tournamentId, UUID playerId) {
        synchronized (lock) {
            LiveBidSession s = session(tournamentId);
            return s.isFor(playerId) ? s.count() : 0;
        }
    }

    @Override
    public List<Step> steps(UUID tournamentId, UUID playerId) {
        synchronized (lock) {
            LiveBidSession s = session(tournamentId);
            if (!s.isFor(playerId)) {
                return List.of();
            }
            List<Step> out = new ArrayList<>();
            for (LiveBidSession.Step step : s.stepsInOrder()) {
                out.add(toStep(step));
            }
            return out;
        }
    }

    @Override
    public void setPendingVerbal(UUID tournamentId, UUID playerId, Long amount) {
        synchronized (lock) {
            LiveBidSession s = session(tournamentId);
            if (s.isFor(playerId)) {
                s.setPendingVerbalAmount(amount);
            }
        }
    }

    @Override
    public Long pendingVerbal(UUID tournamentId, UUID playerId) {
        synchronized (lock) {
            LiveBidSession s = session(tournamentId);
            return s.isFor(playerId) ? s.pendingVerbalAmount() : null;
        }
    }

    @Override
    public Instant deadline(UUID tournamentId, UUID playerId) {
        synchronized (lock) {
            return session(tournamentId).isFor(playerId) ? deadlines.get(key(tournamentId)) : null;
        }
    }

    @Override
    public Optional<UUID> claimExpiredClose(UUID tournamentId) {
        synchronized (lock) {
            UUID k = key(tournamentId);
            Instant d = deadlines.get(k);
            UUID player = session(tournamentId).currentPlayerId();
            if (d == null || player == null || Instant.now().isBefore(d)) {
                return Optional.empty();
            }
            deadlines.remove(k);   // claimed — no second close for this lot
            return Optional.of(player);
        }
    }

    @Override
    public void forget(UUID tournamentId) {
        synchronized (lock) {
            UUID k = key(tournamentId);
            sessions.remove(k);
            deadlines.remove(k);
        }
    }
}
