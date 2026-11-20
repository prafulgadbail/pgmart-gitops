package com.pgmart.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class TokensTest {

    // A new random signing secret for every test run, so no secret is written in the code
    private static final String SECRET = randomSecret();

    @Test
    void issuedTokenCanBeRead() {
        Tokens tokens = new Tokens(SECRET, Duration.ofHours(1));
        var user = new Users.User(7, "a@b.com", "Asha", null, "x", "ADMIN", true);

        AuthUser read = tokens.read("Bearer " + tokens.issue(user));

        assertThat(read.id()).isEqualTo(7);
        assertThat(read.email()).isEqualTo("a@b.com");
        assertThat(read.isAdmin()).isTrue();
    }

    @Test
    void tokenFromAnotherSecretIsRejected() {
        var user = new Users.User(7, "a@b.com", "Asha", null, "x", "CUSTOMER", true);
        String other = new Tokens(randomSecret(), Duration.ofHours(1)).issue(user);

        assertThatThrownBy(() -> new Tokens(SECRET, Duration.ofHours(1)).read("Bearer " + other))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void missingOrMalformedHeaderIsRejected() {
        Tokens tokens = new Tokens(SECRET, Duration.ofHours(1));

        assertThatThrownBy(() -> tokens.read(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> tokens.read("Basic abc")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> tokens.read("Bearer not-a-jwt")).isInstanceOf(ApiException.class);
    }

    @Test
    void shortSecretIsRefused() {
        assertThatThrownBy(() -> new Tokens("short", Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
