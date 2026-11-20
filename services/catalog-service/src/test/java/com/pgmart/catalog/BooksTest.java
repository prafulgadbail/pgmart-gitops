package com.pgmart.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The catalog SQL against a real PostgreSQL with the seeded books. */
class BooksTest {

    private final Books books = new Books(PostgresForTests.jdbcClient());

    @Test
    void seededTopicsAndBooksAreThere() {
        assertThat(books.topics()).extracting(Books.Topic::key).startsWith("Cloud", "VCS", "CI/CD");
        assertThat(books.topicExists("Containers")).isTrue();
        assertThat(books.topicExists("Cooking")).isFalse();

        Books.Book aws = books.byId(1).orElseThrow();
        assertThat(aws.title()).isEqualTo("AWS for DevOps");
        assertThat(aws.highlights()).hasSize(3);
        assertThat(books.all()).hasSizeGreaterThanOrEqualTo(79);
    }

    @Test
    void searchFindsByTextAndTopic() {
        Books.Book book = books.create(request("Zebra Pipelines Handbook", "Testing", 7, true));

        assertThat(books.search("  zebra PIPELINES ", null)).extracting(Books.Book::id).containsExactly(book.id());
        assertThat(books.search(null, "Testing")).extracting(Books.Book::id).contains(book.id());
        assertThat(books.search("zebra", "Cloud")).isEmpty();
        assertThat(books.search("", " ")).isNotEmpty();
    }

    @Test
    void aBookIsCreatedUpdatedAndItsStockChanged() {
        Books.Book created = books.create(new AdminBookController.BookRequest(" Created Book ", " A subtitle ", "IaC",
                "terraform", "#5C4EE5", "#1a1450", 99900, 59900, 12, 300, List.of(" One ", "", "Two"), " ", null));
        assertThat(created.title()).isEqualTo("Created Book");
        assertThat(created.highlights()).containsExactly("One", "Two");
        assertThat(created.imageUrl()).isNull();
        assertThat(created.active()).isTrue();

        Books.Book updated = books.update(created.id(), new AdminBookController.BookRequest("Updated Book", "New",
                "IaC", "terraform", "#5C4EE5", "#1a1450", 99900, 49900, 3, 320, null,
                "https://example.com/cover.png", false)).orElseThrow();
        assertThat(updated.title()).isEqualTo("Updated Book");
        assertThat(updated.highlights()).isEmpty();
        assertThat(updated.imageUrl()).isEqualTo("https://example.com/cover.png");
        assertThat(updated.active()).isFalse();

        assertThat(books.setStock(created.id(), 40).orElseThrow().stock()).isEqualTo(40);
        assertThat(books.setActive(created.id(), true).orElseThrow().active()).isTrue();
        assertThat(books.update(999_999, request("Missing", "IaC", 1, true))).isEmpty();
        assertThat(books.setStock(999_999, 1)).isEmpty();
        assertThat(books.setActive(999_999, true)).isEmpty();
    }

    @Test
    void lowStockCountsOnlyActiveBooksAtOrUnderTheLimit() {
        int before = books.lowStock(2);

        books.create(request("Almost Gone", "Data", 1, true));
        books.create(request("Hidden And Almost Gone", "Data", 1, false));

        assertThat(books.lowStock(2)).isEqualTo(before + 1);
    }

    @Test
    void theWishlistKeepsEachBookOnceAndHidesInactiveBooks() {
        Books.Book shown = books.create(request("Wished Book", "Data", 5, true));
        Books.Book hidden = books.create(request("Hidden Wished Book", "Data", 5, false));

        books.addToWishlist(42, shown.id());
        books.addToWishlist(42, shown.id());
        books.addToWishlist(42, hidden.id());
        assertThat(books.wishlist(42)).containsExactly(shown.id());

        books.removeFromWishlist(42, shown.id());
        assertThat(books.wishlist(42)).isEmpty();
    }

    static AdminBookController.BookRequest request(String title, String topic, int stock, boolean active) {
        return new AdminBookController.BookRequest(title, "Test subtitle", topic, "logo", "#000000", "#ffffff",
                99900, 59900, stock, 200, List.of("A highlight"), "", active);
    }
}
