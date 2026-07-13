package com.auctiontracker.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;

/**
 * Authorizes the tournament-scoped admin endpoints: allowed for the app admin
 * (runs everything) or a tournament admin whose grants include the auction the
 * request targets.
 *
 * The auction id is taken from the URL path for {@code /api/admin/tournaments/{id}}
 * (so a tournament admin can only edit/read the rules of an auction they're
 * granted — the path can't be spoofed), and otherwise from the request's
 * {@code X-Tournament-Id} header / {@code tournamentId} param (players/teams
 * endpoints operate on the current-context auction).
 */
@Component
public class TournamentAdminAuthorization implements AuthorizationManager<RequestAuthorizationContext> {

    private static final Pattern TOURNAMENT_PATH =
            Pattern.compile("/api/admin/tournaments/([0-9a-fA-F-]{36})");

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication,
                                       RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof AuthPrincipal principal)) {
            return new AuthorizationDecision(false);
        }
        UUID tournamentId = tournamentId(context.getRequest());
        return new AuthorizationDecision(principal.canAdminTournament(tournamentId));
    }

    private UUID tournamentId(HttpServletRequest request) {
        Matcher m = TOURNAMENT_PATH.matcher(request.getRequestURI());
        if (m.find()) {
            UUID fromPath = parse(m.group(1));
            if (fromPath != null) {
                return fromPath;
            }
        }
        String raw = request.getHeader("X-Tournament-Id");
        if (raw == null || raw.isBlank()) {
            raw = request.getParameter("tournamentId");
        }
        return parse(raw);
    }

    private UUID parse(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
