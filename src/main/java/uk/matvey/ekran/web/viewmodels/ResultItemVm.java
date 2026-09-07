package uk.matvey.ekran.web.viewmodels;

import uk.matvey.ekran.domain.SearchResult;

public record ResultItemVm(
    String href,
    String title,
    Integer year,
    String subtitle,
    String imageUrl
) {

    public static ResultItemVm of(SearchResult result) {
        var href = switch (result.type()) {
            case MOVIE -> "/movies/" + result.tmdbId();
            case PERSON -> "/persons/" + result.tmdbId();
        };
        return new ResultItemVm(
            href,
            result.title(),
            result.year(),
            result.subtitle(),
            result.thumbUrl() == null ? null : result.thumbUrl().toString()
        );
    }
}