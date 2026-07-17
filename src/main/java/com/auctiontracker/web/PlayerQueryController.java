package com.auctiontracker.web;

import com.auctiontracker.bidding.BiddingService;
import com.auctiontracker.core.CoreService;
import com.auctiontracker.core.Player;
import com.auctiontracker.core.PlayerCategory;
import com.auctiontracker.core.PlayerRole;
import com.auctiontracker.core.PlayerStatus;
import com.auctiontracker.photo.PlayerPhotoService;
import com.auctiontracker.web.dto.Responses.CurrentBidView;
import com.auctiontracker.web.dto.Responses.PlayerView;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Shared read endpoints for the player pool (DESIGN.md 6.2). */
@RestController
@RequestMapping("/api/players")
public class PlayerQueryController {

    private final CoreService core;
    private final BiddingService bidding;
    private final PlayerPhotoService photos;

    public PlayerQueryController(CoreService core, BiddingService bidding, PlayerPhotoService photos) {
        this.core = core;
        this.bidding = bidding;
        this.photos = photos;
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

    /**
     * The player's poster image (JPEG) from the Drive-backed cache, or 404 when
     * none is mapped. Explicitly cacheable: these are large and immutable, so we
     * let browsers keep them despite the blanket no-store on {@code /api/**} — the
     * later header write here wins over {@link com.auctiontracker.config.ApiNoCacheFilter}.
     */
    @GetMapping("/{id}/photo")
    public ResponseEntity<byte[]> photo(@PathVariable("id") UUID playerId) {
        byte[] image = photos.photo(playerId);
        if (image == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .body(image);
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
