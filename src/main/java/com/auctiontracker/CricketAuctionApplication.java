package com.auctiontracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling   // drives the live-bid auto-close sweep (BidTimerService)
public class CricketAuctionApplication {

    public static void main(String[] args) {
        SpringApplication.run(CricketAuctionApplication.class, args);
    }
}
