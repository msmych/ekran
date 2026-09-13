package uk.matvey.ekran.tmdb;

import static java.util.List.of;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import uk.matvey.ekran.config.AppConfig;
import uk.matvey.ekran.domain.Department;
import uk.matvey.ekran.domain.Filmography;
import uk.matvey.ekran.domain.FilmographyItem;
import uk.matvey.ekran.domain.Movie;
import uk.matvey.ekran.domain.MovieVideo;
import uk.matvey.ekran.domain.Person;
import uk.matvey.ekran.domain.PersonLink;
import uk.matvey.ekran.domain.SearchResult;
import uk.matvey.ekran.domain.SearchResultPage;
import uk.matvey.ekran.domain.SearchType;
import uk.matvey.ekran.tmdb.dto.CastCredit;
import uk.matvey.ekran.tmdb.dto.CreditsResponse;
import uk.matvey.ekran.tmdb.dto.CrewCredit;
import uk.matvey.ekran.tmdb.dto.Genre;
import uk.matvey.ekran.tmdb.dto.MovieDetailResponse;
import uk.matvey.ekran.tmdb.dto.MovieSearchItem;
import uk.matvey.ekran.tmdb.dto.MovieSearchResponse;
import uk.matvey.ekran.tmdb.dto.PersonDetailResponse;
import uk.matvey.ekran.tmdb.dto.PersonMovieCreditsResponse;

public class TmdbMapper {

    private static final int PRINCIPAL_CAST_LIMIT = 8;

    private final String imageBaseUrl;

    public TmdbMapper(AppConfig config) {
        this.imageBaseUrl = config.imageBaseUrl();
    }

    public SearchResultPage toSearchResults(MovieSearchResponse response) {
        if (response == null) {
            return new SearchResultPage(of());
        }
        var items = orEmpty(response.results());
        return new SearchResultPage(items.stream().map(this::toSearchResult).toList());
    }

    public Movie toMovie(MovieDetailResponse response) {
        var credits = response.credits() == null
            ? new CreditsResponse(of(), of())
            : response.credits();
        var crew = orEmpty(credits.crew());
        var cast = orEmpty(credits.cast());
        return new Movie(
            response.id(),
            response.title(),
            originalTitle(response.title(), response.originalTitle()),
            localDate(response.releaseDate()),
            response.runtime(),
            genres(response),
            response.voteAverage(),
            response.overview(),
            imageUrl("w342", response.posterPath()),
            imageUrl("w780", response.backdropPath()),
            directors(crew),
            writers(crew),
            principalCast(cast),
            response.originalLanguage(),
            videos(response)
        );
    }

    private List<MovieVideo> videos(MovieDetailResponse response) {
        var results = response.videos() == null ? null : response.videos().results();
        return orEmpty(results).stream()
            .filter(v -> "YouTube".equalsIgnoreCase(v.site()) && v.key() != null)
            .map(v -> new MovieVideo(
                v.key(),
                v.name(),
                v.type(),
                Boolean.TRUE.equals(v.official()),
                v.iso6391(),
                v.publishedAt()))
            .toList();
    }

    public Person toPerson(PersonDetailResponse response) {
        var credits = response.movieCredits() == null
            ? new PersonMovieCreditsResponse(of(), of())
            : response.movieCredits();
        var crew = orEmpty(credits.crew());
        var cast = orEmpty(credits.cast());
        return new Person(
            response.id(),
            response.name(),
            Department.fromTmdb(response.knownForDepartment()),
            response.biography(),
            imageUrl("h632", response.profilePath()),
            new Filmography(
                filmography(crew, "Directing", CrewCredit::job),
                filmography(crew, "Writing", CrewCredit::job),
                filmographyFromCast(cast)
            )
        );
    }

    private SearchResult toSearchResult(MovieSearchItem item) {
        return new SearchResult(
            item.id(),
            SearchType.MOVIE,
            item.title(),
            item.originalTitle(),
            year(item.releaseDate()),
            ratingSubtitle(item.voteAverage()),
            imageUrl("w92", item.posterPath())
        );
    }

    private List<PersonLink> directors(List<CrewCredit> crew) {
        return crew.stream()
            .filter(c -> "Director".equals(c.job()))
            .map(c -> new PersonLink(c.id(), c.name(), c.job(), Department.DIRECTING))
            .toList();
    }

    private List<PersonLink> writers(List<CrewCredit> crew) {
        return crew.stream()
            .filter(c -> "Writing".equals(c.department()))
            .map(c -> new PersonLink(c.id(), c.name(), c.job(), Department.WRITING))
            .toList();
    }

    private List<PersonLink> principalCast(List<CastCredit> cast) {
        return cast.stream()
            .sorted(Comparator.comparing(CastCredit::order, Comparator.nullsLast(Comparator.naturalOrder())))
            .limit(PRINCIPAL_CAST_LIMIT)
            .map(c -> new PersonLink(c.id(), c.name(), c.character(), Department.ACTING))
            .toList();
    }

    private List<FilmographyItem> filmography(List<CrewCredit> crew, String department, Function<CrewCredit, String> role) {
        return sortedByYear(crew.stream()
            .filter(c -> department.equals(c.department()))
            .map(c -> new FilmographyItem(c.id(), c.title(), year(c.releaseDate()), role.apply(c)))
            .toList());
    }

    private List<FilmographyItem> filmographyFromCast(List<CastCredit> cast) {
        return sortedByYear(cast.stream()
            .map(c -> new FilmographyItem(c.id(), c.title(), year(c.releaseDate()), c.character()))
            .toList());
    }

    private List<FilmographyItem> sortedByYear(List<FilmographyItem> items) {
        return items.stream()
            .sorted(Comparator.comparing(FilmographyItem::year, Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
            .toList();
    }

    private List<String> genres(MovieDetailResponse response) {
        return response.genres() == null ? of() : response.genres().stream().map(Genre::name).toList();
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? of() : list;
    }

    private String originalTitle(String title, String originalTitle) {
        return originalTitle == null || originalTitle.isBlank() || originalTitle.equals(title) ? null : originalTitle;
    }

    private String ratingSubtitle(Double voteAverage) {
        return voteAverage == null || voteAverage <= 0 ? null : String.format(Locale.ROOT, "%.1f", voteAverage);
    }

    private Integer year(String releaseDate) {
        var date = localDate(releaseDate);
        return date == null ? null : date.getYear();
    }

    private LocalDate localDate(String releaseDate) {
        if (releaseDate == null || releaseDate.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(releaseDate);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private URI imageUrl(String size, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return URI.create(imageBaseUrl + "/" + size + path);
    }
}