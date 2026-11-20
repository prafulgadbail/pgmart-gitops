package com.pgmart.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AuthTest {

    // Random secrets for every test run, so no secret is written in the code
    private static final String SECRET = randomSecret();
    private static final String INTERNAL = randomSecret();

    private final Auth auth = new Auth(SECRET, INTERNAL);

    @Test
    void aTokenFromUserServiceIsRead() {
        AuthUser user = auth.user(bearer(SECRET, "7", "CUSTOMER"));

        assertThat(user.id()).isEqualTo(7);
        assertThat(user.email()).isEqualTo("asha@example.com");
        assertThat(user.isAdmin()).isFalse();
    }

    @Test
    void missingWrongOrBrokenTokensAreRefused() {
        assertUnauthorized(null);
        assertUnauthorized("Basic abc");
        assertUnauthorized("Bearer not-a-jwt");
        assertUnauthorized(bearer(randomSecret(), "7", "CUSTOMER"));
        assertUnauthorized(bearer(SECRET, "not-a-number", "CUSTOMER"));
    }

    @Test
    void shoppingIsForCustomersAndTheConsoleForAdmins() {
        String customer = bearer(SECRET, "7", "CUSTOMER");
        String admin = bearer(SECRET, "1", "ADMIN");

        assertThat(auth.customer(customer).id()).isEqualTo(7);
        assertThat(auth.admin(admin).isAdmin()).isTrue();
        assertForbidden(() -> auth.customer(admin));
        assertForbidden(() -> auth.admin(customer));
    }

    @Test
    void internalCallsNeedTheSharedToken() {
        assertThatCode(() -> auth.internal(INTERNAL)).doesNotThrowAnyException();
        assertForbidden(() -> auth.internal(null));
        assertForbidden(() -> auth.internal("wrong"));
    }

    private void assertUnauthorized(String header) {
        assertThatThrownBy(() -> auth.user(header))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    private static void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private static String bearer(String secret, String subject, String role) {
        return "Bearer " + JWT.create()
                .withIssuer("pgmart")
                .withSubject(subject)
                .withClaim("email", "asha@example.com")
                .withClaim("name", "Asha")
                .withClaim("role", role)
                .withExpiresAt(Instant.now().plusSeconds(600))
                .sign(Algorithm.HMAC256(secret));
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
