package com.auctiontracker.tournament;

import com.auctiontracker.config.AuctionProperties;
import com.auctiontracker.core.AuctionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Create / list / activate tournaments. Exactly one tournament is active at a
 * time (global) — activating one deactivates the rest and points the
 * {@link RuleBook} at it, so every view (admin, owners, broadcast) operates
 * within it from that moment.
 */
@Service
public class TournamentService {

    private final TournamentRepository tournaments;
    private final RuleBook ruleBook;

    public TournamentService(TournamentRepository tournaments, RuleBook ruleBook) {
        this.tournaments = tournaments;
        this.ruleBook = ruleBook;
    }

    public List<Tournament> list() {
        return tournaments.findAllByOrderByCreatedAtAsc();
    }

    public Tournament get(UUID id) {
        return tournaments.findById(id).orElseThrow(() ->
                AuctionException.notFound("TOURNAMENT_NOT_FOUND", "No tournament with id " + id));
    }

    /** Rules of a specific tournament (for the editor to prefill). */
    public AuctionProperties rulesOf(UUID id) {
        return ruleBook.parse(get(id).getRulesJson());
    }

    /** The active tournament, if any. */
    public Tournament active() {
        return tournaments.findFirstByActiveTrue().orElse(null);
    }

    /**
     * Creates a new tournament with its own rule book and immediately makes it
     * active (you create it to work in it). Starts with no players/teams — the
     * admin drives the same setup → auction flow as KCPL.
     */
    @Transactional
    public Tournament create(String name, AuctionProperties rules) {
        if (name == null || name.isBlank()) {
            throw AuctionException.badRequest("INVALID_TOURNAMENT", "Tournament name must not be blank");
        }
        if (rules == null) {
            throw AuctionException.badRequest("INVALID_TOURNAMENT", "Tournament rules are required");
        }
        Tournament t = Tournament.create(name.trim(), uniqueSlug(name), ruleBook.serialize(rules));
        deactivateAll();
        t.setActive(true);
        tournaments.save(t);
        ruleBook.activeChanged(t.getId());
        ruleBook.rulesChanged(t.getId());
        return t;
    }

    /** Updates a tournament's rule book (does not touch already-sold prices/history). */
    @Transactional
    public Tournament updateRules(UUID id, String name, AuctionProperties rules) {
        Tournament t = get(id);
        if (name != null && !name.isBlank()) {
            t.setName(name.trim());
        }
        if (rules != null) {
            t.setRulesJson(ruleBook.serialize(rules));
        }
        tournaments.save(t);
        ruleBook.rulesChanged(id);
        return t;
    }

    @Transactional
    public Tournament activate(UUID id) {
        Tournament t = get(id);
        deactivateAll();
        t.setActive(true);
        tournaments.save(t);
        ruleBook.activeChanged(t.getId());
        return t;
    }

    private void deactivateAll() {
        for (Tournament other : tournaments.findAll()) {
            if (other.isActive()) {
                other.setActive(false);
                tournaments.save(other);
            }
        }
    }

    /** Slugify the name; append -2, -3, … until unique. */
    private String uniqueSlug(String name) {
        String base = name.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (base.isEmpty()) {
            base = "tournament";
        }
        String slug = base;
        int n = 2;
        while (tournaments.existsBySlug(slug)) {
            slug = base + "-" + n++;
        }
        return slug;
    }
}
