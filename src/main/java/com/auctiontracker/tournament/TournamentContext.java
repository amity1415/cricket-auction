package com.auctiontracker.tournament;

import java.util.UUID;

/**
 * The tournament a single request is operating in, held per-thread for the life
 * of the request (set by {@link TournamentContextFilter} from the
 * {@code X-Tournament-Id} header or {@code tournamentId} query param). This is
 * what lets many auctions run at once: each screen/tab carries its own auction
 * id, so two requests on the same server operate on different tournaments.
 * When absent, {@link RuleBook} falls back to the default (active) tournament.
 */
public final class TournamentContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TournamentContext() {}

    public static void set(UUID id) { CURRENT.set(id); }

    public static UUID get() { return CURRENT.get(); }

    public static void clear() { CURRENT.remove(); }
}
