package com.invoicely.security;

import java.security.SecureRandom;

/**
 * Generates the one-time password {@code POST /team} hands back when the
 * owner adds a staff member (Product Scope §5.1).
 *
 * <p>The alphabet leaves out the five characters that are easy to confuse with
 * one another when read aloud or copied off a screen — {@code O} and {@code 0},
 * {@code l} and {@code 1} and {@code I} — because that is exactly how this
 * password travels: the owner reads or copies it once, into a chat message or
 * a face-to-face handoff, and it is never shown again. Twelve characters from
 * the remaining ~57-character alphabet is well past the 8-character minimum
 * the change-password endpoint enforces, and the account is forced to replace
 * it on first login anyway, so the temporary password only has to survive one
 * use.
 */
public final class TemporaryPasswords {

    private static final String ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int LENGTH = 12;

    // One shared generator: SecureRandom is safe for concurrent use, and
    // constructing a fresh one per call just re-seeds for no benefit.
    private static final SecureRandom RANDOM = new SecureRandom();

    private TemporaryPasswords() {
    }

    public static String generate() {
        StringBuilder password = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }
}
