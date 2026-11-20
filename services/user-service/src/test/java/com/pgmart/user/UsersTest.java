package com.pgmart.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The SQL against a real PostgreSQL in a container, with the same Flyway migrations as production. */
class UsersTest {

    private static PostgreSQLContainer postgres;
    private static Users users;

    @BeforeAll
    static void startDatabase() {
        postgres = new PostgreSQLContainer("postgres:17-alpine");
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
        users = new Users(JdbcClient.create(
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())));
    }

    @AfterAll
    static void stopDatabase() {
        postgres.stop();
    }

    @Test
    void aUserIsCreatedAndFoundByEmailAndId() {
        Users.User created = users.create("find@example.com", "Asha", "hash", "CUSTOMER", false);

        assertThat(users.byEmail("find@example.com")).contains(created);
        assertThat(users.byId(created.id())).contains(created);
        assertThat(created.emailVerified()).isFalse();
        assertThat(users.byEmail("nobody@example.com")).isEmpty();
    }

    @Test
    void anAccountCanBeVerifiedUpdatedAndMadeAdmin() {
        long id = users.create("change@example.com", "Asha", "hash-1", "CUSTOMER", false).id();

        users.restartSignup(id, "Asha K", "hash-2");
        users.markVerified(id);
        users.updatePassword(id, "hash-3");
        users.updateProfile(id, "Asha Kumar", "9876543210");
        Users.User changed = users.byId(id).orElseThrow();
        assertThat(changed.name()).isEqualTo("Asha Kumar");
        assertThat(changed.phone()).isEqualTo("9876543210");
        assertThat(changed.passwordHash()).isEqualTo("hash-3");
        assertThat(changed.emailVerified()).isTrue();

        users.makeAdmin(id, "hash-4");
        assertThat(users.byId(id).orElseThrow().role()).isEqualTo("ADMIN");
    }

    @Test
    void aNewCodeReplacesTheOldOneAndCountsAttemptsFromZero() {
        users.saveOtp("otp@example.com", "VERIFY", "code-1", OffsetDateTime.now().plusMinutes(10));
        users.countAttempt("otp@example.com", "VERIFY");
        assertThat(users.otp("otp@example.com", "VERIFY").orElseThrow().attempts()).isEqualTo(1);

        users.saveOtp("otp@example.com", "VERIFY", "code-2", OffsetDateTime.now().plusMinutes(10));
        Users.OtpRow otp = users.otp("otp@example.com", "VERIFY").orElseThrow();
        assertThat(otp.codeHash()).isEqualTo("code-2");
        assertThat(otp.attempts()).isZero();

        users.deleteOtp("otp@example.com", "VERIFY");
        assertThat(users.otp("otp@example.com", "VERIFY")).isEmpty();
    }

    @Test
    void theFirstAddressIsTheDefaultUntilAnotherIsChosen() {
        long id = users.create("address@example.com", "Asha", "hash", "CUSTOMER", true).id();

        Users.Address home = users.addAddress(id, address(" Home ", false));
        assertThat(home.isDefault()).isTrue();
        assertThat(home.fullName()).isEqualTo("Home");

        Users.Address office = users.addAddress(id, address("Office", true));
        assertThat(users.addresses(id)).extracting(Users.Address::fullName).containsExactly("Office", "Home");

        assertThat(users.setDefaultAddress(id, home.id())).isTrue();
        assertThat(users.addresses(id).get(0).fullName()).isEqualTo("Home");

        long otherUser = users.create("other@example.com", "Ravi", "hash", "CUSTOMER", true).id();
        assertThat(users.setDefaultAddress(otherUser, home.id())).isFalse();
        assertThat(users.deleteAddress(otherUser, office.id())).isFalse();
        assertThat(users.deleteAddress(id, office.id())).isTrue();
        assertThat(users.addresses(id)).hasSize(1);
    }

    private static ProfileController.AddressRequest address(String name, boolean isDefault) {
        return new ProfileController.AddressRequest(name, "9876543210", "12 MG Road", "Pune", "Maharashtra",
                "411001", isDefault);
    }
}
