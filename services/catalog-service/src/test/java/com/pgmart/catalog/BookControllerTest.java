package com.pgmart.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class BookControllerTest {

    private static final String AUTH = "Bearer token";

    private final Books books = mock(Books.class);
    private final Auth auth = mock(Auth.class);
    private final BookController controller = new BookController(books, auth);

    private final Books.Book shown = book(9, true);
    private final Books.Book hidden = book(10, false);

    @BeforeEach
    void setUp() {
        when(auth.customer(AUTH)).thenReturn(new AuthUser(7, "asha@example.com", "Asha", "CUSTOMER"));
        when(books.byId(9)).thenReturn(Optional.of(shown));
        when(books.byId(10)).thenReturn(Optional.of(hidden));
    }

    @Test
    void topicsAndSearchComeFromTheCatalog() {
        when(books.topics()).thenReturn(List.of(new Books.Topic("Containers", "Containers", "Ship it", 3)));
        when(books.search("kube", "Containers")).thenReturn(List.of(shown));

        assertThat(controller.topics()).hasSize(1);
        assertThat(controller.list("kube", "Containers")).containsExactly(shown);
    }

    @Test
    void aVeryLongSearchIsRefused() {
        assertThatThrownBy(() -> controller.list("x".repeat(101), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void onlyActiveBooksCanBeOpened() {
        assertThat(controller.one(9)).isEqualTo(shown);
        assertNotFound(() -> controller.one(10));
        assertNotFound(() -> controller.one(11));
    }

    @Test
    void theWishlistBelongsToTheLoggedInCustomer() {
        when(books.wishlist(7)).thenReturn(List.of(9L));

        assertThat(controller.wishlist(AUTH)).containsExactly(9L);

        controller.addToWishlist(AUTH, 9);
        verify(books).addToWishlist(7, 9);

        controller.removeFromWishlist(AUTH, 9);
        verify(books).removeFromWishlist(7, 9);
    }

    @Test
    void aHiddenBookCannotBeWished() {
        assertNotFound(() -> controller.addToWishlist(AUTH, 10));
        verify(books, never()).addToWishlist(anyLong(), anyLong());
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    static Books.Book book(long id, boolean active) {
        return new Books.Book(id, "Kubernetes Field Guide", "Run it", "Containers", "k8s", "#326CE5", "#12206b",
                119900, 69900, 10, 480, List.of(), null, active);
    }
}
