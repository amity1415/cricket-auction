package com.auctiontracker.core;

import org.springframework.stereotype.Component;

/**
 * Single shared monitor for all admin write operations (mark-under-auction,
 * place-bid, confirm-sale, mark-unsold). The system is single-writer by design
 * (ARCHITECTURE.md section 2); this lock only guards against accidental
 * double-submits, e.g. a double-clicked "confirm sale" button (DESIGN.md 8.2).
 * Replaced by database transactions + optimistic locking when JPA lands.
 */
@Component
public class AuctionLock {
}
