package uk.matvey.ekran.web.viewmodels;

import uk.matvey.ekran.domain.FilmographyItem;

public record FilmographyItemVm(
    String title,
    Integer year,
    String role,
    String href
) {

    public static FilmographyItemVm of(FilmographyItem item) {
        return new FilmographyItemVm(
            item.title(),
            item.year(),
            item.role(),
            "/movies/" + item.movieTmdbId()
        );
    }
}