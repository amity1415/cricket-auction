package com.auctiontracker.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Live auction data (dashboard, players, audit) must never be cached. Several
 * spectator laptops poll these endpoints continuously, and a browser or a
 * network hop serving even a few-seconds-stale {@code /api/dashboard} makes the
 * broadcast board flash the previous player-on-the-block (and their opening
 * bid) before the true current state loads. Static assets keep their own
 * revalidating cache policy; only {@code /api/**} responses are forced
 * no-store here so every poll reflects reality.
 */
@Component
@Order(0)
public class ApiNoCacheFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        // Player poster images are large and immutable — they set their own
        // long-lived cache policy (see PlayerQueryController#photo) and must be
        // browser-cacheable, so they're the one /api/ path we don't force no-store
        // on. (Spring writes the controller's Cache-Control via addHeader, so a
        // no-store set here would otherwise combine with it and win.)
        if (uri.startsWith("/api/") && !uri.endsWith("/photo")) {
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
            response.setHeader("Pragma", "no-cache");
            response.setHeader("Expires", "0");
        }
        chain.doFilter(request, response);
    }
}
