package com.auctiontracker.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.IOException;

/**
 * Phase-5 security. Session-based auth with two roles:
 *  - ADMIN (config account): setup, live auction console, all write APIs, user admin.
 *  - FRANCHISE_OWNER (self-registered, DB): read-only dashboards/team views.
 *
 * The public broadcast screen and the read APIs it needs stay open to everyone.
 *
 * CSRF is disabled: this is an internal single-writer tool and the browser JS
 * posts with fetch; we lean on a SameSite=Lax session cookie (see application.yml)
 * to blunt cross-site requests instead of threading CSRF tokens through every call.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // Static assets (JS/CSS/icons/fonts) carry no secrets — the data
                // behind them is guarded at the API layer, so serve them freely.
                // All static files live at the root path, so single-segment
                // patterns suffice (PathPattern forbids anything after "**").
                .requestMatchers("/*.js", "/*.css", "/*.ico",
                        "/*.png", "/*.svg", "/*.woff2", "/*.map").permitAll()

                // Public pages: login/register, the live broadcast, and the
                // read-only player list + analysis + profile (guest access).
                .requestMatchers("/login.html", "/register.html", "/broadcast.html",
                        "/players.html", "/player.html").permitAll()

                // Public auth endpoints (login/register/self-lookup/logout + team list).
                .requestMatchers("/api/auth/login", "/api/auth/register",
                        "/api/auth/teams", "/api/auth/me", "/api/auth/logout").permitAll()

                // Read APIs the public broadcast screen depends on. Note the audit
                // GET lives under /api/admin but is read-only and needed by broadcast.
                .requestMatchers(HttpMethod.GET, "/api/dashboard/**", "/api/players/**",
                        "/api/config", "/api/admin/audit").permitAll()

                // Admin-only pages: setup (also the "/" welcome page) and the console.
                .requestMatchers("/", "/index.html", "/auction.html").hasRole("ADMIN")

                // Admin-only APIs: every write and user-management call.
                .requestMatchers("/api/admin/**").hasRole("ADMIN")

                // API docs are an admin tool.
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").hasRole("ADMIN")

                // Everything else (team.html, player.html, remaining reads) needs a login.
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginProcessingUrl("/api/auth/login")
                .successHandler(loginSuccessHandler())
                .failureHandler(loginFailureHandler())
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpServletResponse.SC_OK))
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(authenticationEntryPoint())
                .accessDeniedHandler(accessDeniedHandler())
            );
        return http.build();
    }

    /** After login, tell the JS where to land based on role. */
    private AuthenticationSuccessHandler loginSuccessHandler() {
        return (request, response, authentication) -> {
            UserDetails principal = (UserDetails) authentication.getPrincipal();
            boolean admin = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals(Role.ADMIN.authority()));
            String redirect = admin ? "/index.html" : "/team.html";
            writeJson(response, HttpServletResponse.SC_OK,
                    "{\"username\":\"" + escape(principal.getUsername())
                            + "\",\"role\":\"" + (admin ? "ADMIN" : "FRANCHISE_OWNER")
                            + "\",\"redirect\":\"" + redirect + "\"}");
        };
    }

    private AuthenticationFailureHandler loginFailureHandler() {
        return (request, response, exception) ->
                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                        "{\"error\":\"Invalid username or password\"}");
    }

    /** Not logged in: APIs get 401 JSON, page requests get bounced to the login screen. */
    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            if (isApi(request)) {
                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                        "{\"error\":\"Authentication required\"}");
            } else {
                response.sendRedirect("/login.html");
            }
        };
    }

    /** Logged in but not allowed: APIs get 403 JSON, page requests go to the owner view. */
    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            if (isApi(request)) {
                writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                        "{\"error\":\"Not permitted\"}");
            } else {
                response.sendRedirect("/team.html");
            }
        };
    }

    private static boolean isApi(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/");
    }

    private static void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(body);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
