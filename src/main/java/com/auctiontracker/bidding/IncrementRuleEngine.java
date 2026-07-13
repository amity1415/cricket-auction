package com.auctiontracker.bidding;

import com.auctiontracker.config.AuctionProperties;
import com.auctiontracker.tournament.RuleBook;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Bid increment lookup (DESIGN.md section 4). Bands come from the active
 * tournament's rule book; upper bounds are inclusive, prices above the last band
 * use the default step. Read per-call (not snapshotted in the constructor) so a
 * tournament switch takes effect immediately.
 */
@Component
public class IncrementRuleEngine {

    private final RuleBook ruleBook;

    public IncrementRuleEngine(RuleBook ruleBook) {
        this.ruleBook = ruleBook;
    }

    public long incrementFor(long currentPrice) {
        AuctionProperties props = ruleBook.current();
        List<AuctionProperties.IncrementRule> rules = props.incrementRules().stream()
                .sorted(Comparator.comparingLong(AuctionProperties.IncrementRule::upTo))
                .toList();
        for (AuctionProperties.IncrementRule rule : rules) {
            if (currentPrice <= rule.upTo()) {
                return rule.increment();
            }
        }
        return props.defaultIncrement();
    }

    /** DESIGN.md 5.3: first bid opens at base price; each later bid adds the current band's step. */
    public long nextBidAmount(long basePrice, Long currentAmount) {
        return currentAmount == null ? basePrice : currentAmount + incrementFor(currentAmount);
    }
}
