package com.auctiontracker.web;

import com.auctiontracker.bidding.BiddingService;
import com.auctiontracker.core.CoreService;
import com.auctiontracker.core.Player;
import com.auctiontracker.core.PlayerCategory;
import com.auctiontracker.core.PlayerRole;
import com.auctiontracker.core.PlayerStatus;
import com.auctiontracker.web.dto.Responses.CurrentBidView;
import com.auctiontracker.web.dto.Responses.PlayerView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Shared read endpoints for the player pool (DESIGN.md 6.2). */
@RestController
@RequestMapping("/api/players")
public class PlayerQueryController {

    private final CoreService core;
    private final BiddingService bidding;

    public PlayerQueryController(CoreService core, BiddingService bidding) {
        this.core = core;
        this.bidding = bidding;
    }

    @GetMapping
    public List<PlayerView> listPlayers(@RequestParam(required = false) PlayerStatus status,
                                        @RequestParam(required = false) PlayerRole role,
                                        @RequestParam(required = false) PlayerCategory category) {
        return core.listPlayers(status, role, category).stream().map(PlayerView::from).toList();
    }

    @GetMapping("/{id}")
    public PlayerView player(@PathVariable("id") UUID playerId) {
        return PlayerView.from(core.getPlayer(playerId));
    }

    @GetMapping("/{id}/current-bid")
    public CurrentBidView currentBid(@PathVariable("id") UUID playerId) {
        Player player = core.getPlayer(playerId);
        boolean underAuction = player.getStatus() == PlayerStatus.UNDER_AUCTION;
        Long currentAmount = bidding.currentBidAmount(playerId);
        UUID leadingTeamId = bidding.currentLeadingTeamId(playerId);
        return new CurrentBidView(
                player.getPlayerId(),
                player.getName(),
                player.getStatus(),
                player.getBasePrice(),
                currentAmount,
                leadingTeamId,
                leadingTeamId == null ? null : core.getTeam(leadingTeamId).getName(),
                underAuction ? bidding.nextBidAmount(player) : null,
                bidding.bidCount(playerId),
                Instant.now());
    }
}
