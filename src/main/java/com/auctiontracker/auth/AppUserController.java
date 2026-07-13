package com.auctiontracker.auth;

import com.auctiontracker.core.AuctionException;
import com.auctiontracker.tournament.Tournament;
import com.auctiontracker.tournament.TournamentRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * App-admin-only management of tournament-admin accounts (see {@link Role}).
 * Under {@code /api/admin/app-users}, guarded ADMIN-only in {@link SecurityConfig}.
 * These users can run one or more specific auctions but cannot create/delete
 * auctions or manage other users.
 */
@RestController
@RequestMapping("/api/admin/app-users")
public class AppUserController {

    private final UserAccountRepository accounts;
    private final TournamentRepository tournaments;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;

    public AppUserController(UserAccountRepository accounts, TournamentRepository tournaments,
                             PasswordEncoder passwordEncoder, SecurityProperties props) {
        this.accounts = accounts;
        this.tournaments = tournaments;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = props.adminUsername();
    }

    public record GrantView(UUID id, String name) {}

    public record AppUserView(UUID id, String username, String displayName,
                              List<GrantView> tournaments, Instant createdAt) {}

    public record CreateRequest(@NotBlank String displayName,
                                @NotBlank @Size(min = 3, max = 40) String username,
                                @NotBlank @Size(min = 6, max = 100) String password,
                                Set<UUID> tournamentIds) {}

    public record AccessRequest(Set<UUID> tournamentIds) {}

    @GetMapping
    public List<AppUserView> list() {
        Map<UUID, String> names = tournamentNames();
        return accounts.findByRole(Role.TOURNAMENT_ADMIN).stream()
                .sorted(Comparator.comparing(UserAccount::getCreatedAt))
                .map(a -> view(a, names))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AppUserView create(@Valid @RequestBody CreateRequest req) {
        String username = req.username().trim().toLowerCase();
        if (username.equalsIgnoreCase(adminUsername) || accounts.existsByUsername(username)) {
            throw AuctionException.conflict("USERNAME_TAKEN", "That username is already taken");
        }
        Set<UUID> grants = validGrants(req.tournamentIds());
        String hash = passwordEncoder.encode(req.password());
        UserAccount saved = accounts.save(
                UserAccount.tournamentAdmin(username, hash, req.displayName().trim(), grants));
        return view(saved, tournamentNames());
    }

    @PutMapping("/{id}/access")
    public AppUserView setAccess(@PathVariable UUID id, @RequestBody AccessRequest req) {
        UserAccount account = tournamentAdmin(id);
        account.setAdminTournamentIds(validGrants(req.tournamentIds()));
        UserAccount saved = accounts.save(account);
        return view(saved, tournamentNames());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID id) {
        tournamentAdmin(id); // 404 if not a tournament admin
        accounts.deleteById(id);
    }

    private UserAccount tournamentAdmin(UUID id) {
        UserAccount a = accounts.findById(id)
                .filter(u -> u.getRole() == Role.TOURNAMENT_ADMIN)
                .orElseThrow(() -> AuctionException.notFound("USER_NOT_FOUND", "No such tournament admin"));
        return a;
    }

    /** Keeps only ids that still refer to a real auction. */
    private Set<UUID> validGrants(Set<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        return ids.stream().filter(tournaments::existsById).collect(Collectors.toSet());
    }

    private Map<UUID, String> tournamentNames() {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (Tournament t : tournaments.findAllByOrderByCreatedAtAsc()) {
            names.put(t.getId(), t.getName());
        }
        return names;
    }

    private AppUserView view(UserAccount a, Map<UUID, String> names) {
        List<GrantView> grants = a.getAdminTournamentIds().stream()
                .map(id -> new GrantView(id, names.getOrDefault(id, "(deleted auction)")))
                .sorted(Comparator.comparing(GrantView::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new AppUserView(a.getId(), a.getUsername(), a.getDisplayName(), grants, a.getCreatedAt());
    }
}
