package com.auctiontracker.tournament;

import com.auctiontracker.config.AuctionProperties;
import com.auctiontracker.core.PlayerJpaRepository;
import com.auctiontracker.core.TeamJpaRepository;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tournament/auction management. Writes (create, activate, edit rules) live under
 * {@code /api/admin} (admin-only); the "which tournament am I looking at" lookup
 * is public so the broadcast and owner views can label the screen.
 */
@RestController
public class TournamentController {

    private final TournamentService service;
    private final PlayerJpaRepository players;
    private final TeamJpaRepository teams;

    public TournamentController(TournamentService service, PlayerJpaRepository players,
                                TeamJpaRepository teams) {
        this.service = service;
        this.players = players;
        this.teams = teams;
    }

    public record TournamentSummary(UUID id, String name, String slug, boolean active,
                                    Instant createdAt, long playerCount, long teamCount) {}

    public record CurrentTournament(UUID id, String name, String slug) {}

    public record CreateRequest(@NotBlank String name, AuctionProperties rules) {}

    public record DeleteRequest(String password) {}

    public record TournamentDetail(UUID id, String name, String slug, boolean active,
                                   AuctionProperties rules) {}

    /** Public: every auction with its live counts — the hub list (all roles browse it). */
    @GetMapping("/api/tournaments")
    public List<TournamentSummary> list() {
        return service.list().stream().map(t -> new TournamentSummary(
                t.getId(), t.getName(), t.getSlug(), t.isActive(), t.getCreatedAt(),
                players.countByTournamentId(t.getId()), teams.countByTournamentId(t.getId()))).toList();
    }

    /** Admin alias of the list (kept for existing admin callers). */
    @GetMapping("/api/admin/tournaments")
    public List<TournamentSummary> adminList() {
        return list();
    }

    /** Admin: one tournament's rule book, to prefill the editor. */
    @GetMapping("/api/admin/tournaments/{id}")
    public TournamentDetail detail(@PathVariable UUID id) {
        Tournament t = service.get(id);
        return new TournamentDetail(t.getId(), t.getName(), t.getSlug(), t.isActive(),
                service.rulesOf(id));
    }

    /** Admin: create a new tournament (its own rules) and switch to it. */
    @PostMapping("/api/admin/tournaments")
    public TournamentDetail create(@RequestBody CreateRequest req) {
        Tournament t = service.create(req.name(), req.rules());
        return new TournamentDetail(t.getId(), t.getName(), t.getSlug(), t.isActive(),
                service.rulesOf(t.getId()));
    }

    /** Admin: edit an existing tournament's name/rules. */
    @PutMapping("/api/admin/tournaments/{id}")
    public TournamentDetail update(@PathVariable UUID id, @RequestBody CreateRequest req) {
        Tournament t = service.updateRules(id, req.name(), req.rules());
        return new TournamentDetail(t.getId(), t.getName(), t.getSlug(), t.isActive(),
                service.rulesOf(id));
    }

    /** Admin: make this tournament the default used when a request names none. */
    @PostMapping("/api/admin/tournaments/{id}/activate")
    public CurrentTournament setDefault(@PathVariable UUID id) {
        Tournament t = service.setDefault(id);
        return new CurrentTournament(t.getId(), t.getName(), t.getSlug());
    }

    /** Admin: permanently delete an auction and all its data (password-guarded). */
    @DeleteMapping("/api/admin/tournaments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestBody(required = false) DeleteRequest req) {
        service.delete(id, req == null ? null : req.password());
    }

    /** Public: the default tournament (for labelling id-less views). */
    @GetMapping("/api/tournaments/current")
    public CurrentTournament current() {
        Tournament t = service.active();
        return t == null ? null : new CurrentTournament(t.getId(), t.getName(), t.getSlug());
    }

    /** Public: one tournament's identity, so any screen can show which auction it's on. */
    @GetMapping("/api/tournaments/{id}")
    public CurrentTournament byId(@PathVariable UUID id) {
        Tournament t = service.get(id);
        return new CurrentTournament(t.getId(), t.getName(), t.getSlug());
    }
}
