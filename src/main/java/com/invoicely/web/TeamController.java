package com.invoicely.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Team page's endpoints (Product Scope §2). Like {@link ClientController},
 * this class does HTTP and nothing else; the rules live in {@link TeamService}.
 *
 * <p>Every method here is owner-only (Product Scope §3: staff cannot manage
 * the team). Each carries its own {@code @PreAuthorize} rather than one
 * class-level annotation, so the rule for a given method sits right next to
 * it — a class-level rule can quietly stop applying to a method that gets
 * moved or renamed. Cross-business access is a separate concern (404, not
 * 403 — ADR-0001) and is handled entirely inside {@link TeamService}.
 */
@RestController
@RequestMapping("/team")
class TeamController {

    private final TeamService teamService;

    TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @PreAuthorize("hasRole('OWNER')")
    @GetMapping
    List<StaffResponse> list() {
        return teamService.list();
    }

    @PreAuthorize("hasRole('OWNER')")
    @PostMapping
    ResponseEntity<CreatedStaffResponse> create(@Valid @RequestBody CreateStaffRequest request) {
        CreatedStaffResponse created = teamService.create(request);
        return ResponseEntity.created(URI.create("/team/" + created.id())).body(created);
    }

    /** Deactivates or reactivates a team member — the only thing a PATCH here does. */
    @PreAuthorize("hasRole('OWNER')")
    @PatchMapping("/{id}")
    StaffResponse setActive(@PathVariable Long id, @Valid @RequestBody UpdateActiveRequest request) {
        return teamService.setActive(id, request.active());
    }

    /** The one field a PATCH may change: whether the account can sign in. */
    record UpdateActiveRequest(boolean active) {
    }
}
