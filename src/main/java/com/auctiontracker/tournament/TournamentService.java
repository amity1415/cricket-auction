package com.auctiontracker.tournament;

import com.auctiontracker.auth.SecurityProperties;
import com.auctiontracker.auth.UserAccountRepository;
import com.auctiontracker.bidding.BidEventJpaRepository;
import com.auctiontracker.bidding.BiddingService;
import com.auctiontracker.config.AuctionProperties;
import com.auctiontracker.core.AuctionException;
import com.auctiontracker.core.PlayerJpaRepository;
import com.auctiontracker.core.PlayerRowParser;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TournamentService.class);

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
    /** Create a tournament with no photo folder (used by seeders). */
    public Tournament create(String name, AuctionProperties rules) {
        return create(name, rules, null);
    }

    @Transactional
    public Tournament create(String name, AuctionProperties rules, String photosFolderLink) {
        if (name == null || name.isBlank()) {
            throw AuctionException.badRequest("INVALID_TOURNAMENT", "Tournament name must not be blank");
        }
        if (rules == null) {
            throw AuctionException.badRequest("INVALID_TOURNAMENT", "Tournament rules are required");
        }
        validateSquadFeasibility(rules);
        Tournament t = Tournament.create(name.trim(), uniqueSlug(name), ruleBook.serialize(rules));
        t.setPhotosFolderId(PlayerRowParser.toPhotoFolderId(photosFolderLink));
        if (tournaments.count() == 0) {
            t.setActive(true); // first tournament is the default for id-less requests
        }
        tournaments.save(t);
        ruleBook.rulesChanged(t.getId());
        return t;
    }

    /** Updates a tournament's rule book (does not touch already-sold prices/history). */
    @Transactional
    public Tournament updateRules(UUID id, String name, AuctionProperties rules, String photosFolderLink) {
        Tournament t = get(id);
        if (name != null && !name.isBlank()) {
            t.setName(name.trim());
        }
        if (rules != null) {
            validateSquadFeasibility(rules);
            t.setRulesJson(ruleBook.serialize(rules));
        }
        t.setPhotosFolderId(PlayerRowParser.toPhotoFolderId(photosFolderLink));
        tournaments.save(t);
        ruleBook.rulesChanged(id);
        return t;
    }

    /**
     * Rejects a rule book whose group min/max quotas can't fill the default squad
     * size (see {@link AuctionProperties#assertSquadFits}). The same check runs
     * per team when a team is given a custom squad size (see CoreService).
     */
    private void validateSquadFeasibility(AuctionProperties rules) {
        rules.assertSquadFits(rules.teamDefaults() == null ? 0 : rules.teamDefaults().maxSquadSize());
        validateCarryForward(rules);
    }

    /**
     * When carry-forward is on, every budgeted group must appear in the auction
     * sequence (otherwise its unspent budget has nowhere to roll to) — a hard
     * failure. As a soft guard, warn if the pool budgets together exceed the purse
     * left after the maximum possible retention spend; organizers may deliberately
     * leave slack, so this only logs (see design OD-4).
     */
    private void validateCarryForward(AuctionProperties rules) {
        if (rules == null || !rules.carryForwardEnabled()) {
            return;
        }
        java.util.List<com.auctiontracker.core.PlayerCategory> seq = rules.effectiveGroupSequence();
        long totalBudget = 0;
        for (com.auctiontracker.core.PlayerCategory g : rules.configuredGroups()) {
            Long budget = rules.budgetFor(g);
            if (budget == null) {
                continue;
            }
            totalBudget += budget;
            if (!seq.contains(g)) {
                throw AuctionException.badRequest("RULES_INFEASIBLE",
                        ("Carry-forward is on but group %s has a budget yet isn't in the auction sequence — "
                                + "add it to the group order so its unspent budget can carry forward.")
                                .formatted(g));
            }
        }
        long purse = rules.teamDefaults() == null ? 0 : rules.teamDefaults().startingPurse();
        if (totalBudget > purse && purse > 0) {
            log.warn("Carry-forward rule book: pool budgets sum to {} which exceeds the team purse of {} — "
                    + "teams may be unable to use the full budget.", totalBudget, purse);
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
