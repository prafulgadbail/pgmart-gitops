package com.pgmart.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AdminBookControllerTest {

    private static final String AUTH = "Bearer admin";

    private final Books books = mock(Books.class);
    private final Auth auth = mock(Auth.class);
    private final AdminBookController admin = new AdminBookController(books, auth);

    private final Books.Book book = BookControllerTest.book(9, true);
    private final AdminBookController.BookRequest request = request(69900, 119900);

    @BeforeEach
    void loggedInAsAdmin() {
        when(auth.admin(AUTH)).thenReturn(new AuthUser(1, "admin@example.com", "Admin", "ADMIN"));
        when(books.topicExists("Containers")).thenReturn(true);
    }

    @Test
    void theConsoleListsEveryBookAndCountsActiveAndLowStock() {
        when(books.all()).thenReturn(List.of(book, BookControllerTest.book(10, false)));
        when(books.lowStock(AdminBookController.LOW_STOCK)).thenReturn(4);

        assertThat(admin.all(AUTH)).hasSize(2);
        assertThat(admin.stats(AUTH)).isEqualTo(new AdminBookController.Stats(1, 4));
    }

    @Test
    void aBookIsCreatedAndUpdated() {
        when(books.create(request)).thenReturn(book);
        when(books.update(9, request)).thenReturn(Optional.of(book));

        assertThat(admin.create(AUTH, request)).isEqualTo(book);
        assertThat(admin.update(AUTH, 9, request)).isEqualTo(book);
        assertNotFound(() -> admin.update(AUTH, 99, request));
    }

    @Test
    void aPriceAboveTheMrpOrAnUnknownTopicIsRefused() {
        assertBadRequest(() -> admin.create(AUTH, request(129900, 119900)));

        AdminBookController.BookRequest unknownTopic = new AdminBookController.BookRequest("Title", "Subtitle", "Cooking",
                "logo", "#000000", "#ffffff", 119900, 69900, 1, 10, null, "", true);
        assertBadRequest(() -> admin.create(AUTH, unknownTopic));
        verify(books, never()).create(any());
    }

    @Test
    void stockAndVisibilityAreChanged() {
        when(books.setStock(9, 25)).thenReturn(Optional.of(book));
        when(books.setActive(9, false)).thenReturn(Optional.of(book));
        when(books.setActive(9, true)).thenReturn(Optional.of(book));

        assertThat(admin.stock(AUTH, 9, new AdminBookController.StockRequest(25))).isEqualTo(book);
        assertThat(admin.active(AUTH, 9, Map.of("active", false))).isEqualTo(book);
        assertThat(admin.active(AUTH, 9, Map.of("active", true))).isEqualTo(book);
        assertNotFound(() -> admin.stock(AUTH, 99, new AdminBookController.StockRequest(1)));
        assertNotFound(() -> admin.active(AUTH, 99, Map.of("active", true)));
    }

    private static AdminBookController.BookRequest request(long price, long mrp) {
        return new AdminBookController.BookRequest("Kubernetes Field Guide", "Run it", "Containers", "k8s",
                "#326CE5", "#12206b", mrp, price, 10, 480, List.of("Rolling updates"), "", true);
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private static void assertBadRequest(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
