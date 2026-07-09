package com.auctiontracker.web;

import com.auctiontracker.config.AuctionProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of the auction rule book (application.yml `auction.*`) so the
 * UI can prefill forms and display group quotas without hardcoding them.
 */
@RestController
public class ConfigController {

    private final AuctionProperties props;

    public ConfigController(AuctionProperties props) {
        this.props = props;
    }

    @GetMapping("/api/config")
    public AuctionProperties config() {
        return props;
    }
}
