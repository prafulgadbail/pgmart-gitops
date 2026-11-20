package com.pgmart.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ProfileControllerTest {

    private static final String AUTH = "Bearer token";

    private final Users users = mock(Users.class);
    private final Tokens tokens = mock(Tokens.class);
    private final ProfileController profile = new ProfileController(users, tokens);

    private final Users.User asha = new Users.User(1, "asha@example.com", "Asha", "9876543210", "hash", "CUSTOMER", true);
    private final Users.Address home = new Users.Address(5, "Asha", "9876543210", "12 MG Road", "Pune", "Maharashtra",
            "411001", true);
    private final ProfileController.AddressRequest newAddress = new ProfileController.AddressRequest(
            "Asha", "9876543210", "12 MG Road", "Pune", "Maharashtra", "411001", false);

    @BeforeEach
    void loggedInAsAsha() {
        when(tokens.read(AUTH)).thenReturn(new AuthUser(1, "asha@example.com", "Asha", "CUSTOMER"));
        when(users.byId(1L)).thenReturn(Optional.of(asha));
    }

    @Test
    void meReturnsTheLoggedInUser() {
        assertThat(profile.me(AUTH).email()).isEqualTo("asha@example.com");
    }

    @Test
    void aTokenForADeletedUserIsRefused() {
        when(users.byId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> profile.me(AUTH))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void updateSavesTheNameAndPhone() {
        profile.update(AUTH, new ProfileController.ProfileRequest(" Asha K ", "9123456780"));

        verify(users).updateProfile(1L, "Asha K", "9123456780");
    }

    @Test
    void anEmptyPhoneIsSavedAsNoPhone() {
        profile.update(AUTH, new ProfileController.ProfileRequest("Asha", ""));
        profile.update(AUTH, new ProfileController.ProfileRequest("Asha", null));

        verify(users, times(2)).updateProfile(1L, "Asha", null);
    }

    @Test
    void addressesAreListedForTheLoggedInUser() {
        when(users.addresses(1L)).thenReturn(List.of(home));

        assertThat(profile.addresses(AUTH)).containsExactly(home);
    }

    @Test
    void anAddressIsAddedUpToTen() {
        when(users.addAddress(1L, newAddress)).thenReturn(home);

        assertThat(profile.addAddress(AUTH, newAddress)).isEqualTo(home);
    }

    @Test
    void theEleventhAddressIsRefused() {
        when(users.addresses(1L)).thenReturn(Collections.nCopies(10, home));

        assertThatThrownBy(() -> profile.addAddress(AUTH, newAddress))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void makeDefaultReturnsTheUpdatedList() {
        when(users.setDefaultAddress(1L, 5L)).thenReturn(true);
        when(users.addresses(1L)).thenReturn(List.of(home));

        assertThat(profile.makeDefault(AUTH, 5L)).containsExactly(home);
    }

    @Test
    void makeDefaultForAnUnknownAddressIsNotFound() {
        assertThatThrownBy(() -> profile.makeDefault(AUTH, 99L))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void deleteRemovesTheAddressOrSaysNotFound() {
        when(users.deleteAddress(1L, 5L)).thenReturn(true);
        profile.deleteAddress(AUTH, 5L);
        verify(users).deleteAddress(1L, 5L);

        assertThatThrownBy(() -> profile.deleteAddress(AUTH, 99L))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
