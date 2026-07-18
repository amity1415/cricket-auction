package com.auctiontracker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Boots the full context — catches config-binding and wiring mistakes. */
@SpringBootTest(properties = "auction.photos.enabled=false") // no Drive fetch in CI
class CricketAuctionApplicationTests {

    @Test
    void contextLoads() {
    }
}
