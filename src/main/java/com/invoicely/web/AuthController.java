package com.invoicely.web;

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
class AuthController {

    private final AuthService authService;

    AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/change-password")
    AuthResponse changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return authService.changePassword(request);
    }
}
