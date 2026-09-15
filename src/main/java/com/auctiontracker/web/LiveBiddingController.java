package com.auctiontracker.web;

import com.auctiontracker.auth.AuthPrincipal;
import com.auctiontracker.bidding.BiddingService;
import com.auctiontracker.config.AuctionProperties;
import com.auctiontracker.core.AuctionException;
import com.auctiontracker.core.CoreService;
import com.auctiontracker.core.Player;
import com.auctiontracker.core.Team;
import com.auctiontracker.tournament.RuleBook;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Owner-driven live bidding (the "online" auction). A franchise owner bids on the
 * player currently on the block straight from their own device — the team is taken
 * from the authenticated principal, never the request, so no one can bid for another
 * team. Bidding still goes through {@link BiddingService#placeBid}, so it lands in the
 * same in-memory {@code LiveBidSession} under the same auction lock as the auctioneer
 * console: atomic, no per-bid DB write, and first-request-wins on a tie (the second
 * bid at the same amount is rejected {@code BID_TOO_LOW}). The auctioneer console keeps
 * using {@code /api/admin/**}; this surface is additive.
 *
 * <p>Only enabled when the active rule book has {@code onlineBidding} on; offline
 * auctions reject owner bids so the auctioneer stays in sole control.
 */
@RestController
@RequestMapping("/api/live")
public class LiveBiddingController {

    private final BiddingService bidding;
    private final CoreService core;
    private final RuleBook ruleBook;

    public LiveBiddingController(BiddingService bidding, CoreService core, RuleBook ruleBook) {
        this.bidding = bidding;
        this.core = core;
        this.ruleBook = ruleBook;
    }

    /** Optional body: {@code amount} null = accept the current ask (next increment);
     *  set = a custom jump bid, which must still beat the current bid. */
    public record LiveBidRequest(Long amount) {}

    public record LiveBidResult(
            UUID playerId,
            long amount,
            UUID leadingTeamId,
            String leadingTeamName,
            int bidNumber,
            long nextBidAmount,
            boolean youLead,
            Instant biddingDeadline,
            Instant lastUpdated) {}

    /** Everything an owner device needs to render the block and decide whether to bid. */
    public record LiveStateView(
            boolean onlineBidding,
            boolean playerOnBlock,
            UUID playerId,
            String playerName,
            String role,
            String category,
            Long basePrice,
            Long currentBidAmount,
            UUID leadingTeamId,
            String leadingTeamName,
            Long nextBidAmount,
            boolean youLead,
            Long yourRemainingPurse,
            Instant biddingDeadline,
            Instant lastUpdated) {}

    @PostMapping("/bid")
    public LiveBidResult bid(@RequestBody(required = false) LiveBidRequest request,
                             Authentication authentication) {
        AuthPrincipal principal = principal(authentication);
        UUID teamId = requireOwnerTeam(principal);

        AuctionProperties rules = ruleBook.current();
        if (rules == null || !rules.onlineBiddingEnabled()) {
            throw AuctionException.conflict("BIDDING_CLOSED",
                    "This auction isn't accepting owner bids.");
        }
        UUID playerId = bidding.currentBlockPlayerId();
        if (playerId == null) {
            throw AuctionException.conflict("NO_BLOCK", "No player is on the block right now.");
        }
        // Scope guard: the owner's team must belong to the auction that is live.
        Team team = core.getTeam(teamId);
        UUID activeId = ruleBook.activeTournamentId();
        if (activeId == null || !activeId.equals(team.getTournamentId())) {
            throw AuctionException.conflict("WRONG_AUCTION",
                    "Your team isn't part of the auction that is currently live.");
        }

        Long amount = request == null ? null : request.amount();
        BiddingService.BidResult result = bidding.placeBid(playerId, teamId, amount);
        return new LiveBidResult(
                playerId,
                result.amount(),
                result.leadingTeam().getTeamId(),
                result.leadingTeam().getName(),
                result.bidNumber(),
                result.amount() + result.nextMinimumIncrement(),
                teamId.equals(result.leadingTeam().getTeamId()),
                bidding.currentBlockDeadline(playerId),
                Instant.now());
    }

    @GetMapping("/state")
    public LiveStateView state(Authentication authentication) {
        AuthPrincipal principal = principal(authentication);
        UUID teamId = principal.teamId();   // null for admins peeking; then youLead/purse stay null
        AuctionProperties rules = ruleBook.current();
        boolean online = rules != null && rules.onlineBiddingEnabled();

        UUID playerId = bidding.currentBlockPlayerId();
        if (playerId == null) {
            return new LiveStateView(online, false, null, null, null, null, null, null, null, null,
                    null, false, teamPurse(teamId), null, Instant.now());
        }
        Player player = core.getPlayer(playerId);
        // One store round-trip for the live view (leader, amount, next bid, deadline).
        BiddingService.BlockView view = bidding.blockView(player);
        UUID leadingTeamId = view.leadingTeamId();
        String leadingTeamName = leadingTeamId == null ? null : core.teamNameOrFallback(leadingTeamId);
        return new LiveStateView(
                online,
                true,
                playerId,
                player.getName(),
                player.getRole() == null ? null : player.getRole().name(),
                player.getCategory() == null ? null : player.getCategory().name(),
                player.getBasePrice(),
                view.currentAmount(),
                leadingTeamId,
                leadingTeamName,
                view.nextBidAmount(),
                teamId != null && teamId.equals(leadingTeamId),
                teamPurse(teamId),
                view.deadline(),
                Instant.now());
    }

    private Long teamPurse(UUID teamId) {
        if (teamId == null) {
            return null;
        }
        try {
            return core.getTeam(teamId).getRemainingPurse();
        } catch (AuctionException notFound) {
            return null;   // team not in the active auction — no purse to show here
        }
    }

    private AuthPrincipal principal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
            throw AuctionException.conflict("NOT_AUTHENTICATED", "Please sign in to bid.");
        }
        return principal;
    }

    private UUID requireOwnerTeam(AuthPrincipal principal) {
        UUID teamId = principal.teamId();
        if (teamId == null) {
            throw AuctionException.badRequest("NOT_AN_OWNER",
                    "Only a franchise owner account can place a live bid — use the auctioneer console.");
        }
        return teamId;
    }
}
