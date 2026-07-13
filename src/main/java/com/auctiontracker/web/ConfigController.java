package com.auctiontracker.web;

import com.auctiontracker.config.AuctionProperties;
import com.auctiontracker.tournament.RuleBook;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of the active tournament's rule book so the UI can prefill
 * forms and display group quotas without hardcoding them.
 */
@RestController
public class ConfigController {

    private final RuleBook ruleBook;

    public ConfigController(RuleBook ruleBook) {
        this.ruleBook = ruleBook;
    }

    @GetMapping("/api/config")
    public AuctionProperties config() {
        return ruleBook.current();
    }
}
