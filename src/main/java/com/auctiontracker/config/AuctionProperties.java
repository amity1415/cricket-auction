package com.auctiontracker.config;

import com.auctiontracker.core.PlayerCategory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Auction business configuration — every rule-based number lives here, not in
 * code: increment bands, per-group base prices, per-team group quotas, the
 * unsold-demotion rule, feasibility floor, and team-registration defaults.
 * Kept in application config, not the database — DESIGN.md section 4.
 */
@ConfigurationProperties(prefix = "auction")
public record AuctionProperties(
        long minViablePrice,
        Map<PlayerCategory, Long> basePrices,
        List<IncrementRule> incrementRules,
        long defaultIncrement,
        Map<PlayerCategory, CategoryRule> categoryRules,
        Retention retention,
        TeamDefaults teamDefaults,
        boolean demoteUnsoldPlayers,
        boolean seedDemoData) {

    /**
     * Pre-auction retention rules: total cap per team, split between the top
     * group (A) and the lower groups (B–E), plus the flat cost charged for a
     * retention (group A vs. any lower group).
     */
    public record Retention(int maxPerTeam, int maxFromGroupA, int maxFromLowerGroups,
                            long costGroupA, long costOtherGroups) {}

    /** One band of the increment table. {@code upTo} is an inclusive upper bound. */
    public record IncrementRule(long upTo, long increment) {}

    /**
     * Per-team squad composition rule for one group. {@code maxPerTeam} is a
     * hard block at bid and sale time (null = unlimited); {@code minPerTeam}
     * feeds the mandatory-slot reserve like role minimums (null = 0);
     * {@code reservePerSlot} is the amount kept back per remaining allowed slot
     * in this group (RULE 1; null = fall back to base price); {@code budget} is
     * the group's total spend cap — the five budgets sum to the team purse
     * (null = no dedicated budget, only the shared purse constrains).
     */
    public record CategoryRule(Integer maxPerTeam, Integer minPerTeam,
                               Long reservePerSlot, Long budget) {}

    /** Defaults offered when registering a team (seeder and setup-page prefill). */
    public record TeamDefaults(
            long startingPurse,
            int maxSquadSize) {}

    public long basePriceFor(PlayerCategory category) {
        Long price = basePrices.get(category);
        if (price == null) {
            throw new IllegalStateException("No base price configured for group " + category);
        }
        return price;
    }

    /** Max players a team may hold in this group; null = unlimited. */
    public Integer maxPerTeamFor(PlayerCategory category) {
        CategoryRule rule = categoryRules == null ? null : categoryRules.get(category);
        return rule == null ? null : rule.maxPerTeam();
    }

    /** Min players a team should end up with in this group; 0 if unset. */
    public int minPerTeamFor(PlayerCategory category) {
        CategoryRule rule = categoryRules == null ? null : categoryRules.get(category);
        return rule == null || rule.minPerTeam() == null ? 0 : rule.minPerTeam();
    }

    /**
     * RULE 1 reserve: amount to keep per remaining allowed slot in this group,
     * so a team can always complete its quota at this price. Falls back to the
     * group's base price when {@code reserve-per-slot} is not configured.
     */
    public long reservePerSlotFor(PlayerCategory category) {
        CategoryRule rule = categoryRules == null ? null : categoryRules.get(category);
        if (rule != null && rule.reservePerSlot() != null) {
            return rule.reservePerSlot();
        }
        return basePriceFor(category);
    }

    /** RULE 1 group budget: total a team may spend in this group; null = no cap. */
    public Long budgetFor(PlayerCategory category) {
        CategoryRule rule = categoryRules == null ? null : categoryRules.get(category);
        return rule == null ? null : rule.budget();
    }

    /** RULE 2 retention cost: flat fee for retaining a player in this group. */
    public long retentionCostFor(PlayerCategory category) {
        return category == PlayerCategory.A ? retention.costGroupA() : retention.costOtherGroups();
    }
}
