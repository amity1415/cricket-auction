package com.auctiontracker.core;

/**
 * Formats whole-rupee amounts with Indian digit grouping, e.g. ₹1,20,00,000.
 * Grouping is done manually — JDK locale data for en-IN doesn't reliably
 * apply lakh/crore grouping across JDK versions.
 */
public final class Money {

    private Money() {}

    public static String inr(long amount) {
        String digits = Long.toString(Math.abs(amount));
        String grouped;
        if (digits.length() <= 3) {
            grouped = digits;
        } else {
            // Last 3 digits, then groups of 2 (Indian numbering system).
            StringBuilder head = new StringBuilder();
            String rest = digits.substring(0, digits.length() - 3);
            while (rest.length() > 2) {
                head.insert(0, "," + rest.substring(rest.length() - 2));
                rest = rest.substring(0, rest.length() - 2);
            }
            head.insert(0, rest);
            grouped = head + "," + digits.substring(digits.length() - 3);
        }
        return (amount < 0 ? "-₹" : "₹") + grouped;
    }
}
