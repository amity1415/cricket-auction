package com.auctiontracker;

import com.auctiontracker.bidding.BiddingService;
import com.auctiontracker.bidding.InMemoryBidEventRepository;
import com.auctiontracker.bidding.IncrementRuleEngine;
import com.auctiontracker.config.AuctionProperties;
import com.auctiontracker.config.AuctionProperties.CategoryRule;
import com.auctiontracker.config.AuctionProperties.GroupTransition;
import com.auctiontracker.config.AuctionProperties.IncrementRule;
import com.auctiontracker.config.AuctionProperties.Retention;
import com.auctiontracker.config.AuctionProperties.TeamDefaults;
import com.auctiontracker.core.AuctionException;
import com.auctiontracker.core.AuctionLock;
import com.auctiontracker.core.FeasibilityService;
import com.auctiontracker.core.InMemoryPlayerRepository;
import com.auctiontracker.core.InMemoryTeamRepository;
import com.auctiontracker.core.Player;
import com.auctiontracker.core.PlayerCategory;
import com.auctiontracker.core.PlayerStatus;
import com.auctiontracker.core.Team;
import com.auctiontracker.sale.InMemorySaleRepository;
import com.auctiontracker.sale.SaleService;
import com.auctiontracker.tournament.RuleBook;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.auctiontracker.core.PlayerCategory.A;
import static com.auctiontracker.core.PlayerCategory.B;
import static com.auctiontracker.core.PlayerCategory.C;
import static com.auctiontracker.core.PlayerCategory.D;
import static com.auctiontracker.core.PlayerRole.BATSMAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KCPL 2 format: the group-budget carry-forward chain, pre-auction (retention)
 * picks kept off the pool budgets/quotas, the no-demote-A / B→C / C→D / sticky-D
 * unsold cascade, the lottery (manual) bid, and rule-book JSON round-trip.
 * See {@code docs/KCPL2-FORMAT-DESIGN.md}.
 */
class KcplFormatTest {

    private final InMemoryPlayerRepository players = new InMemoryPlayerRepository();

    /** The real KCPL Season 2 rule book (mirrors KcplSeeder), parameterised for contrast tests. */
    private static AuctionProperties kcplProps(boolean carryForward, boolean preAuctionCountsInPools) {
        Map<PlayerCategory, Long> basePrices = new LinkedHashMap<>();
        basePrices.put(A, 300_000L);
        basePrices.put(B, 100_000L);
        basePrices.put(C, 50_000L);
        basePrices.put(D, 20_000L);

        Map<PlayerCategory, CategoryRule> quotas = new LinkedHashMap<>();
        quotas.put(A, new CategoryRule(4, 4, 300_000L, 6_000_000L));
        quotas.put(B, new CategoryRule(6, 3, null, 5_000_000L));
        quotas.put(C, new CategoryRule(null, 3, null, 800_000L));
        quotas.put(D, new CategoryRule(null, 2, null, 200_000L));

        Map<PlayerCategory, GroupTransition> transitions = new LinkedHashMap<>();
        transitions.put(A, new GroupTransition(A, null));
        transitions.put(B, new GroupTransition(C, null));
        transitions.put(C, new GroupTransition(D, null));
        transitions.put(D, new GroupTransition(D, null));

        return new AuctionProperties(
                20_000L, basePrices,
                List.of(new IncrementRule(50_000L, 5_000L),
                        new IncrementRule(100_000L, 10_000L),
                        new IncrementRule(300_000L, 20_000L)),
                25_000L, quotas,
                new Retention(3, 2, 1, 1_200_000L, 600_000L),
                new TeamDefaults(15_000_000L, 20),
                false, false, transitions, null,
                List.of(A, B, C, D), carryForward, preAuctionCountsInPools);
    }

    private FeasibilityService feasibility(AuctionProperties props) {
        return new FeasibilityService(players, RuleBook.fixed(props));
    }

    /** Adds a player already owned by the team in a given status/price (SOLD or RETAINED). */
    private void owned(Team team, PlayerCategory cat, long price, PlayerStatus status) {
        Player p = TestFixtures.player("Owned " + cat + " " + status, BATSMAN, cat, 300_000L);
        p.setStatus(status);
        p.setSoldToTeamId(team.getTeamId());
        p.setSoldPrice(price);
        players.save(p);
        team.getSquadPlayerIds().add(p.getPlayerId());
    }

    // ---- Carry-forward -----------------------------------------------------

    @Test
    void unspentPoolBudgetCarriesIntoTheNextPoolCeiling() {
        FeasibilityService f = feasibility(kcplProps(true, false));
        Team team = TestFixtures.team("Carry", 15_000_000L, 20, Map.of());
        // Buy all 4 Pool A players at base ₹3L = ₹12L spent; ₹48L of the ₹60L A budget is unspent.
        for (int i = 0; i < 4; i++) {
            owned(team, A, 300_000L, PlayerStatus.SOLD);
        }
        // Effective Pool B budget = own ₹50L + carried ₹48L = ₹98L; first-B reserve = 5×₹1L = ₹5L.
        // Ceiling on the first B player = ₹98L − ₹0 − ₹5L = ₹93L.
        Player b = TestFixtures.player("BigB", BATSMAN, B, 100_000L);
        f.assertCanAcquire(team, b, 9_300_000L);                 // exactly ₹93L → allowed
        var ex = assertThrows(AuctionException.class,
                () -> f.assertCanAcquire(team, b, 9_400_000L));   // ₹94L → over the carried ceiling
        assertEquals("GROUP_BUDGET_EXCEEDED", ex.getCode());
    }

    @Test
    void withoutCarryForwardEachPoolBudgetIsStatic() {
        // Same squad, carry-forward OFF: Pool B is capped at its own ₹50L (−₹5L reserve = ₹45L),
        // so the ₹93L bid that carry-forward allowed is now rejected.
        FeasibilityService f = feasibility(kcplProps(false, false));
        Team team = TestFixtures.team("NoCarry", 15_000_000L, 20, Map.of());
        for (int i = 0; i < 4; i++) {
            owned(team, A, 300_000L, PlayerStatus.SOLD);
        }
        Player b = TestFixtures.player("BigB", BATSMAN, B, 100_000L);
        f.assertCanAcquire(team, b, 4_500_000L);                 // ₹45L static ceiling → allowed
        var ex = assertThrows(AuctionException.class,
                () -> f.assertCanAcquire(team, b, 4_600_000L));   // ₹46L → over the static ceiling
        assertEquals("GROUP_BUDGET_EXCEEDED", ex.getCode());
    }

    // ---- Pre-auction (retention) kept off the pools ------------------------

    @Test
    void retainedIconDoesNotConsumePoolABudget() {
        FeasibilityService f = feasibility(kcplProps(true, false));
        Team team = TestFixtures.team("Icons", 15_000_000L, 20, Map.of());
        owned(team, A, 1_200_000L, PlayerStatus.RETAINED); // a Grade-A Icon retained for ₹12L

        // Pool A budget must ignore the ₹12L retention: first auction A ceiling stays
        // ₹60L − ₹0 spent − 3×₹3L reserve = ₹51L (NOT ₹60L−₹12L−₹9L = ₹39L).
        Player a = TestFixtures.player("AuctionA", BATSMAN, A, 300_000L);
        f.assertCanAcquire(team, a, 5_100_000L);                 // ₹51L → allowed
        var ex = assertThrows(AuctionException.class,
                () -> f.assertCanAcquire(team, a, 5_200_000L));
        assertEquals("GROUP_BUDGET_EXCEEDED", ex.getCode());
    }

    @Test
    void retainedIconDoesNotFillPoolAQuota() {
        FeasibilityService f = feasibility(kcplProps(true, false));
        Team team = TestFixtures.team("Quota", 15_000_000L, 20, Map.of());
        owned(team, A, 1_200_000L, PlayerStatus.RETAINED);       // Icon (excluded from the 4-from-A cap)
        for (int i = 0; i < 3; i++) {
            owned(team, A, 300_000L, PlayerStatus.SOLD);         // 3 auction buys
        }
        // Retained excluded ⇒ only 3 count toward max 4, so a 4th auction A is allowed.
        Player fourthA = TestFixtures.player("FourthA", BATSMAN, A, 300_000L);
        f.assertCanAcquire(team, fourthA, 300_000L);             // no CATEGORY_QUOTA_FULL
    }

    @Test
    void withPreAuctionCountingRetainedFillsQuotaAndBudget() {
        // The legacy default: retained players DO count in pool quotas and spend.
        FeasibilityService f = feasibility(kcplProps(true, true));
        Team team = TestFixtures.team("Legacy", 15_000_000L, 20, Map.of());
        owned(team, A, 300_000L, PlayerStatus.RETAINED);
        for (int i = 0; i < 3; i++) {
            owned(team, A, 300_000L, PlayerStatus.SOLD);
        }
        // 1 retained + 3 sold = 4 → Pool A max reached, a further A is blocked.
        Player fifthA = TestFixtures.player("FifthA", BATSMAN, A, 300_000L);
        var ex = assertThrows(AuctionException.class,
                () -> f.assertCanAcquire(team, fifthA, 300_000L));
        assertEquals("CATEGORY_QUOTA_FULL", ex.getCode());
    }

    // ---- Unsold cascade (demotion) + lottery bid ---------------------------

    private SaleContext saleContext() {
        AuctionProperties props = kcplProps(true, false);
        InMemoryTeamRepository teams = new InMemoryTeamRepository();
        InMemorySaleRepository sales = new InMemorySaleRepository();
        FeasibilityService f = new FeasibilityService(players, RuleBook.fixed(props));
        AuctionLock lock = new AuctionLock();
        BiddingService bidding = new BiddingService(players, teams, new InMemoryBidEventRepository(),
                new IncrementRuleEngine(RuleBook.fixed(props)), f, lock, RuleBook.fixed(props));
        SaleService sale = new SaleService(players, teams, sales, f, lock, RuleBook.fixed(props), bidding);
        return new SaleContext(teams, bidding, sale);
    }

    private record SaleContext(InMemoryTeamRepository teams, BiddingService bidding, SaleService sale) {}

    private Player unsoldUnderAuction(SaleContext ctx, PlayerCategory cat, long base) {
        Player p = players.save(TestFixtures.player("Cascade " + cat, BATSMAN, cat, base));
        ctx.bidding().markUnderAuction(p.getPlayerId());
        ctx.sale().markUnsold(p.getPlayerId());
        return p;
    }

    @Test
    void groupAIsNeverDemotedAndReturnsAvailable() {
        SaleContext ctx = saleContext();
        Player p = unsoldUnderAuction(ctx, A, 300_000L);
        assertEquals(PlayerStatus.AVAILABLE, p.getStatus());
        assertEquals(A, p.getCategory());          // no demotion out of A
        assertEquals(300_000L, p.getBasePrice());
    }

    @Test
    void groupBDemotesToCAndCToD() {
        SaleContext ctx = saleContext();
        Player b = unsoldUnderAuction(ctx, B, 100_000L);
        assertEquals(PlayerStatus.AVAILABLE, b.getStatus());
        assertEquals(C, b.getCategory());
        assertEquals(50_000L, b.getBasePrice());   // re-priced to Pool C base

        Player c = unsoldUnderAuction(ctx, C, 50_000L);
        assertEquals(D, c.getCategory());
        assertEquals(20_000L, c.getBasePrice());   // re-priced to Pool D base
    }

    @Test
    void groupDIsAStickyFloorNeverPastD() {
        SaleContext ctx = saleContext();
        Player d = unsoldUnderAuction(ctx, D, 20_000L);
        assertEquals(PlayerStatus.AVAILABLE, d.getStatus());
        assertEquals(D, d.getCategory());          // never demoted past D
        // And it can go back on the block for another attempt.
        ctx.bidding().markUnderAuction(d.getPlayerId());
        assertEquals(PlayerStatus.UNDER_AUCTION, d.getStatus());
    }

    @Test
    void unsoldDPlayerCanBeAwardedByLotteryManualBid() {
        SaleContext ctx = saleContext();
        Team team = ctx.teams().save(TestFixtures.team("Lottery", 15_000_000L, 20, Map.of()));
        Player d = unsoldUnderAuction(ctx, D, 20_000L);          // back in the pool at ₹20K
        ctx.bidding().markUnderAuction(d.getPlayerId());
        // Auctioneer types the agreed lottery amount (the base) for the winning team.
        ctx.bidding().placeBid(d.getPlayerId(), team.getTeamId(), 20_000L);
        var result = ctx.sale().confirmSale(d.getPlayerId());
        assertEquals(PlayerStatus.SOLD, d.getStatus());
        assertEquals(20_000L, d.getSoldPrice());
        assertEquals(team.getTeamId(), result.team().getTeamId());
    }

    // ---- Rule-book JSON round-trip -----------------------------------------

    @Test
    void ruleBookRoundTripsTheCarryForwardFields() {
        RuleBook rb = new RuleBook(null, kcplProps(true, false), new ObjectMapper());
        AuctionProperties parsed = rb.parse(rb.serialize(kcplProps(true, false)));
        assertEquals(List.of(A, B, C, D), parsed.groupSequence());
        assertTrue(parsed.carryForwardEnabled());
        assertEquals(Boolean.FALSE, parsed.preAuctionCountsInPools()); // raw accessor keeps the false
        assertTrue(!parsed.retentionsCountInPools());                  // helper resolves it to "exclude"
        assertEquals(6_000_000L, parsed.budgetFor(A));
        assertEquals(A, parsed.unsoldTransitionFor(A).destination());   // self = no demotion
        assertEquals(C, parsed.unsoldTransitionFor(B).destination());
    }
}
