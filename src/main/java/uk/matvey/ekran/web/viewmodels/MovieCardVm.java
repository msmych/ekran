package uk.matvey.ekran.web.viewmodels;

import java.util.List;
import java.util.stream.Collectors;

import uk.matvey.ekran.domain.FilmographyItem;
import uk.matvey.ekran.domain.Movie;
import uk.matvey.ekran.domain.PersonLink;

public record MovieCardVm(
    long tmdbId,
    String title,
    Integer year,
    String meta,
    String originalTitle,
    String directors,
    String posterUrl
) {

    public static MovieCardVm of(Movie movie) {
        return new MovieCardVm(
            movie.tmdbId(),
            movie.title(),
            movie.releaseDate() == null ? null : movie.releaseDate().getYear(),
            MovieDetailVm.runtime(movie.runtimeMinutes()),
            movie.originalTitle(),
            directors(movie.directors()),
            movie.posterUrl() == null ? null : movie.posterUrl().toString()
        );
    }

    public static MovieCardVm of(FilmographyItem item) {
        // duration and credits are not part of TMDB's person-credits payload, and
        // fetching them per credit would cost one detail call per movie
        return new MovieCardVm(
            item.movieTmdbId(),
            item.title(),
            item.year(),
            null,
            null,
            null,
            item.posterUrl() == null ? null : item.posterUrl().toString()
        );
    }

    private static String directors(List<PersonLink> directors) {
        if (directors == null || directors.isEmpty()) {
            return null;
        }
        return directors.stream().map(PersonLink::name).collect(Collectors.joining(", "));
    }
}