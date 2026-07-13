package com.auctiontracker.auth;

import com.auctiontracker.core.AuctionException;
import com.auctiontracker.core.Team;
import com.auctiontracker.core.TeamRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Public auth surface: franchise-owner self-registration, the team list that
 * feeds the registration dropdown, and a "who am I" lookup the frontend uses to
 * gate navigation. Login/logout themselves are handled by Spring Security
 * (see {@link SecurityConfig}); this controller does not implement them.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserAccountRepository accounts;
    private final TeamRepository teams;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;

    public AuthController(UserAccountRepository accounts, TeamRepository teams,
                          PasswordEncoder passwordEncoder, SecurityProperties props) {
        this.accounts = accounts;
        this.teams = teams;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = props.adminUsername();
    }

    public record RegisterRequest(
            @NotBlank String displayName,
            @NotBlank @Size(min = 3, max = 40) String username,
            @NotBlank @Size(min = 6, max = 100) String password,
            @NotNull UUID teamId) {}

    public record RegisterResponse(String username, UUID teamId) {}

    /** Team option for the registration dropdown; {@code claimed} hides taken teams. */
    public record TeamOption(UUID teamId, String name, String ownerName, boolean claimed) {}

    public record MeResponse(boolean authenticated, String username, String displayName,
                             String role, UUID teamId, String teamName) {}

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest req) {
        String username = req.username().trim().toLowerCase();
        if (username.equalsIgnoreCase(adminUsername) || accounts.existsByUsername(username)) {
            throw AuctionException.conflict("USERNAME_TAKEN", "That username is already taken");
        }
        Team team = teams.findById(req.teamId())
                .orElseThrow(() -> AuctionException.notFound("TEAM_NOT_FOUND", "No such team"));
        if (accounts.existsByTeamId(team.getTeamId())) {
            throw AuctionException.conflict("TEAM_CLAIMED",
                    "That team already has an owner account");
        }
        String hash = passwordEncoder.encode(req.password());
        UserAccount account = UserAccount.franchiseOwner(username, hash, req.displayName().trim(),
                team.getTeamId());
        account.setTournamentId(team.getTournamentId()); // owner belongs to its team's tournament
        UserAccount saved = accounts.save(account);
        return new RegisterResponse(saved.getUsername(), saved.getTeamId());
    }

    /** Teams available to register against — taken ones are flagged, not hidden, for clarity. */
    @GetMapping("/teams")
    public List<TeamOption> registrableTeams() {
        return teams.findAll().stream()
                .sorted(Comparator.comparing(Team::getName, String.CASE_INSENSITIVE_ORDER))
                .map(t -> new TeamOption(t.getTeamId(), t.getName(), t.getOwnerName(),
                        accounts.existsByTeamId(t.getTeamId())))
                .toList();
    }

    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
            return new MeResponse(false, null, null, null, null, null);
        }
        String teamName = principal.teamId() == null ? null
                : teams.findById(principal.teamId()).map(Team::getName).orElse(null);
        return new MeResponse(true, principal.getUsername(), principal.displayName(),
                principal.role().name(), principal.teamId(), teamName);
    }
}
