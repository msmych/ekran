package uk.matvey.ekran.web.viewmodels;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import uk.matvey.ekran.domain.Movie;
import uk.matvey.ekran.domain.MovieVideo;

public record MovieDetailVm(
    String title,
    String originalTitle,
    Integer year,
    String runtime,
    String rating,
    String genres,
    String overview,
    String posterUrl,
    String backdropUrl,
    List<PersonLinkVm> directors,
    List<PersonLinkVm> writers,
    List<PersonLinkVm> cast,
    String tmdbUrl,
    List<VideoVm> videos
) {

    public record VideoVm(
        String key,
        String name,
        String type
    ) {
    }

    public static MovieDetailVm of(Movie movie) {
        return new MovieDetailVm(
            movie.title(),
            movie.originalTitle(),
            movie.releaseDate() == null ? null : movie.releaseDate().getYear(),
            runtime(movie.runtimeMinutes()),
            rating(movie.rating()),
            movie.genres().isEmpty() ? null : String.join(", ", movie.genres()),
            movie.overview(),
            movie.posterUrl() == null ? null : movie.posterUrl().toString(),
            movie.backdropUrl() == null ? null : movie.backdropUrl().toString(),
            movie.directors().stream().map(d -> PersonLinkVm.of(d, "/directing")).toList(),
            movie.writers().stream().map(w -> PersonLinkVm.of(w, "/writing")).toList(),
            movie.cast().stream().map(c -> PersonLinkVm.of(c, "/acting")).toList(),
            "https://www.themoviedb.org/movie/" + movie.tmdbId(),
            rankVideos(movie.videos(), movie.originalLanguage()).stream().map(MovieDetailVm::toVideoVm).toList()
        );
    }

    private static VideoVm toVideoVm(MovieVideo video) {
        return new VideoVm(video.key(), video.name(), video.type());
    }

    static List<MovieVideo> rankVideos(List<MovieVideo> videos, String originalLanguage) {
        return videos.stream()
            .sorted(
                Comparator.comparingInt((MovieVideo v) -> typeRank(v.type()))
                    .thenComparingInt(v -> v.official() ? 0 : 1)
                    .thenComparingInt(v -> languageRank(v.language(), originalLanguage))
                    .thenComparing(v -> v.publishedAt() == null ? "" : v.publishedAt(), Comparator.reverseOrder()))
            .toList();
    }

    private static int typeRank(String type) {
        return "Trailer".equals(type) ? 0 : "Teaser".equals(type) ? 1 : 2;
    }

    private static int languageRank(String language, String originalLanguage) {
        if (language == null || language.isBlank()) {
            return 2;
        }
        if (language.equalsIgnoreCase(originalLanguage)) {
            return 0;
        }
        return "en".equalsIgnoreCase(language) ? 1 : 2;
    }

    private static String runtime(Integer minutes) {
        if (minutes == null || minutes <= 0) {
            return null;
        }
        return "%dh %02dm".formatted(minutes / 60, minutes % 60);
    }

    private static String rating(Double value) {
        if (value == null || value <= 0) {
            return null;
        }
        return String.format(Locale.ROOT, "%.1f ★", value);
    }
}