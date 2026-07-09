package com.auctiontracker.auth;

import com.auctiontracker.core.AuctionException;
import com.auctiontracker.core.Team;
import com.auctiontracker.core.TeamRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Admin-only management of franchise-owner accounts. Lives under {@code /api/admin}
 * so it inherits the ADMIN-only rule in {@link SecurityConfig}. The admin account
 * itself is config-based and never appears here, so it cannot be removed.
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final UserAccountRepository accounts;
    private final TeamRepository teams;

    public AdminUserController(UserAccountRepository accounts, TeamRepository teams) {
        this.accounts = accounts;
        this.teams = teams;
    }

    public record OwnerView(UUID id, String username, String displayName,
                            UUID teamId, String teamName, Instant createdAt) {}

    @GetMapping
    public List<OwnerView> listOwners() {
        return accounts.findAll().stream()
                .sorted(Comparator.comparing(UserAccount::getCreatedAt))
                .map(a -> new OwnerView(a.getId(), a.getUsername(), a.getDisplayName(),
                        a.getTeamId(),
                        teams.findById(a.getTeamId()).map(Team::getName).orElse("—"),
                        a.getCreatedAt()))
                .toList();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeOwner(@PathVariable UUID id) {
        if (!accounts.existsById(id)) {
            throw AuctionException.notFound("OWNER_NOT_FOUND", "No such franchise owner");
        }
        accounts.deleteById(id);
    }
}
