package com.invoicely.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The {@code /auth} endpoints. Like {@link InvoiceController}, this class does
 * HTTP and nothing else; the rules live in {@link AuthService}.
 *
 * <p>{@code register} and {@code login} are the two paths
 * {@link com.invoicely.security.SecurityConfig} leaves open to anyone, because
 * a token is how every other request proves who it is, and neither of these
 * has one yet. {@code change-password} is behind the usual token requirement —
 * it is reachable by a user who {@code must_change_password}, but only because
 * {@link com.invoicely.security.AccountStateFilter} carves out that one
 * exception, not because this endpoint is open. {@code me} is behind the usual
 * requirement too, with no such carve-out — see its own Javadoc below.
 */
@RestController
@RequestMapping("/auth")
@Tag(name = "Auth", description = "Registration, login, forced password change, and re-identifying the caller "
        + "from their token. Register and login are the only endpoints here reachable without a token.")
class AuthController {

    private final AuthService authService;

    AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "Register a business and its owner",
            description = "Creates the business and one OWNER account together, then logs that owner in.")
    @ApiResponse(responseCode = "201", description = "Business and owner created; token issued.")
    @ApiResponse(responseCode = "400", description = "Validation failed, or the password is too long for BCrypt.")
    @ApiResponse(responseCode = "409", description = "An account with this email already exists.")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @Operation(summary = "Log in",
            description = "Unknown email and wrong password answer identically, so a caller cannot enumerate emails.")
    @ApiResponse(responseCode = "200", description = "Token issued.")
    @ApiResponse(responseCode = "400", description = "Validation failed.")
    @ApiResponse(responseCode = "401", description = "Incorrect email or password (or the account is deactivated).")
    @PostMapping("/login")
    AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @Operation(summary = "Change your own password",
            description = "Also clears must_change_password, which is what lets a staff member off a temporary "
                    + "password reach the rest of the API.")
    @ApiResponse(responseCode = "200", description = "Password changed; a fresh token is issued.")
    @ApiResponse(responseCode = "400", description = "Validation failed, or the new password is too long for BCrypt.")
    @ApiResponse(responseCode = "401", description = "No/invalid token, or currentPassword does not match.")
    @PostMapping("/change-password")
    AuthResponse changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return authService.changePassword(request);
    }

    @Operation(summary = "Who am I",
            description = "Re-identifies the caller from their token, so the UI can restore a session after a page "
                    + "reload without asking for the password again. Unlike the other three endpoints here, this "
                    + "one requires a token — it is not in SecurityConfig's permit-all list. A caller whose "
                    + "must_change_password is still set gets 403, the same as everywhere else but change-password "
                    + "itself: AccountStateFilter carves out only that one path, and the UI already knows to show "
                    + "the forced-change interstitial straight from the login response, so this endpoint never has "
                    + "to special-case it.")
    @ApiResponse(responseCode = "200", description = "The caller's identity.")
    @ApiResponse(responseCode = "401", description = "No token, or the token is invalid, expired, or superseded "
            + "by a later password change.")
    @ApiResponse(responseCode = "403", description = "The account is deactivated, or must_change_password is "
            + "still set.")
    @GetMapping("/me")
    MeResponse me() {
        return authService.me();
    }
}
