package uk.matvey.ekran.web.viewmodels;

import uk.matvey.ekran.domain.SearchResultPage;

import java.util.List;

public record SearchResultsVm(
    String query,
    List<ResultItemVm> items
) {

    public static SearchResultsVm of(String query, SearchResultPage page) {
        var items = page.results().stream().map(ResultItemVm::of).toList();
        return new SearchResultsVm(query == null ? "" : query, items);
    }
}