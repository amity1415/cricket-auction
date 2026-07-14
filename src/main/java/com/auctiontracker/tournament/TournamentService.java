package com.auctiontracker.tournament;

import com.auctiontracker.auth.SecurityProperties;
import com.auctiontracker.auth.UserAccountRepository;
import com.auctiontracker.bidding.BidEventJpaRepository;
import com.auctiontracker.bidding.BiddingService;
import com.auctiontracker.config.AuctionProperties;
import com.auctiontracker.core.AuctionException;
import com.auctiontracker.core.PlayerCategory;
import com.auctiontracker.core.PlayerJpaRepository;
import com.auctiontracker.core.Team;
import com.auctiontracker.core.TeamJpaRepository;
import com.auctiontracker.sale.SaleJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Create / list / delete tournaments. Tournaments COEXIST — each is a separate
 * auction identified by its id, and every screen operates on the one its request
 * names (see {@link TournamentContext}). The {@code active} flag marks only the
 * default tournament used when a request names none (e.g. a bare public view).
 */
@Service
public class TournamentService {

    private final TournamentRepository tournaments;
    private final RuleBook ruleBook;
    private final PlayerJpaRepository players;
    private final TeamJpaRepository teams;
    private final SaleJpaRepository sales;
    private final BidEventJpaRepository bidEvents;
    private final UserAccountRepository owners;
    private final BiddingService bidding;
    private final SecurityProperties security;

    public TournamentService(TournamentRepository tournaments, RuleBook ruleBook,
                             PlayerJpaRepository players, TeamJpaRepository teams,
                             SaleJpaRepository sales, BidEventJpaRepository bidEvents,
                             UserAccountRepository owners, BiddingService bidding,
                             SecurityProperties security) {
        this.tournaments = tournaments;
        this.ruleBook = ruleBook;
        this.players = players;
        this.teams = teams;
        this.sales = sales;
        this.bidEvents = bidEvents;
        this.owners = owners;
        this.bidding = bidding;
        this.security = security;
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

    /** The default tournament (used when a request names none), if any. */
    public Tournament active() {
        return tournaments.findFirstByActiveTrue().orElse(null);
    }

    /**
     * Creates a new tournament with its own rule book. It COEXISTS with the
     * others (does not disturb the current default) and starts empty — the admin
     * opens it by id and drives the same setup → auction flow. The very first
     * tournament ever created becomes the default.
     */
    @Transactional
    public Tournament create(String name, AuctionProperties rules) {
        if (name == null || name.isBlank()) {
            throw AuctionException.badRequest("INVALID_TOURNAMENT", "Tournament name must not be blank");
        }
        if (rules == null) {
            throw AuctionException.badRequest("INVALID_TOURNAMENT", "Tournament rules are required");
        }
        validateSquadFeasibility(rules);
        Tournament t = Tournament.create(name.trim(), uniqueSlug(name), ruleBook.serialize(rules));
        if (tournaments.count() == 0) {
            t.setActive(true); // first tournament is the default for id-less requests
        }
        tournaments.save(t);
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
            validateSquadFeasibility(rules);
            t.setRulesJson(ruleBook.serialize(rules));
        }
        tournaments.save(t);
        ruleBook.rulesChanged(id);
        return t;
    }

    /**
     * A squad must hold exactly {@code maxSquadSize} players, drawn from the
     * groups within each group's [min, max] per-team bounds. That is only possible
     * when the group minimums sum to no more than the squad size and the group
     * maximums sum to at least it (an unlimited group makes the upper bound moot).
     * Rejects an infeasible rule book with a message that says why it doesn't fit.
     */
    private void validateSquadFeasibility(AuctionProperties rules) {
        int squad = rules.teamDefaults() == null ? 0 : rules.teamDefaults().maxSquadSize();
        if (squad <= 0) {
            return;
        }
        int minSum = 0;
        long maxSum = 0;
        boolean anyUnlimited = false;
        for (PlayerCategory g : PlayerCategory.values()) {
            int min = rules.minPerTeamFor(g);
            Integer max = rules.maxPerTeamFor(g);
            if (max != null && min > max) {
                throw AuctionException.badRequest("RULES_INFEASIBLE",
                        "Group %s can't fit: its minimum (%d) is greater than its maximum (%d)."
                                .formatted(g, min, max));
            }
            minSum += min;
            if (max == null) {
                anyUnlimited = true;
            } else {
                maxSum += max;
            }
        }
        if (minSum > squad) {
            throw AuctionException.badRequest("RULES_INFEASIBLE",
                    ("These rules don't fit: the group minimums add up to %d players, but a squad holds only %d. "
                            + "Lower the group minimums or raise the max squad size.")
                            .formatted(minSum, squad));
        }
        if (!anyUnlimited && maxSum < squad) {
            throw AuctionException.badRequest("RULES_INFEASIBLE",
                    ("These rules don't fit: the group maximums add up to only %d players, but a squad must hold %d. "
                            + "Raise a group maximum (or leave one unlimited) or lower the max squad size.")
                            .formatted(maxSum, squad));
        }
    }

    /** Makes this tournament the default used when a request names none. */
    @Transactional
    public Tournament setDefault(UUID id) {
        Tournament t = get(id);
        for (Tournament other : tournaments.findAll()) {
            if (other.isActive() && !other.getId().equals(id)) {
                other.setActive(false);
                tournaments.save(other);
            }
        }
        t.setActive(true);
        tournaments.save(t);
        ruleBook.activeChanged(t.getId());
        return t;
    }

    /**
     * Permanently deletes a tournament and ALL its data (players, teams, sales,
     * bids, owner accounts). Guarded by the admin password (re-entered) so a
     * stray click can't wipe an auction. If the default is removed, the oldest
     * remaining tournament becomes the new default.
     */
    @Transactional
    public void delete(UUID id, String password) {
        Tournament t = get(id);
        if (password == null || !security.adminPassword().equals(password)) {
            throw AuctionException.forbidden("BAD_PASSWORD",
                    "Password does not match — auction not deleted");
        }

        // Wipe scoped data. Teams are deleted as entities so their element
        // collections (squad, role minimums) cascade; the rest bulk-delete.
        players.deleteByTournamentId(id);
        sales.deleteByTournamentId(id);
        bidEvents.deleteByTournamentId(id);
        List<Team> tournamentTeams = teams.findByTournamentId(id);
        if (!tournamentTeams.isEmpty()) {
            teams.deleteAll(tournamentTeams);
        }
        owners.deleteByTournamentId(id);

        boolean wasDefault = t.isActive();
        tournaments.delete(t);
        bidding.forgetTournament(id);
        ruleBook.rulesChanged(id);

        if (wasDefault) {
            Tournament next = tournaments.findAllByOrderByCreatedAtAsc().stream().findFirst().orElse(null);
            if (next != null) {
                next.setActive(true);
                tournaments.save(next);
                ruleBook.activeChanged(next.getId());
            } else {
                ruleBook.activeChanged(null);
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
