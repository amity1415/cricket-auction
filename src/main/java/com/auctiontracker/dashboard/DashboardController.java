package com.auctiontracker.dashboard;

import com.auctiontracker.dashboard.DashboardViews.DashboardView;
import com.auctiontracker.dashboard.DashboardViews.TeamDetailView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Shared read endpoints (DESIGN.md 6.2). Auth-scoping of the per-team view
 * arrives with the security module (phase 5); v1 is open read access.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping
    public DashboardView fullDashboard() {
        return dashboard.fullDashboard();
    }

    @GetMapping("/teams/{teamId}")
    public TeamDetailView teamDashboard(@PathVariable UUID teamId) {
        return dashboard.teamDetail(teamId);
    }
}
