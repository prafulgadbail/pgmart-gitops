package com.pgmart.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class TokensTest {

    // Random keys made for each test run, so no secret-looking value is written in the code
    private static final String SECRET = randomSecret();

    private static String randomSecret() {
        return UUID.randomUUID() + "-" + UUID.randomUUID(); // 73 characters, more than the 32 needed
    }

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
    void shortSecretIsRefused() {
        assertThatThrownBy(() -> new Tokens("short", Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
