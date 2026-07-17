package com.auctiontracker.web;

import com.auctiontracker.bidding.BiddingService;
import com.auctiontracker.core.CoreService;
import com.auctiontracker.core.Player;
import com.auctiontracker.core.Team;
import com.auctiontracker.dashboard.DashboardService;
import com.auctiontracker.photo.PlayerPhotoService;
import com.auctiontracker.sale.SaleService;
import com.auctiontracker.setup.SetupService;
import com.auctiontracker.web.dto.Requests.PlaceBidRequest;
import com.auctiontracker.web.dto.Requests.RegisterPlayerRequest;
import com.auctiontracker.web.dto.Requests.RegisterTeamRequest;
import com.auctiontracker.web.dto.Requests.UpdateTeamRequest;
import com.auctiontracker.web.dto.Responses.BidView;
import com.auctiontracker.web.dto.Responses.BulkImportResponse;
import com.auctiontracker.web.dto.Responses.ConfirmSaleResponse;
import com.auctiontracker.web.dto.Responses.CurrentBidView;
import com.auctiontracker.web.dto.Responses.PlaceBidResponse;
import com.auctiontracker.web.dto.Responses.PlayerView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin write API (DESIGN.md 6.1). Admin authentication arrives with the
 * security module (phase 5); v1 is open.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final CoreService core;
    private final BiddingService bidding;
    private final SaleService sale;
    private final DashboardService dashboard;
    private final SetupService setup;
    private final PlayerPhotoService photos;

    public AdminController(CoreService core, BiddingService bidding, SaleService sale,
                           DashboardService dashboard, SetupService setup, PlayerPhotoService photos) {
        this.core = core;
        this.bidding = bidding;
        this.sale = sale;
        this.dashboard = dashboard;
        this.setup = setup;
        this.photos = photos;
    }

    // --- Pre-auction setup -------------------------------------------------

    @PostMapping("/players")
    @ResponseStatus(HttpStatus.CREATED)
    public PlayerView registerPlayer(@Valid @RequestBody RegisterPlayerRequest request) {
        Player player = core.registerPlayer(request.name(), request.role(), request.category(),
                request.basePrice(), request.stats());
        return PlayerView.from(player);
    }

    @PostMapping(value = "/players/bulk-import", consumes = {"text/csv", "text/plain"})
    @ResponseStatus(HttpStatus.CREATED)
    public BulkImportResponse bulkImport(@RequestBody String csv) {
        List<Player> imported = core.bulkImportPlayers(csv);
        return new BulkImportResponse(imported.size(), imported.stream().map(PlayerView::from).toList());
    }

    /**
     * Setup-page import: replaces the whole player pool from a .csv or .xlsx
     * upload and resets all auction progress (bids, sales, squads, purses).
     */
    @PostMapping(value = "/players/bulk-import-replace", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public BulkImportResponse bulkImportReplace(@RequestParam("file") MultipartFile file) throws IOException {
        List<Player> imported = setup.replaceImport(file.getOriginalFilename(), file.getBytes());
        // Resolve each player's poster (by serial) from the Image_location folder,
        // off-thread — never blocks or fails the import if Drive is slow/down.
        photos.resolveFolderImages(imported);
        return new BulkImportResponse(imported.size(), imported.stream().map(PlayerView::from).toList());
    }

    @PostMapping("/teams")
    @ResponseStatus(HttpStatus.CREATED)
    public Team registerTeam(@Valid @RequestBody RegisterTeamRequest request) {
        return core.registerTeam(request.name(), request.ownerName(), request.startingPurse(),
                request.maxSquadSize(), request.minPerRole());
    }

    // --- Setup-screen CRUD -------------------------------------------------

    /** Edit a player (AVAILABLE/UNSOLD only). */
    @PutMapping("/players/{id}")
    public PlayerView updatePlayer(@PathVariable("id") UUID playerId,
                                   @Valid @RequestBody RegisterPlayerRequest request) {
        return PlayerView.from(core.updatePlayer(playerId, request.name(), request.role(),
                request.category(), request.basePrice(), request.stats()));
    }

    /** Remove a player (AVAILABLE/UNSOLD only). */
    @DeleteMapping("/players/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePlayer(@PathVariable("id") UUID playerId) {
        core.deletePlayer(playerId);
    }

    /** Edit a team; purse changes preserve the amount already spent. */
    @PutMapping("/teams/{id}")
    public Team updateTeam(@PathVariable("id") UUID teamId,
                           @Valid @RequestBody UpdateTeamRequest request) {
        return core.updateTeam(teamId, request.name(), request.ownerName(),
                request.startingPurse(), request.maxSquadSize());
    }

    /** Remove a team — only while its squad is empty. */
    @DeleteMapping("/teams/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTeam(@PathVariable("id") UUID teamId) {
        core.deleteTeam(teamId);
    }

    // --- Pre-auction retention -------------------------------------------

    /** Retain an AVAILABLE player at base price (rules in auction.retention). */
    @PostMapping("/teams/{teamId}/retain/{playerId}")
    public ConfirmSaleResponse retainPlayer(@PathVariable UUID teamId, @PathVariable UUID playerId) {
        SaleService.SaleResult result = sale.retainPlayer(teamId, playerId);
        return new ConfirmSaleResponse(
                PlayerView.from(result.player()),
                List.of(dashboard.snapshot(result.team())),
                Instant.now());
    }

    /** Undo a retention: the player returns to the pool, the purse is refunded. */
    @PostMapping("/players/{id}/release-retention")
    public ConfirmSaleResponse releasePlayer(@PathVariable("id") UUID playerId) {
        SaleService.SaleResult result = sale.releasePlayer(playerId);
        return new ConfirmSaleResponse(
                PlayerView.from(result.player()),
                List.of(dashboard.snapshot(result.team())),
                Instant.now());
    }

    // --- Live auction ------------------------------------------------------

    @PostMapping("/players/{id}/mark-under-auction")
    public PlayerView markUnderAuction(@PathVariable("id") UUID playerId) {
        return PlayerView.from(bidding.markUnderAuction(playerId));
    }

    @PostMapping("/players/{id}/place-bid")
    public PlaceBidResponse placeBid(@PathVariable("id") UUID playerId,
                                     @Valid @RequestBody PlaceBidRequest request) {
        BiddingService.BidResult result = bidding.placeBid(playerId, request.teamId(), request.amount());
        Player player = result.player();
        return new PlaceBidResponse(
                player.getPlayerId(),
                player.getName(),
                player.getStatus(),
                result.amount(),
                result.leadingTeam().getTeamId(),
                result.leadingTeam().getName(),
                result.bidNumber(),
                result.nextMinimumIncrement(),
                result.amount() + result.nextMinimumIncrement());
    }

    /** Misclick guard: pops the most recent bid from the in-memory trail (nothing hit the DB). */
    @PostMapping("/players/{id}/undo-bid")
    public CurrentBidView undoBid(@PathVariable("id") UUID playerId) {
        Player player = bidding.undoBid(playerId);
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
                bidding.nextBidAmount(player),
                bidding.bidCount(playerId),
                Instant.now());
    }

    @PostMapping("/players/{id}/confirm-sale")
    public ConfirmSaleResponse confirmSale(@PathVariable("id") UUID playerId) {
        SaleService.SaleResult result = sale.confirmSale(playerId);
        return new ConfirmSaleResponse(
                PlayerView.from(result.player()),
                List.of(dashboard.snapshot(result.team())),
                Instant.now());
    }

    @PostMapping("/players/{id}/mark-unsold")
    public PlayerView markUnsold(@PathVariable("id") UUID playerId) {
        return PlayerView.from(sale.markUnsold(playerId));
    }

    /** Undo a completed sale: the player returns to the pool, the purse is refunded. */
    @PostMapping("/players/{id}/revert-sale")
    public ConfirmSaleResponse revertSale(@PathVariable("id") UUID playerId) {
        SaleService.SaleResult result = sale.revertSale(playerId);
        return new ConfirmSaleResponse(
                PlayerView.from(result.player()),
                List.of(dashboard.snapshot(result.team())),
                Instant.now());
    }

    // --- Bid history -------------------------------------------------------

    @GetMapping("/players/{id}/bids")
    public List<BidView> bidHistory(@PathVariable("id") UUID playerId) {
        return bidding.bidHistory(playerId).stream()
                .map(event -> BidView.from(event, core.teamNameOrFallback(event.getTeamId())))
                .toList();
    }
}
