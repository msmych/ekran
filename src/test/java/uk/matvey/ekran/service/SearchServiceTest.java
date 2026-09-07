package uk.matvey.ekran.service;

import org.junit.jupiter.api.Test;

import uk.matvey.ekran.domain.SearchResult;
import uk.matvey.ekran.domain.SearchResultPage;
import uk.matvey.ekran.domain.SearchType;
import uk.matvey.ekran.domain.TmdbUnavailableException;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.List.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchServiceTest {

    @Test
    void normalizesQueryBeforeCallingRepository() {
        var capturedQuery = new AtomicReference<String>();
        var capturedPage = new AtomicInteger();
        var service = new SearchService((query, page) -> {
            capturedQuery.set(query);
            capturedPage.set(page);
            return new SearchResultPage(of());
        });

        service.search("  alien  ");

        assertThat(capturedQuery.get()).isEqualTo("alien");
        assertThat(capturedPage.get()).isEqualTo(1);
    }

    @Test
    void blankQueryReturnsEmptyPageWithoutCallingRepository() {
        var calls = new AtomicInteger();
        var service = countingService(calls);

        assertThat(service.search(null).results()).isEmpty();
        assertThat(service.search("").results()).isEmpty();
        assertThat(service.search("   ").results()).isEmpty();
        assertThat(calls.get()).isZero();
    }

    @Test
    void oversizedQueryReturnsEmptyPageWithoutCallingRepository() {
        var calls = new AtomicInteger();
        var service = countingService(calls);

        assertThat(service.search("x".repeat(101)).results()).isEmpty();
        assertThat(calls.get()).isZero();
    }

    @Test
    void passesResultsThrough() {
        var service = new SearchService((q, p) ->
            new SearchResultPage(of(new SearchResult(1, SearchType.MOVIE, "Alien", 1979, null, null))));

        var page = service.search("alien");

        assertThat(page.results()).hasSize(1);
        assertThat(page.results().get(0).title()).isEqualTo("Alien");
    }

    @Test
    void propagatesRepositoryFailure() {
        var service = new SearchService((q, p) -> {
            throw new TmdbUnavailableException("boom");
        });

        assertThatThrownBy(() -> service.search("alien"))
            .isInstanceOf(TmdbUnavailableException.class);
    }

    private static SearchService countingService(AtomicInteger calls) {
        return new SearchService((query, page) -> {
            calls.incrementAndGet();
            return new SearchResultPage(of());
        });
    }
}