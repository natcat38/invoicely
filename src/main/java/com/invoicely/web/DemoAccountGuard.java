package com.invoicely.web;

import com.invoicely.DemoDataSeeder.DemoDataProperties;
import com.invoicely.domain.User;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Keeps the two public demo logins usable by everyone.
 *
 * <p>The demo password is published in the README, so anyone holding it can
 * call the API directly. Without this guard, one visitor could change the
 * password (or have the owner deactivate the staff login) and every later
 * visitor would be locked out — and {@code DemoDataSeeder} cannot repair that,
 * because it returns early once the owner's email exists.
 *
 * <p>The guard is switched on by the {@code demo} profile alone:
 * {@link DemoDataProperties} is only registered as a bean when that profile is
 * active, so everywhere else (tests, local development, a real deployment)
 * {@link #isDemoAccount} is false for every user and nothing changes.
 *
 * <p>Every code path that can change a user's password hash or flip a user's
 * {@code active} flag must call a method here. At the time of writing those
 * are exactly two: {@code AuthService.changePassword} and
 * {@code TeamService.setActive}. There is no endpoint that edits a user's
 * email, role or name, so none of those need guarding. A new endpoint that
 * touches {@code setPasswordHash}, {@code setActive} or {@code setEmail} on
 * {@link User} has to call this guard too.
 */
@Component
public class DemoAccountGuard {

    private final Set<String> demoEmails;

    DemoAccountGuard(ObjectProvider<DemoDataProperties> demoProperties) {
        // Lower-cased because the API treats emails case-insensitively
        // (findByEmailIgnoreCase), so "Owner@Invoicely.demo" is the same account.
        this.demoEmails = demoProperties.stream()
                .flatMap(properties -> Stream.of(properties.ownerEmail(), properties.staffEmail()))
                .map(email -> email.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** @throws DemoAccountProtectedException if this is a seeded demo account */
    void rejectPasswordChange(User user) {
        if (isDemoAccount(user)) {
            throw new DemoAccountProtectedException(
                    "This is a shared demo account, so its password cannot be changed. "
                            + "Register your own business to try changing a password.");
        }
    }

    /**
     * Only deactivation is refused. Reactivating a demo account can only
     * restore access, so it stays allowed: it is the way back if one was ever
     * switched off before this guard existed.
     *
     * @throws DemoAccountProtectedException if this is a seeded demo account
     */
    void rejectDeactivation(User user) {
        if (isDemoAccount(user)) {
            throw new DemoAccountProtectedException(
                    "This is a shared demo account, so it cannot be deactivated. "
                            + "Register your own business to try deactivating staff.");
        }
    }

    private boolean isDemoAccount(User user) {
        return demoEmails.contains(user.getEmail().toLowerCase(Locale.ROOT));
    }
}
