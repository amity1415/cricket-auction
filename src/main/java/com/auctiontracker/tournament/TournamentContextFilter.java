package com.auctiontracker.tournament;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Binds each request to the tournament named by the {@code X-Tournament-Id}
 * header (or {@code tournamentId} query param), so scoped reads/writes land in
 * that auction. Cleared after the request so the thread carries nothing over.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TournamentContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            String raw = request.getHeader("X-Tournament-Id");
            if (raw == null || raw.isBlank()) {
                raw = request.getParameter("tournamentId");
            }
            if (raw != null && !raw.isBlank()) {
                try {
                    TournamentContext.set(UUID.fromString(raw.trim()));
                } catch (IllegalArgumentException ignored) {
                    // Malformed id → ignore and fall back to the default tournament.
                }
            }
            chain.doFilter(request, response);
        } finally {
            TournamentContext.clear();
        }
    }
}
