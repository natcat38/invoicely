package com.invoicely.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
 * exception, not because this endpoint is open.
 */
@RestController
@RequestMapping("/auth")
@Tag(name = "Auth", description = "Registration, login and forced password change. The only endpoints reachable without a token.")
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
}
