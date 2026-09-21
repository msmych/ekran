package uk.matvey.ekran.service;

import org.junit.jupiter.api.Test;

import uk.matvey.ekran.domain.SearchResult;
import uk.matvey.ekran.domain.SearchResultPage;
import uk.matvey.ekran.domain.SearchType;
import uk.matvey.ekran.domain.TmdbUnavailableException;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;

class SearchServiceTest {

    @Test
    void normalizesQueryBeforeCallingRepository() {
        var capturedQuery = new AtomicReference<String>();
        var capturedPage = new AtomicInteger();
        var service = new SearchService((query, page, type) -> {
            capturedQuery.set(query);
            capturedPage.set(page);
            return new SearchResultPage(List.of());
        });

        service.search("  alien  ", SearchType.MOVIE);

        assertThat(capturedQuery.get()).isEqualTo("alien");
        assertThat(capturedPage.get()).isEqualTo(1);
    }

    @Test
    void passesTypeThroughToRepository() {
        var capturedType = new AtomicReference<SearchType>();
        var service = new SearchService((query, page, type) -> {
            capturedType.set(type);
            return new SearchResultPage(List.of());
        });

        service.search("ridley", SearchType.PERSON);

        assertThat(capturedType.get()).isEqualTo(SearchType.PERSON);
    }

    @Test
    void blankQueryReturnsEmptyPageWithoutCallingRepository() {
        var calls = new AtomicInteger();
        var service = countingService(calls);

        assertThat(service.search(null, SearchType.MOVIE).results()).isEmpty();
        assertThat(service.search("", SearchType.PERSON).results()).isEmpty();
        assertThat(service.search("   ", SearchType.MOVIE).results()).isEmpty();
        assertThat(calls.get()).isZero();
    }

    @Test
    void oversizedQueryReturnsEmptyPageWithoutCallingRepository() {
        var calls = new AtomicInteger();
        var service = countingService(calls);

        assertThat(service.search("x".repeat(101), SearchType.PERSON).results()).isEmpty();
        assertThat(calls.get()).isZero();
    }

    @Test
    void passesResultsThrough() {
        var service = new SearchService((q, p, t) ->
            new SearchResultPage(List.of(new SearchResult(1, SearchType.MOVIE, "Alien", null, 1979, null, null))));

        var page = service.search("alien", SearchType.MOVIE);

        assertThat(page.results()).hasSize(1);
        assertThat(page.results().get(0).title()).isEqualTo("Alien");
    }

    @Test
    void propagatesRepositoryFailure() {
        var service = new SearchService((q, p, t) -> {
            throw new TmdbUnavailableException("boom");
        });

        assertThatThrownBy(() -> service.search("alien", SearchType.MOVIE))
            .isInstanceOf(TmdbUnavailableException.class);
    }

    private static SearchService countingService(AtomicInteger calls) {
        return new SearchService((query, page, type) -> {
            calls.incrementAndGet();
            return new SearchResultPage(List.of());
        });
    }
}