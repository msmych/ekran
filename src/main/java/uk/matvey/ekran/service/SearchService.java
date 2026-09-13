package uk.matvey.ekran.service;

import java.util.List;

import uk.matvey.ekran.domain.SearchResultPage;
import uk.matvey.ekran.repository.SearchRepository;

public class SearchService {

    private static final int MAX_QUERY_LENGTH = 100;
    private static final int FIRST_PAGE = 1;

    private final SearchRepository repository;

    public SearchService(SearchRepository repository) {
        this.repository = repository;
    }

    public SearchResultPage search(String query) {
        var normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_QUERY_LENGTH) {
            return new SearchResultPage(List.of());
        }
        return repository.search(normalized, FIRST_PAGE);
    }
}