package uk.matvey.ekran.web.viewmodels;

import uk.matvey.ekran.domain.FilmographyItem;
import uk.matvey.ekran.domain.Movie;

public record MovieCardVm(
    long tmdbId,
    String title,
    Integer year,
    String meta,
    String posterUrl
) {

    public static MovieCardVm of(Movie movie) {
        return new MovieCardVm(
            movie.tmdbId(),
            movie.title(),
            movie.releaseDate() == null ? null : movie.releaseDate().getYear(),
            MovieDetailVm.runtime(movie.runtimeMinutes()),
            movie.posterUrl() == null ? null : movie.posterUrl().toString()
        );
    }

    public static MovieCardVm of(FilmographyItem item) {
        // duration is not part of TMDB's person-credits payload, and fetching
        // it per credit would cost one detail call per movie — cards show year only
        return new MovieCardVm(
            item.movieTmdbId(),
            item.title(),
            item.year(),
            null,
            item.posterUrl() == null ? null : item.posterUrl().toString()
        );
    }
}