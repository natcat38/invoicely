package com.invoicely.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
@Tag(name = "Team", description = "Staff account management. Every endpoint here is owner-only.")
class TeamController {

    private final TeamService teamService;

    TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @Operation(summary = "List team members")
    @ApiResponse(responseCode = "200", description = "Staff in the caller's business, with last-active info.")
    @ApiResponse(responseCode = "403", description = "Caller is STAFF, not OWNER.")
    @PreAuthorize("hasRole('OWNER')")
    @GetMapping
    List<StaffResponse> list() {
        return teamService.list();
    }

    @Operation(summary = "Add a staff account", description = "A temporary password is generated and returned "
            + "once; the account must change it before doing anything else.")
    @ApiResponse(responseCode = "201", description = "Staff account created, with its one-time temporary password.")
    @ApiResponse(responseCode = "400", description = "Validation failed.")
    @ApiResponse(responseCode = "403", description = "Caller is STAFF, not OWNER.")
    @ApiResponse(responseCode = "409", description = "A user with this email already exists.")
    @PreAuthorize("hasRole('OWNER')")
    @PostMapping
    ResponseEntity<CreatedStaffResponse> create(@Valid @RequestBody CreateStaffRequest request) {
        CreatedStaffResponse created = teamService.create(request);
        return ResponseEntity.created(URI.create("/team/" + created.id())).body(created);
    }

    /** Deactivates or reactivates a team member — the only thing a PATCH here does. */
    @Operation(summary = "Deactivate or reactivate a team member")
    @ApiResponse(responseCode = "200", description = "Member's active flag updated.")
    @ApiResponse(responseCode = "400", description = "Validation failed (active is required).")
    @ApiResponse(responseCode = "403", description = "Caller is STAFF, not OWNER.")
    @ApiResponse(responseCode = "404", description = "No such team member, or they belong to another business.")
    @ApiResponse(responseCode = "409", description = "The owner tried to deactivate their own account.")
    @PreAuthorize("hasRole('OWNER')")
    @PatchMapping("/{id}")
    StaffResponse setActive(@PathVariable Long id, @Valid @RequestBody UpdateActiveRequest request) {
        return teamService.setActive(id, request.active());
    }

    /**
     * The one field a PATCH may change: whether the account can sign in.
     *
     * <p>Boxed and {@code @NotNull} rather than a primitive: a primitive would
     * default a missing or misspelled field to {@code false}, so a malformed
     * request would quietly deactivate someone instead of being rejected.
     */
    record UpdateActiveRequest(
            @NotNull(message = "Say whether the account should be active.")
            Boolean active) {
    }
}
