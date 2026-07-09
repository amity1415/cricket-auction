package com.auctiontracker;

import com.auctiontracker.bidding.IncrementRuleEngine;
import com.auctiontracker.core.Player;
import com.auctiontracker.core.PlayerCategory;
import com.auctiontracker.core.PlayerRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** DESIGN.md 8.1: correct step for every price band, including exact boundaries. */
class IncrementRuleEngineTest {

    private final IncrementRuleEngine engine = new IncrementRuleEngine(TestFixtures.props());

    @Test
    void lowestBandBelowThreshold() {
        assertEquals(500_000L, engine.incrementFor(1_500_000L));
    }

    @Test
    void boundaryIsInclusive_firstBand() {
        assertEquals(500_000L, engine.incrementFor(2_000_000L));
    }

    @Test
    void justAboveFirstBoundaryMovesToSecondBand() {
        assertEquals(1_000_000L, engine.incrementFor(2_000_001L));
    }

    @Test
    void secondBandMidRange() {
        assertEquals(1_000_000L, engine.incrementFor(5_000_000L));
    }

    @Test
    void boundaryIsInclusive_secondBand() {
        assertEquals(1_000_000L, engine.incrementFor(10_000_000L));
    }

    @Test
    void thirdBandMidRange() {
        assertEquals(2_000_000L, engine.incrementFor(15_000_000L));
    }

    @Test
    void boundaryIsInclusive_thirdBand() {
        assertEquals(2_000_000L, engine.incrementFor(20_000_000L));
    }

    @Test
    void aboveAllBandsUsesDefaultIncrement() {
        assertEquals(2_500_000L, engine.incrementFor(20_000_001L));
        assertEquals(2_500_000L, engine.incrementFor(100_000_000L));
    }

    @Test
    void firstBidOpensAtBasePriceWithNoIncrement() {
        assertEquals(5_000_000L, engine.nextBidAmount(5_000_000L, null));
    }

    @Test
    void subsequentBidAddsIncrementOfCurrentBand() {
        assertEquals(6_000_000L, engine.nextBidAmount(5_000_000L, 5_000_000L));
    }
}
