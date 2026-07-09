package com.auctiontracker.bidding;

import com.auctiontracker.config.AuctionProperties;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Bid increment lookup (DESIGN.md section 4). Bands come from config;
 * upper bounds are inclusive, prices above the last band use the default step.
 */
@Component
public class IncrementRuleEngine {

    private final List<AuctionProperties.IncrementRule> rules;
    private final long defaultIncrement;

    public IncrementRuleEngine(AuctionProperties props) {
        this.rules = props.incrementRules().stream()
                .sorted(Comparator.comparingLong(AuctionProperties.IncrementRule::upTo))
                .toList();
        this.defaultIncrement = props.defaultIncrement();
    }

    public long incrementFor(long currentPrice) {
        for (AuctionProperties.IncrementRule rule : rules) {
            if (currentPrice <= rule.upTo()) {
                return rule.increment();
            }
        }
        return defaultIncrement;
    }

    /** DESIGN.md 5.3: first bid opens at base price; each later bid adds the current band's step. */
    public long nextBidAmount(long basePrice, Long currentAmount) {
        return currentAmount == null ? basePrice : currentAmount + incrementFor(currentAmount);
    }
}
