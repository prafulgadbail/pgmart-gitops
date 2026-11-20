package com.pgmart.user;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class AdminAccountTest {

    private final Users users = mock(Users.class);

    @Test
    void noAdminIsCreatedWithoutEmailAndPassword() {
        new AdminAccount(users, "", "").run(null);
        new AdminAccount(users, "admin@example.com", "").run(null);

        verifyNoInteractions(users);
    }

    @Test
    void aShortAdminPasswordStopsTheStart() {
        assertThatThrownBy(() -> new AdminAccount(users, "admin@example.com", "short").run(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anAdminIsCreatedWhenTheEmailIsNew() {
        new AdminAccount(users, " Admin@Example.com ", "admin-password").run(null);

        verify(users).create(eq("admin@example.com"), eq("Store Admin"), anyString(), eq("ADMIN"), eq(true));
    }

    @Test
    void anExistingAccountIsMadeAdmin() {
        when(users.byEmail("admin@example.com")).thenReturn(Optional.of(
                new Users.User(3, "admin@example.com", "Asha", null, "hash", "CUSTOMER", false)));

        new AdminAccount(users, "admin@example.com", "admin-password").run(null);

        verify(users).makeAdmin(eq(3L), anyString());
        verify(users, never()).create(any(), any(), any(), any(), eq(true));
    }
}
