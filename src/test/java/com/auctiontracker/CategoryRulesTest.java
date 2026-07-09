package com.auctiontracker;

import com.auctiontracker.config.AuctionProperties;
import com.auctiontracker.config.AuctionProperties.CategoryRule;
import com.auctiontracker.core.AuctionException;
import com.auctiontracker.core.FeasibilityService;
import com.auctiontracker.core.InMemoryPlayerRepository;
import com.auctiontracker.core.Player;
import com.auctiontracker.core.PlayerCategory;
import com.auctiontracker.core.PlayerStatus;
import com.auctiontracker.core.Team;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.auctiontracker.core.PlayerCategory.A;
import static com.auctiontracker.core.PlayerCategory.B;
import static com.auctiontracker.core.PlayerCategory.C;
import static com.auctiontracker.core.PlayerCategory.D;
import static com.auctiontracker.core.PlayerCategory.E;
import static com.auctiontracker.core.PlayerRole.BATSMAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Per-group squad rules from application config: hard max, and min feeding the reserve. */
class CategoryRulesTest {

    private final InMemoryPlayerRepository players = new InMemoryPlayerRepository();

    private Player squadMember(Team team, PlayerCategory category) {
        return squadMember(team, category, 5_000_000L);
    }

    private Player squadMember(Team team, PlayerCategory category, long soldPrice) {
        Player p = TestFixtures.player("Owned " + category, BATSMAN, category, 5_000_000L);
        p.setStatus(PlayerStatus.SOLD);
        p.setSoldToTeamId(team.getTeamId());
        p.setSoldPrice(soldPrice);
        players.save(p);
        team.getSquadPlayerIds().add(p.getPlayerId());
        return p;
    }

    @Test
    void groupMaxBlocksAcquireWhenQuotaFull() {
        AuctionProperties props = TestFixtures.props(Map.of(B, new CategoryRule(1, 0, null, null)));
        FeasibilityService feasibility = new FeasibilityService(players, props);
        Team team = TestFixtures.team("Quota", 150_000_000L, 8, Map.of());
        squadMember(team, B); // quota of 1 already used

        Player next = TestFixtures.player("Next", BATSMAN, B, 5_000_000L);
        var ex = assertThrows(AuctionException.class,
                () -> feasibility.assertCanAcquire(team, next, 5_000_000L));

        assertEquals("CATEGORY_QUOTA_FULL", ex.getCode());
    }

    @Test
    void groupBelowMaxIsStillAllowed() {
        AuctionProperties props = TestFixtures.props(Map.of(B, new CategoryRule(2, 0, null, null)));
        FeasibilityService feasibility = new FeasibilityService(players, props);
        Team team = TestFixtures.team("Quota", 150_000_000L, 8, Map.of());
        squadMember(team, B);

        Player next = TestFixtures.player("Next", BATSMAN, B, 5_000_000L);
        feasibility.assertCanAcquire(team, next, 5_000_000L); // no exception
    }

    // Group A ceiling ₹50L, base/reserve ₹6L, max 4; B–E have quota but NO budget.
    private static final Map<PlayerCategory, CategoryRule> BUDGET_RULES = Map.of(
            A, new CategoryRule(4, 0, 600_000L, 5_000_000L),
            B, new CategoryRule(5, 0, null, null),
            C, new CategoryRule(4, 0, null, null),
            D, new CategoryRule(4, 0, null, null),
            E, new CategoryRule(4, 0, null, null));

    @Test
    void firstGroupAPlayerCappedByCeilingMinusReserve() {
        // 1st group-A player: cap = ₹50L ceiling − ₹0 spent − 3×₹6L reserve = ₹32L.
        FeasibilityService feasibility = new FeasibilityService(players, TestFixtures.realisticProps(BUDGET_RULES));
        Team team = TestFixtures.team("Budget", 15_000_000L, 20, Map.of());
        Player firstA = TestFixtures.player("StarA", BATSMAN, A, 600_000L);

        var ex = assertThrows(AuctionException.class,
                () -> feasibility.assertCanAcquire(team, firstA, 3_300_000L)); // ₹33L > ₹32L cap
        assertEquals("GROUP_BUDGET_EXCEEDED", ex.getCode());
        feasibility.assertCanAcquire(team, firstA, 3_200_000L); // exactly ₹32L → allowed
    }

    @Test
    void ceilingAccountsForAmountAlreadySpentInGroupA() {
        FeasibilityService feasibility = new FeasibilityService(players, TestFixtures.realisticProps(BUDGET_RULES));
        Team team = TestFixtures.team("Budget", 15_000_000L, 20, Map.of());
        squadMember(team, A, 2_000_000L); // one group-A already bought for ₹20L

        Player secondA = TestFixtures.player("SecondA", BATSMAN, A, 600_000L);
        // ₹50L − ₹20L spent − 2×₹6L reserve = ₹18L cap.
        var ex = assertThrows(AuctionException.class,
                () -> feasibility.assertCanAcquire(team, secondA, 1_900_000L)); // ₹19L > ₹18L
        assertEquals("GROUP_BUDGET_EXCEEDED", ex.getCode());
        feasibility.assertCanAcquire(team, secondA, 1_800_000L); // ₹18L → allowed
    }

    @Test
    void lastGroupASlotUsesWholeRemainingCeiling() {
        FeasibilityService feasibility = new FeasibilityService(players, TestFixtures.realisticProps(BUDGET_RULES));
        Team team = TestFixtures.team("Budget", 15_000_000L, 20, Map.of());
        squadMember(team, A, 600_000L);
        squadMember(team, A, 600_000L);
        squadMember(team, A, 600_000L); // 3 at base = ₹18L spent; ₹32L left, no reserve on the last

        Player lastA = TestFixtures.player("LastA", BATSMAN, A, 600_000L);
        feasibility.assertCanAcquire(team, lastA, 3_200_000L); // whole ₹32L remaining ceiling usable
        var ex = assertThrows(AuctionException.class,
                () -> feasibility.assertCanAcquire(team, lastA, 3_300_000L)); // ₹33L > ₹32L left
        assertEquals("GROUP_BUDGET_EXCEEDED", ex.getCode());
    }

    @Test
    void unspentGroupABudgetFlowsToOtherGroups() {
        // B has NO ceiling — its only limit is the squad-completion reserve. Bought
        // 4 group-A players cheaply, so the freed money is available for a big B bid.
        FeasibilityService feasibility = new FeasibilityService(players, TestFixtures.realisticProps(BUDGET_RULES));
        Team team = TestFixtures.team("Flow", 15_000_000L, 20, Map.of());
        for (int i = 0; i < 4; i++) squadMember(team, A, 600_000L); // ₹24L spent on A, quota full

        Player bigB = TestFixtures.player("BigB", BATSMAN, B, 400_000L);
        // A ₹26L saving is usable on B — a ₹50L bid is fine (well under the squad reserve).
        feasibility.assertCanAcquire(team, bigB, 5_000_000L); // no GROUP_BUDGET_EXCEEDED for B
    }

    @Test
    void squadCompletionReserveBlocksLeavingTooLittleToFinishSquad() {
        // Small purse, empty squad of max 5. After buying one group-E player, 4 slots
        // remain; the cheapest way to fill them is 3×E(₹50K) + 1×D(₹1L) = ₹2.5L reserve.
        FeasibilityService feasibility = new FeasibilityService(players, TestFixtures.realisticProps(BUDGET_RULES));
        Team team = TestFixtures.team("Tight", 2_500_000L, 5, Map.of()); // ₹25L purse
        Player e = TestFixtures.player("Efringe", BATSMAN, E, 50_000L);

        // Bid ₹23L leaves ₹2L, but the 4 remaining slots need ₹2.5L at base → blocked.
        var ex = assertThrows(AuctionException.class,
                () -> feasibility.assertCanAcquire(team, e, 2_300_000L));
        assertEquals("SQUAD_FEASIBILITY_BROKEN", ex.getCode());
        feasibility.assertCanAcquire(team, e, 2_250_000L); // leaves exactly ₹2.5L → allowed
    }

    @Test
    void groupMinimumsFeedMandatorySlotReserve() {
        // Two mandatory group-E signings still open → reserve of max(role, group) slots.
        AuctionProperties props = TestFixtures.props(Map.of(E, new CategoryRule(null, 2, null, null)));
        FeasibilityService feasibility = new FeasibilityService(players, props);
        Team team = TestFixtures.team("Reserve", 150_000_000L, 8, Map.of()); // no role minimums

        assertEquals(2, feasibility.remainingMandatorySlots(team));
        // Two group-E signings still owed; each reserved at group E's base price
        // (₹10L in fixtures). This bid covers one, so ₹10L is reserved for the other:
        // maxAffordableBid = ₹1500L − ₹10L = ₹1490L.
        assertEquals(149_000_000L, feasibility.maxAffordableBid(team));

        // One group-E player signed → one mandatory slot left.
        squadMember(team, E);
        assertEquals(1, feasibility.remainingMandatorySlots(team));
    }

    @Test
    void groupMinimumsAreReservedAtGroupBasePrice() {
        // A and B each require one signing (base ₹6L + ₹4L = ₹10L). That must stay
        // in the purse — the old cheapest-slot reserve (2×₹50K) would wrongly allow
        // a bid that leaves the team unable to buy its mandatory A and B players.
        Map<PlayerCategory, CategoryRule> rules = Map.of(
                A, new CategoryRule(4, 1, null, null),
                B, new CategoryRule(5, 1, null, null),
                C, new CategoryRule(4, 0, null, null),
                D, new CategoryRule(4, 0, null, null),
                E, new CategoryRule(4, 0, null, null));
        FeasibilityService feasibility = new FeasibilityService(players, TestFixtures.realisticProps(rules));
        Team team = TestFixtures.team("Mins", 1_100_000L, 3, Map.of()); // ₹11L purse, squad of 3

        Player c = TestFixtures.player("Cee", BATSMAN, C, 200_000L);
        feasibility.assertCanAcquire(team, c, 50_000L);   // leaves ₹10.5L ≥ ₹10L A+B reserve → OK
        var ex = assertThrows(AuctionException.class,
                () -> feasibility.assertCanAcquire(team, c, 300_000L)); // leaves ₹8L < ₹10L → blocked
        assertEquals("SQUAD_FEASIBILITY_BROKEN", ex.getCode());
    }
}
