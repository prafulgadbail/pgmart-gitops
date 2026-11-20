package com.pgmart.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/** Reserving and releasing stock against a real PostgreSQL. */
class StockTest {

    private final Books books = new Books(TestDatabase.jdbcClient());
    private final Stock stock = new Stock(TestDatabase.jdbcClient());

    @Test
    void reserveTakesStockAndKeepsThePriceOfTheOrder() {
        Books.Book book = books.create(BooksTest.request("Reserved Book", "Build", 10, true));

        List<Stock.ReservedLine> lines = stock.reserve(orderId(),
                List.of(new Stock.Line(book.id(), 2), new Stock.Line(book.id(), 1)));

        assertThat(lines).containsExactly(new Stock.ReservedLine(book.id(), "Reserved Book", 3, 59900, 99900));
        assertThat(stock(book)).isEqualTo(7);
    }

    @Test
    void reservingTheSameOrderTwiceTakesStockOnce() {
        Books.Book book = books.create(BooksTest.request("Retried Book", "Build", 10, true));
        String order = orderId();

        stock.reserve(order, List.of(new Stock.Line(book.id(), 4)));
        List<Stock.ReservedLine> again = stock.reserve(order, List.of(new Stock.Line(book.id(), 4)));

        assertThat(again).hasSize(1);
        assertThat(stock(book)).isEqualTo(6);
    }

    @Test
    void releaseGivesTheStockBackOnceAndTheOrderCannotBeReservedAgain() {
        Books.Book book = books.create(BooksTest.request("Released Book", "Build", 5, true));
        String order = orderId();
        stock.reserve(order, List.of(new Stock.Line(book.id(), 5)));
        assertThat(stock(book)).isZero();

        stock.release(order);
        stock.release(order);
        stock.release("never-reserved");
        assertThat(stock(book)).isEqualTo(5);

        assertThatThrownBy(() -> stock.reserve(order, List.of(new Stock.Line(book.id(), 1))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("RELEASED"));
    }

    @Test
    void notEnoughStockIsRefusedWithTheNumberLeft() {
        Books.Book few = books.create(BooksTest.request("Few Left", "Build", 2, true));
        Books.Book none = books.create(BooksTest.request("None Left", "Build", 0, true));

        assertThatThrownBy(() -> stock.reserve(orderId(), List.of(new Stock.Line(few.id(), 3))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).isEqualTo("Only 2 left of Few Left"));
        assertThatThrownBy(() -> stock.reserve(orderId(), List.of(new Stock.Line(none.id(), 1))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).isEqualTo("None Left just went out of stock"));
    }

    @Test
    void hiddenOrMissingBooksCannotBeOrdered() {
        Books.Book hidden = books.create(BooksTest.request("Hidden Book", "Build", 5, false));

        assertThatThrownBy(() -> stock.reserve(orderId(), List.of(new Stock.Line(hidden.id(), 1))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("BOOK_UNAVAILABLE"));
        assertThatThrownBy(() -> stock.reserve(orderId(), List.of(new Stock.Line(999_999, 1))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("BOOK_UNAVAILABLE"));
    }

    @Test
    void quantitiesAndOrderSizeAreChecked() {
        assertThatThrownBy(() -> stock.reserve(orderId(), List.of(new Stock.Line(1, 0))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> stock.reserve(orderId(), List.of(new Stock.Line(1, 11))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> stock.reserve(orderId(), List.of()))
                .isInstanceOf(ApiException.class);
        List<Stock.Line> tooMany = IntStream.rangeClosed(1, 31).mapToObj(id -> new Stock.Line(id, 1)).toList();
        assertThatThrownBy(() -> stock.reserve(orderId(), tooMany))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("between 1 and 30"));
    }

    private int stock(Books.Book book) {
        return books.byId(book.id()).orElseThrow().stock();
    }

    private static String orderId() {
        return UUID.randomUUID().toString();
    }
}
