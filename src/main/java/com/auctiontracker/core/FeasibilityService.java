package com.auctiontracker.core;

import com.auctiontracker.config.AuctionProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Shared validation helper (DESIGN.md 5.6): squad feasibility, overseas quota,
 * purse checks, and the max-affordable-bid dashboard figure. Used hypothetically
 * by place-bid and as the final guard by confirm-sale.
 */
@Service
public class FeasibilityService {

    private final PlayerRepository players;
    private final AuctionProperties props;

    public FeasibilityService(PlayerRepository players, AuctionProperties props) {
        this.players = players;
        this.props = props;
    }

    // --- Squad-list overloads -------------------------------------------
    // The dashboard builds one snapshot per team and needs several of these
    // counts at once. Each Team-based method below issues its own
    // findBySoldToTeamId query, so calling five of them per team is an N+1 that
    // made /api/dashboard take seconds against a remote DB. Callers that already
    // hold the squad (e.g. DashboardService, which fetches all squads once) pass
    // the list to these overloads and pay zero extra queries.

    public Map<PlayerRole, Integer> roleCounts(List<Player> squad) {
        Map<PlayerRole, Integer> counts = new EnumMap<>(PlayerRole.class);
        for (PlayerRole role : PlayerRole.values()) {
            counts.put(role, 0);
        }
        for (Player p : squad) {
            counts.merge(p.getRole(), 1, Integer::sum);
        }
        return counts;
    }

    public Map<PlayerCategory, Integer> categoryCounts(List<Player> squad) {
        Map<PlayerCategory, Integer> counts = new EnumMap<>(PlayerCategory.class);
        for (PlayerCategory category : PlayerCategory.values()) {
            counts.put(category, 0);
        }
        for (Player p : squad) {
            counts.merge(p.getCategory(), 1, Integer::sum);
        }
        return counts;
    }

    public int overseasCount(List<Player> squad) {
        return (int) squad.stream().filter(Player::isOverseas).count();
    }

    private List<Player> squadOf(Team team) {
        return players.findBySoldToTeamId(team.getTeamId());
    }

    public Map<PlayerRole, Integer> roleCounts(Team team) {
        return roleCounts(squadOf(team));
    }

    /** How many squad members the team holds in each group. */
    public Map<PlayerCategory, Integer> categoryCounts(Team team) {
        return categoryCounts(squadOf(team));
    }

    public int overseasCount(Team team) {
        return overseasCount(squadOf(team));
    }

    private int roleDeficit(Team team, Map<PlayerRole, Integer> counts) {
        int slots = 0;
        for (Map.Entry<PlayerRole, Integer> entry : team.getMinPerRole().entrySet()) {
            slots += Math.max(0, entry.getValue() - counts.get(entry.getKey()));
        }
        return slots;
    }

    /** RULE 1: how much a team has already spent on players in a group (sale price or retention fee). */
    private long groupSpend(List<Player> squad, PlayerCategory category) {
        return squad.stream()
                .filter(p -> p.getCategory() == category)
                .mapToLong(p -> p.getSoldPrice() == null ? 0L : p.getSoldPrice())
                .sum();
    }

    private int categoryDeficit(Map<PlayerCategory, Integer> counts) {
        int slots = 0;
        for (PlayerCategory category : PlayerCategory.values()) {
            slots += Math.max(0, props.minPerTeamFor(category) - counts.get(category));
        }
        return slots;
    }

    /**
     * Mandatory slots the team still has to fill. Role minimums and per-group
     * minimums are two dimensions over the same signings — one player can fill
     * one of each simultaneously — so the reserve is the max of the two, not
     * their sum.
     */
    public int remainingMandatorySlots(Team team) {
        return remainingMandatorySlots(team, squadOf(team));
    }

    /** As above, but reusing a pre-fetched squad list (no extra query). */
    public int remainingMandatorySlots(Team team, List<Player> squad) {
        return Math.max(roleDeficit(team, roleCounts(squad)), categoryDeficit(categoryCounts(squad)));
    }

    /**
     * All acquire-time checks in DESIGN.md 5.3 order: purse, squad size, the
     * group quota + Group-A budget ceiling, then the squad-completion reserve.
     * Throws a CONFLICT with an actionable message.
     */
    public void assertCanAcquire(Team team, Player player, long price) {
        if (team.getRemainingPurse() < price) {
            throw AuctionException.conflict("INSUFFICIENT_PURSE",
                    "%s can't afford %s — %s remaining".formatted(
                            team.getName(), Money.inr(price), Money.inr(team.getRemainingPurse())),
                    Map.of("teamId", team.getTeamId(),
                            "attemptedAmount", price,
                            "remainingPurse", team.getRemainingPurse()));
        }
        if (team.squadSize() >= team.getMaxSquadSize()) {
            throw AuctionException.conflict("SQUAD_FULL",
                    "%s's squad is already full (%d/%d)".formatted(
                            team.getName(), team.squadSize(), team.getMaxSquadSize()),
                    Map.of("teamId", team.getTeamId()));
        }
        // No overseas quota by design — overseas make-up is informational only.
        PlayerCategory cat = player.getCategory();
        Integer maxInGroup = props.maxPerTeamFor(cat);
        List<Player> squad = players.findBySoldToTeamId(team.getTeamId());
        int inGroup = categoryCounts(squad).get(cat);
        if (maxInGroup != null) {
            if (inGroup >= maxInGroup) {
                throw AuctionException.conflict("CATEGORY_QUOTA_FULL",
                        "%s already has %d group-%s player(s) (max %d) — can't sign %s".formatted(
                                team.getName(), inGroup, cat, maxInGroup, player.getName()),
                        Map.of("teamId", team.getTeamId(), "category", cat, "maxPerTeam", maxInGroup));
            }
            // RULE 1: a group with a configured budget (Group A) has a HARD spend
            // ceiling. A bid may use only what is left of that budget after
            // reserving the group's remaining allowed slots at reserve price:
            //   budget − already-spent-in-group − (remaining slots after) × reserve
            Long budget = props.budgetFor(cat);
            if (budget != null) {
                long spent = groupSpend(squad, cat);
                int remainingAfter = Math.max(0, maxInGroup - inGroup - 1);
                long reserve = (long) remainingAfter * props.reservePerSlotFor(cat);
                long cap = budget - spent - reserve;
                if (price > cap) {
                    throw AuctionException.conflict("GROUP_BUDGET_EXCEEDED",
                            "%s can bid at most %s for this group-%s player — group %s has a %s budget, %s already spent, and %s must stay to fill its other %d slot(s) at base price"
                                    .formatted(team.getName(), Money.inr(Math.max(0, cap)), cat, cat,
                                            Money.inr(budget), Money.inr(spent), Money.inr(reserve), remainingAfter),
                            Map.of("teamId", team.getTeamId(), "category", cat,
                                    "maxBidForGroup", Math.max(0, cap),
                                    "groupBudget", budget, "groupSpent", spent, "groupReserve", reserve));
                }
            }
        }
        // RULE 1 (all groups): every bid must leave enough purse to still fill the
        // team's remaining squad slots at base price. This — not a per-group budget —
        // is what bounds B/C/D/E; unspent Group-A money therefore flows freely here.
        long purseAfter = team.getRemainingPurse() - price;
        long completionReserve = squadCompletionReserve(squad, cat, team.getMaxSquadSize(), team.squadSize());
        if (purseAfter < completionReserve) {
            int roomAfter = Math.max(0, team.getMaxSquadSize() - (team.squadSize() + 1));
            throw AuctionException.conflict("SQUAD_FEASIBILITY_BROKEN",
                    "Buying %s at %s would leave %s with %s, but it must keep %s to fill its remaining %d squad slot(s) at base price"
                            .formatted(player.getName(), Money.inr(price), team.getName(),
                                    Money.inr(purseAfter), Money.inr(completionReserve), roomAfter),
                    Map.of("teamId", team.getTeamId(),
                            "attemptedAmount", price,
                            "remainingPurse", team.getRemainingPurse(),
                            "requiredReserve", completionReserve));
        }
    }

    /**
     * Cheapest cost to fill the team's remaining squad slots (after the pending
     * purchase) at base price. The remaining quota can exceed the squad room by a
     * slot or two, so we reserve only for the slots the team can actually still
     * buy — the cheapest ones — guaranteeing it can always complete a full squad.
     */
    private long squadCompletionReserve(List<Player> squad, PlayerCategory incoming,
                                        int maxSquadSize, int squadSize) {
        int roomAfter = Math.max(0, maxSquadSize - (squadSize + 1));
        if (roomAfter == 0) {
            return 0L;
        }
        Map<PlayerCategory, Integer> counts = categoryCounts(squad);
        List<Long> slotPrices = new ArrayList<>();
        for (PlayerCategory g : PlayerCategory.values()) {
            Integer max = props.maxPerTeamFor(g);
            if (max == null) {
                continue;
            }
            int held = counts.get(g) + (g == incoming ? 1 : 0); // this buy fills one slot
            long base = props.basePriceFor(g);
            for (int i = 0; i < Math.max(0, max - held); i++) {
                slotPrices.add(base);
            }
        }
        slotPrices.sort(null); // ascending — reserve the cheapest way to finish the squad
        long reserve = 0L;
        for (int i = 0; i < Math.min(roomAfter, slotPrices.size()); i++) {
            reserve += slotPrices.get(i);
        }
        return reserve;
    }

    /**
     * Advisory dashboard figure (DESIGN.md 5.7):
     * remainingPurse − (remainingMandatorySlots − 1) × minimumViablePrice,
     * clamped to [0, remainingPurse]; 0 once the squad is full.
     */
    public long maxAffordableBid(Team team) {
        return maxAffordableBid(team, squadOf(team));
    }

    /** As above, but reusing a pre-fetched squad list (no extra query). */
    public long maxAffordableBid(Team team, List<Player> squad) {
        if (team.squadSize() >= team.getMaxSquadSize()) {
            return 0;
        }
        int slots = remainingMandatorySlots(team, squad);
        long reserve = (long) Math.max(0, slots - 1) * props.minViablePrice();
        return Math.max(0, team.getRemainingPurse() - reserve);
    }
}
