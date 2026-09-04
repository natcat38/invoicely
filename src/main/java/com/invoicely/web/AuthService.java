package com.invoicely.web;

import com.invoicely.domain.Business;
import com.invoicely.domain.BusinessRepository;
import com.invoicely.domain.Role;
import com.invoicely.domain.User;
import com.invoicely.domain.UserRepository;
import com.invoicely.security.JwtService;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything the {@code /auth} endpoints do that is not HTTP — see
 * {@link InvoiceService} for why this split exists.
 *
 * <p>Every method here ends the same way: hand the caller a fresh
 * {@link AuthResponse}, built from a token {@link JwtService} just issued. A
 * token is stateless once signed, so "logging out" or "reducing someone's
 * access" is never done by revoking one — it happens by the checks that run on
 * every request afterwards: password verification here, and
 * {@code active} / {@code must_change_password} in
 * {@link com.invoicely.security.AccountStateFilter}.
 */
@Service
@Transactional
public class AuthService {

    /**
     * BCrypt hashes at most 72 bytes and throws outright above that, so an
     * uncapped password would answer a perfectly reasonable long passphrase
     * with a 500. The request records cap the character count, which catches
     * every ordinary case with a proper field-level 400; {@link #hash} then
     * checks the byte count, because a password of accented or emoji
     * characters can be under 72 characters and still over 72 bytes.
     */
    public static final int MAX_PASSWORD_BYTES = 72;

    private final UserRepository users;
    private final BusinessRepository businesses;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final CurrentRequest currentRequest;

    AuthService(UserRepository users,
                BusinessRepository businesses,
                PasswordEncoder passwordEncoder,
                JwtService jwtService,
                CurrentRequest currentRequest) {
        this.users = users;
        this.businesses = businesses;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.currentRequest = currentRequest;
    }

    /**
     * Creates the business and its OWNER account together, then logs that
     * owner straight in — Product Scope §5.1 treats registration as one
     * screen, not "create a business" followed by a separate "now sign in".
     */
    public AuthResponse register(RegisterRequest request) {
        // Checked up front rather than left to the unique index alone: the
        // index still has the final word if two registrations for the same
        // email land at the exact same moment, but a clear 409 beats a
        // generic 500 for the overwhelmingly common case of someone
        // re-registering by mistake.
        if (users.findByEmailIgnoreCase(request.email()).isPresent()) {
            throw new ConflictException("email-taken",
                    "An account with this email address already exists.");
        }

        // No GST or payment-terms fields are set here: the Business
        // constructor already defaults them (not registered, 30-day terms),
        // and this request has no opinion on either.
        Business business = businesses.save(new Business(request.businessName()));

        User owner = new User(business, request.ownerName(), request.email(),
                hash(request.password()), Role.OWNER);
        // An owner chose their own password just now, so there is nothing to
        // force a change on — unlike the temporary password Task 4's /team
        // endpoint generates for a new staff member.
        users.save(owner);

        return AuthResponse.of(owner, jwtService.issue(owner));
    }

    /**
     * Verifies a login and, on success, issues a token.
     *
     * <p>Unknown email and wrong password answer with the identical 401 —
     * distinguishing them would confirm to an attacker which email addresses
     * have accounts. A deactivated account fails the same way for the same
     * reason: {@code AccountStateFilter} could reject it on the very next
     * request anyway, so letting login say "wrong password" here is not
     * hiding anything a caller could not learn by trying the account and
     * being kicked out immediately after.
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = users.findByEmailIgnoreCase(request.email())
                .filter(User::isActive)
                .orElseThrow(AuthService::invalidCredentials);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }
        return AuthResponse.of(user, jwtService.issue(user));
    }

    /**
     * Sets a new password and clears {@code mustChangePassword}, so this is
     * also how a staff member on a temporary password earns access to
     * everything else — {@code AccountStateFilter} lets this one path through
     * for them and nothing else.
     */
    public AuthResponse changePassword(ChangePasswordRequest request) {
        User user = users.findByIdAndBusinessId(currentRequest.userId(), currentRequest.businessId())
                .orElseThrow(() -> new NotFoundException("User"));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw invalidCredentials();
        }
        user.setPasswordHash(hash(request.newPassword()));
        user.setMustChangePassword(false);
        // No save() call: user is managed inside this transaction, so
        // Hibernate writes the changes back at commit (same as ClientService.update).

        return AuthResponse.of(user, jwtService.issue(user));
    }

    /**
     * Hashes a password, rejecting anything BCrypt cannot represent before it
     * throws. See {@link #MAX_PASSWORD_BYTES}.
     */
    private String hash(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new BadRequestException("password-too-long",
                    "That password is too long. Try one under 72 characters.");
        }
        return passwordEncoder.encode(password);
    }

    /**
     * One shared 401. Kept as a single factory method so login and
     * change-password cannot drift into wording a caller could use to tell
     * "wrong password" apart from "wrong everything".
     */
    private static InvalidCredentialsException invalidCredentials() {
        return new InvalidCredentialsException();
    }

    /**
     * A small, local exception rather than a new file: nothing outside this
     * service needs to know it exists, since {@link GlobalExceptionHandler}
     * already turns any {@link ApiException} into the matching Problem
     * Details response.
     */
    private static final class InvalidCredentialsException extends ApiException {
        private InvalidCredentialsException() {
            super(HttpStatus.UNAUTHORIZED, "invalid-credentials", "Incorrect email or password.");
        }
    }
}
