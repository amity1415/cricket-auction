package com.auctiontracker;

import com.auctiontracker.config.AuctionProperties;
import com.auctiontracker.core.Player;
import com.auctiontracker.core.PlayerCategory;
import com.auctiontracker.core.PlayerRole;
import com.auctiontracker.core.Team;

import java.util.List;
import java.util.Map;

public final class TestFixtures {

    private TestFixtures() {}

    /** Mirrors the increment table and base prices in DESIGN.md section 4. */
    public static AuctionProperties props() {
        return props(Map.of());
    }

    /** Variant with per-group min/max squad rules for quota tests. */
    public static AuctionProperties props(Map<PlayerCategory, AuctionProperties.CategoryRule> categoryRules) {
        return new AuctionProperties(
                2_000_000L,
                Map.of(PlayerCategory.A, 20_000_000L,
                        PlayerCategory.B, 5_000_000L,
                        PlayerCategory.C, 2_000_000L,
                        PlayerCategory.D, 1_500_000L,
                        PlayerCategory.E, 1_000_000L),
                List.of(new AuctionProperties.IncrementRule(2_000_000L, 500_000L),
                        new AuctionProperties.IncrementRule(10_000_000L, 1_000_000L),
                        new AuctionProperties.IncrementRule(20_000_000L, 2_000_000L)),
                2_500_000L,
                categoryRules,
                new AuctionProperties.Retention(3, 2, 1, 1_200_000L, 600_000L),
                new AuctionProperties.TeamDefaults(150_000_000L, 8),
                true,
                false);
    }

    /**
     * Props at the real ₹1.5 Cr scale (base A ₹6L … E ₹50K, min-viable ₹50K),
     * for the Group-A budget ceiling and squad-completion reserve tests.
     */
    public static AuctionProperties realisticProps(Map<PlayerCategory, AuctionProperties.CategoryRule> categoryRules) {
        return new AuctionProperties(
                50_000L,
                Map.of(PlayerCategory.A, 600_000L,
                        PlayerCategory.B, 400_000L,
                        PlayerCategory.C, 200_000L,
                        PlayerCategory.D, 100_000L,
                        PlayerCategory.E, 50_000L),
                List.of(new AuctionProperties.IncrementRule(100_000L, 10_000L),
                        new AuctionProperties.IncrementRule(500_000L, 25_000L),
                        new AuctionProperties.IncrementRule(2_000_000L, 50_000L)),
                250_000L,
                categoryRules,
                new AuctionProperties.Retention(3, 2, 1, 1_200_000L, 600_000L),
                new AuctionProperties.TeamDefaults(15_000_000L, 20),
                true,
                false);
    }

    public static Team team(String name, long purse, int maxSquad,
                            Map<PlayerRole, Integer> minPerRole) {
        return Team.register(name, "Owner", purse, maxSquad, minPerRole);
    }

    public static Player player(String name, PlayerRole role, PlayerCategory category,
                                long basePrice) {
        return Player.register(name, role, category, basePrice);
    }
}
