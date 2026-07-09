package com.auctiontracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CricketAuctionApplication {

    public static void main(String[] args) {
        SpringApplication.run(CricketAuctionApplication.class, args);
    }
}
