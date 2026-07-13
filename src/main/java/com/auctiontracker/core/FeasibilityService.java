package com.auctiontracker.core;

import com.auctiontracker.tournament.RuleBook;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Shared validation helper (DESIGN.md 5.6): squad feasibility, purse checks,
 * and the max-affordable-bid dashboard figure. Used hypothetically by place-bid
 * and as the final guard by confirm-sale.
 */
@Service
public class FeasibilityService {

    private final PlayerRepository players;
    private final RuleBook ruleBook;

    public FeasibilityService(PlayerRepository players, RuleBook ruleBook) {
        this.players = players;
        this.ruleBook = ruleBook;
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
            slots += Math.max(0, ruleBook.current().minPerTeamFor(category) - counts.get(category));
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
        PlayerCategory cat = player.getCategory();
        Integer maxInGroup = ruleBook.current().maxPerTeamFor(cat);
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
            Long budget = ruleBook.current().budgetFor(cat);
            if (budget != null) {
                long spent = groupSpend(squad, cat);
                int remainingAfter = Math.max(0, maxInGroup - inGroup - 1);
                long reserve = (long) remainingAfter * ruleBook.current().reservePerSlotFor(cat);
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
     * Least it can cost to finish a VALID squad after the pending purchase: the
     * team must still hit every group's minimum (those slots can only be bought
     * from that group, so they are reserved at that group's base price) and then
     * fill any leftover room with the cheapest groups that still have space. This
     * is what guarantees a bid never strands the team's mandatory group signings.
     */
    private long squadCompletionReserve(List<Player> squad, PlayerCategory incoming,
                                        int maxSquadSize, int squadSize) {
        int roomAfter = Math.max(0, maxSquadSize - (squadSize + 1));
        if (roomAfter == 0) {
            return 0L;
        }
        Map<PlayerCategory, Integer> held = new EnumMap<>(categoryCounts(squad));
        held.merge(incoming, 1, Integer::sum); // this purchase fills one slot in its group
        return completionReserve(held, roomAfter);
    }

    /**
     * Minimum base-price cost to fill {@code slotsToFill} more squad slots given
     * the groups already held. Per-group minimums are mandatory and reserved at
     * that group's base price; the rest is filled with the cheapest groups that
     * still have room (bounded by their max-per-team quota).
     */
    private long completionReserve(Map<PlayerCategory, Integer> held, int slotsToFill) {
        if (slotsToFill <= 0) {
            return 0L;
        }
        List<Long> mandatory = new ArrayList<>();  // group-minimum slots, priced at group base
        List<Long> optional = new ArrayList<>();   // spare capacity, cheapest wins
        for (PlayerCategory g : PlayerCategory.values()) {
            int have = held.getOrDefault(g, 0);
            int min = ruleBook.current().minPerTeamFor(g);
            Integer max = ruleBook.current().maxPerTeamFor(g);
            int cap = max == null ? Integer.MAX_VALUE : max;
            long base = ruleBook.current().basePriceFor(g);
            for (int i = 0; i < Math.max(0, Math.min(min, cap) - have); i++) {
                mandatory.add(base);
            }
            int spare = Math.min(Math.max(0, cap - Math.max(have, min)), slotsToFill);
            for (int i = 0; i < spare; i++) {
                optional.add(base);
            }
        }
        // Mandatory slots are non-negotiable; keep the costliest if minimums somehow
        // exceed the room, so the reserve never understates what the team still owes.
        mandatory.sort(Comparator.reverseOrder());
        long reserve = 0L;
        int remaining = slotsToFill;
        for (long price : mandatory) {
            if (remaining == 0) break;
            reserve += price;
            remaining--;
        }
        if (remaining > 0) {
            optional.sort(null); // cheapest first
            for (int i = 0; i < Math.min(remaining, optional.size()); i++) {
                reserve += optional.get(i);
            }
        }
        return reserve;
    }

    /**
     * Advisory dashboard figure: the most a team can bid on its next player and
     * still afford the players it is still OBLIGED to buy. Its unmet group
     * minimums are priced at that group's base price; role minimums (any group,
     * so the cheapest) are padded in at the minimum viable price — the two are the
     * same slots viewed two ways, so we take the larger count. This bid can cover
     * the priciest of those obligations, so only the rest is reserved. Clamped to
     * [0, remainingPurse]; 0 once the squad is full.
     */
    public long maxAffordableBid(Team team) {
        return maxAffordableBid(team, squadOf(team));
    }

    /** As above, but reusing a pre-fetched squad list (no extra query). */
    public long maxAffordableBid(Team team, List<Player> squad) {
        if (team.squadSize() >= team.getMaxSquadSize()) {
            return 0;
        }
        Map<PlayerCategory, Integer> counts = categoryCounts(squad);
        List<Long> mandatory = new ArrayList<>();
        for (PlayerCategory g : PlayerCategory.values()) {
            long base = ruleBook.current().basePriceFor(g);
            for (int i = 0; i < Math.max(0, ruleBook.current().minPerTeamFor(g) - counts.get(g)); i++) {
                mandatory.add(base);
            }
        }
        // Role minimums belong to no group and can be filled by the cheapest player,
        // so top the list up to the role deficit at the minimum viable price.
        int roleDeficit = roleDeficit(team, roleCounts(squad));
        while (mandatory.size() < roleDeficit) {
            mandatory.add(ruleBook.current().minViablePrice());
        }
        if (mandatory.isEmpty()) {
            return team.getRemainingPurse();
        }
        mandatory.sort(null);
        long thisBidCovers = mandatory.get(mandatory.size() - 1); // fills the priciest obligation
        long reserve = mandatory.stream().mapToLong(Long::longValue).sum() - thisBidCovers;
        return Math.max(0, team.getRemainingPurse() - reserve);
    }
}
