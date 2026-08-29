package com.auctiontracker.config;

import com.auctiontracker.core.AuctionException;
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
        boolean seedDemoData,
        // ---- Optional add-ons; null/absent = legacy behaviour (STANDARD_POOL) ----
        // Where an unsold player in each group moves next, and at what base price.
        // When a group has an entry here it OVERRIDES the ordinal A→E demotion, so
        // any format can express its own cascade graph (e.g. the role-based one).
        Map<PlayerCategory, GroupTransition> unsoldTransitions,
        // Retention fee as a multiple of the player's own base price (e.g. 3 = 3×).
        // When set it overrides the flat per-group retention cost below.
        Integer retentionBasePriceMultiplier,
        // ---- KCPL carry-forward add-ons; all null/absent = legacy behaviour ----
        // The order groups are auctioned in (e.g. [A, B, C, D]). With carry-forward
        // on, a group's unspent budget rolls into the NEXT group in this list. Null
        // or empty ⇒ derive the order from the configured groups' insertion order
        // (see {@link #effectiveGroupSequence()}).
        List<PlayerCategory> groupSequence,
        // When true, each group's spendable budget is CUMULATIVE — its own budget
        // plus everything the earlier groups in {@code groupSequence} left unspent
        // (KCPL: Pool A → B → C → D). Null/false ⇒ each group's budget is an
        // independent static ceiling, exactly as today.
        Boolean budgetCarryForward,
        // When false, RETAINED (pre-auction Icon/Owner) players are excluded from
        // BOTH pool budget-spend and group min/max quota counting, so a team's
        // pre-auction picks don't consume its auction pool budgets or slots (KCPL:
        // ₹150L purse = ₹30L retentions + ₹120L pools). Null ⇒ true (legacy: they
        // count everywhere).
        Boolean preAuctionCountsInPools) {

    /**
     * Where an unsold player moves next and the base price they carry into that
     * destination group. {@code destinationBasePrice == null} (the normal case)
     * means "use the destination group's own base price" — so a transferred
     * player always restarts at the base price of the pool they land in, and it
     * auto-adjusts if that pool's base price changes. Set a value only to force a
     * specific override. Used by formats that configure {@code unsoldTransitions};
     * the A–E format leaves this unset and keeps its ordinal demotion.
     */
    public record GroupTransition(PlayerCategory destination, Long destinationBasePrice) {}

    /**
     * Pre-auction retention rules: total cap per team, split between the top
     * group (A) and the lower groups (B–E), plus the flat cost charged for a
     * retention (group A vs. any lower group).
     *
     * <p>{@code maxPerGroup} is an optional per-category cap that supersedes the
     * A/lower split when present (nullable, opt-in — null keeps the legacy A-vs-lower
     * behaviour byte-identical). KCPL uses it to allow exactly 2 Icons + 1 Owner:
     * {@code {ICON:2, OWNER:1}}. When it is set, a category absent from the map is
     * not retainable (cap 0), and the {@code maxPerTeam} total still applies.
     */
    public record Retention(int maxPerTeam, int maxFromGroupA, int maxFromLowerGroups,
                            long costGroupA, long costOtherGroups,
                            Map<PlayerCategory, Integer> maxPerGroup) {

        /** Per-category retention cap when configured, else null (use the A/lower split). */
        public Integer maxForGroup(PlayerCategory category) {
            return maxPerGroup == null ? null : maxPerGroup.getOrDefault(category, 0);
        }

        /** True when this rule book uses per-category retention caps (KCPL). */
        public boolean hasPerGroupCaps() {
            return maxPerGroup != null && !maxPerGroup.isEmpty();
        }
    }

    /** One band of the increment table. {@code upTo} is an inclusive upper bound. */
    public record IncrementRule(long upTo, long increment) {}

    /** A copy of this rule book with only the increment bands replaced (all other
     *  fields, including any organizer edits, preserved). Used by targeted rule-book
     *  migrations that must not clobber the rest of the config. */
    public AuctionProperties withIncrementRules(List<IncrementRule> newIncrementRules) {
        return new AuctionProperties(minViablePrice, basePrices, newIncrementRules, defaultIncrement,
                categoryRules, retention, teamDefaults, demoteUnsoldPlayers, seedDemoData,
                unsoldTransitions, retentionBasePriceMultiplier, groupSequence,
                budgetCarryForward, preAuctionCountsInPools);
    }

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

    /**
     * The groups this rule book actually uses — exactly those with a configured
     * base price. Loops must iterate these, NOT {@code PlayerCategory.values()},
     * so that unrelated groups from the other auction format (which this
     * tournament doesn't configure) are ignored instead of throwing on a missing
     * base price or skewing feasibility maths.
     */
    public java.util.Set<PlayerCategory> configuredGroups() {
        return basePrices == null ? java.util.Set.of() : basePrices.keySet();
    }

    /**
     * The order groups are auctioned in for carry-forward. Uses {@code groupSequence}
     * when set, otherwise the configured groups' own insertion order — so a rule book
     * that never sets a sequence still yields a deterministic chain. Only groups that
     * are actually configured are kept, so a stale sequence entry can't break maths.
     */
    public List<PlayerCategory> effectiveGroupSequence() {
        java.util.Set<PlayerCategory> configured = configuredGroups();
        if (groupSequence != null && !groupSequence.isEmpty()) {
            List<PlayerCategory> seq = new java.util.ArrayList<>();
            for (PlayerCategory g : groupSequence) {
                if (configured.contains(g) && !seq.contains(g)) {
                    seq.add(g);
                }
            }
            // Append any configured group the sequence forgot, so nothing is dropped.
            for (PlayerCategory g : configured) {
                if (!seq.contains(g)) {
                    seq.add(g);
                }
            }
            return seq;
        }
        return new java.util.ArrayList<>(configured);
    }

    /** True when a group's budget carries its unspent remainder into the next group. */
    public boolean carryForwardEnabled() {
        return Boolean.TRUE.equals(budgetCarryForward);
    }

    /**
     * Whether pre-auction (RETAINED) players count toward pool budgets and group
     * quotas. Defaults to true (legacy) when unset. KCPL sets it false so its
     * ₹30L of Icon/Owner retentions don't eat into the ₹120L of pool budgets.
     * (Named distinctly from the {@code preAuctionCountsInPools} record accessor,
     * which returns the raw nullable Boolean, so both can coexist.)
     */
    public boolean retentionsCountInPools() {
        return preAuctionCountsInPools == null || Boolean.TRUE.equals(preAuctionCountsInPools);
    }

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

    /**
     * What retaining this player actually costs. When a base-price multiplier is
     * configured the fee is {@code playerBasePrice × multiplier} (e.g. 3×); with
     * no multiplier it falls back to the legacy flat per-group cost. Callers pass
     * the player's own base price so per-player overrides are respected.
     */
    public long retentionCost(PlayerCategory category, long playerBasePrice) {
        if (retentionBasePriceMultiplier != null) {
            return playerBasePrice * (long) retentionBasePriceMultiplier;
        }
        return retentionCostFor(category);
    }

    /**
     * The configured move for an unsold player in {@code group}, or null when this
     * rule book defines no transition for it (then the caller uses the legacy
     * demotion / marks the player finally unsold). Terminal groups have no entry.
     */
    public GroupTransition unsoldTransitionFor(PlayerCategory group) {
        return unsoldTransitions == null ? null : unsoldTransitions.get(group);
    }

    /**
     * Checks that a squad of {@code squadSize} players can actually be filled
     * under these group rules: the group minimums must sum to no more than the
     * squad size, the group maximums must sum to at least it (an unlimited group
     * lifts the upper bound), and no group's minimum may exceed its maximum.
     * Throws a BAD_REQUEST that says why it doesn't fit. Enforced both when the
     * rule book is saved and when a team is given a custom squad size.
     */
    public void assertSquadFits(int squadSize) {
        if (squadSize <= 0) {
            return;
        }
        int minSum = 0;
        long maxSum = 0;
        boolean anyUnlimited = false;
        for (PlayerCategory g : configuredGroups()) {
            int min = minPerTeamFor(g);
            Integer max = maxPerTeamFor(g);
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
        if (minSum > squadSize) {
            throw AuctionException.badRequest("RULES_INFEASIBLE",
                    ("These rules don't fit: the group minimums add up to %d players, but a squad holds only %d. "
                            + "Lower the group minimums or raise the squad size.")
                            .formatted(minSum, squadSize));
        }
        if (!anyUnlimited && maxSum < squadSize) {
            throw AuctionException.badRequest("RULES_INFEASIBLE",
                    ("These rules don't fit: the group maximums add up to only %d players, but a squad must hold %d. "
                            + "Raise a group maximum (or leave one unlimited) or lower the squad size.")
                            .formatted(maxSum, squadSize));
        }
    }
}
