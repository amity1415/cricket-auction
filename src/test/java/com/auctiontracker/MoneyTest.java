package com.auctiontracker;

import com.auctiontracker.core.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoneyTest {

    @Test
    void indianGrouping() {
        assertEquals("₹0", Money.inr(0));
        assertEquals("₹100", Money.inr(100));
        assertEquals("₹1,000", Money.inr(1_000));
        assertEquals("₹95,000", Money.inr(95_000));
        assertEquals("₹5,00,000", Money.inr(500_000));
        assertEquals("₹20,00,000", Money.inr(2_000_000));
        assertEquals("₹1,20,00,000", Money.inr(12_000_000));
        assertEquals("₹2,20,00,000", Money.inr(22_000_000));
        assertEquals("₹15,00,00,000", Money.inr(150_000_000));
        assertEquals("-₹9,50,000", Money.inr(-950_000));
    }
}
