package com.auctiontracker.web;

import com.auctiontracker.dashboard.DashboardService;
import com.auctiontracker.dashboard.DashboardViews.OnTheBlockView;
import com.auctiontracker.sale.Sale;
import com.auctiontracker.sale.SaleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tiny read endpoint for the broadcast ticker. It returns ONLY the on-the-block
 * state (current bid / leading team / next bid) and the latest SOLD/UNSOLD result,
 * so the ticker polls a small, fast payload each second instead of the full
 * dashboard (every team's feasibility snapshot) plus the whole audit log. That
 * removes the 1–3s query latency that made the current bid and sold/unsold lag.
 *
 * Mounted under /api/dashboard/** so it inherits that path's public-read rule.
 */
@RestController
public class TickerController {

    private final DashboardService dashboard;
    private final SaleService sales;

    public TickerController(DashboardService dashboard, SaleService sales) {
        this.dashboard = dashboard;
        this.sales = sales;
    }

    @GetMapping("/api/dashboard/ticker")
    public TickerState ticker() {
        return new TickerState(dashboard.onTheBlock(), sales.latestResult().orElse(null));
    }

    /** {@code onTheBlock} is null when no player is under auction; {@code lastResult}
     * is null before the first SOLD/UNSOLD of the tournament. */
    public record TickerState(OnTheBlockView onTheBlock, Sale lastResult) {}
}
