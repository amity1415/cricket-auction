package com.auctiontracker.tournament;

import com.auctiontracker.config.AuctionProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one place that answers "which tournament are we in, and what are its rules?"
 *
 * The active tournament is global (exactly one at a time) — selecting a different
 * one loads it for everyone (admin, owners, guests, the broadcast). Its rule book
 * is a serialized {@link AuctionProperties} stored on the tournament row, so all
 * the existing rule helpers (base prices, quotas, budgets, increments, retention)
 * work unchanged — they just read the active tournament's values instead of the
 * single application.yml bean. When no tournament is active yet (fresh boot), the
 * application.yml defaults are used.
 */
@Service
public class RuleBook {

    private final TournamentRepository tournaments;
    private final AuctionProperties defaults;   // application.yml — also seeds "KCPL"
    private final ObjectMapper mapper;

    private volatile UUID activeId;             // cached active tournament id
    private final Map<UUID, AuctionProperties> ruleCache = new ConcurrentHashMap<>();

    public RuleBook(TournamentRepository tournaments, AuctionProperties defaults, ObjectMapper mapper) {
        this.tournaments = tournaments;
        this.defaults = defaults;
        this.mapper = mapper;
    }

    /**
     * A fixed rule book with no tournament backing — for unit tests and any
     * context without a database. There is no active tournament, so scoped
     * repositories behave unscoped and {@link #current()} returns these rules.
     */
    public static RuleBook fixed(AuctionProperties rules) {
        return new RuleBook(null, rules, null);
    }

    /** Id of the active tournament, or null before any exists (bootstrap / tests). */
    public UUID activeTournamentId() {
        if (tournaments == null) {
            return null;
        }
        UUID id = activeId;
        if (id == null) {
            id = tournaments.findFirstByActiveTrue().map(Tournament::getId).orElse(null);
            activeId = id;
        }
        return id;
    }

    /** Rules of the active tournament (application.yml defaults if none active). */
    public AuctionProperties current() {
        UUID id = activeTournamentId();
        if (id == null) {
            return defaults;
        }
        return ruleCache.computeIfAbsent(id, k ->
                tournaments.findById(k).map(t -> parse(t.getRulesJson())).orElse(defaults));
    }

    /** The application.yml rule book — used to seed the first ("KCPL") tournament. */
    public AuctionProperties defaults() {
        return defaults;
    }

    // --- cache invalidation, called by the tournament service on changes ---

    public void activeChanged(UUID newActiveId) {
        this.activeId = newActiveId;
    }

    public void rulesChanged(UUID tournamentId) {
        ruleCache.remove(tournamentId);
    }

    // --- (de)serialization of the rule book to/from the tournament's JSON column ---

    public String serialize(AuctionProperties rules) {
        try {
            return mapper.writeValueAsString(rules);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize tournament rules", e);
        }
    }

    public AuctionProperties parse(String json) {
        try {
            return mapper.readValue(json, AuctionProperties.class);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read tournament rules", e);
        }
    }
}
