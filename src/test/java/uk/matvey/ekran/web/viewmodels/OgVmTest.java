package uk.matvey.ekran.web.viewmodels;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.matvey.ekran.domain.Movie;

class OgVmTest {

    private static final Movie MOVIE = new Movie(
        348, "Alien", null, LocalDate.parse("1979-05-25"), 117,
        List.of("Horror"), "In space no one can hear you scream.",
        URI.create("https://img/poster.jpg"), URI.create("https://img/backdrop.jpg"),
        List.of(), List.of(), List.of(), "en", List.of()
    );

    private static final Movie NO_BACKDROP = new Movie(
        9471, "Aliens", null, LocalDate.parse("1986-07-18"), 137,
        List.of("Action"), "This time it's war.",
        URI.create("https://img/poster.jpg"), null,
        List.of(), List.of(), List.of(), "en", List.of()
    );

    @Test
    void movieOgHasTitleWithYearBackdropImageAndCanonicalUrl() {
        var og = OgVm.movie(MovieDetailVm.of(MOVIE), "https://ekran.uk/movies/348");

        assertThat(og.title()).isEqualTo("Alien (1979)");
        assertThat(og.description()).isEqualTo("In space no one can hear you scream.");
        assertThat(og.image()).isEqualTo("https://img/backdrop.jpg");
        assertThat(og.url()).isEqualTo("https://ekran.uk/movies/348");
        assertThat(og.type()).isEqualTo("video.movie");
        assertThat(og.siteName()).isEqualTo("ekran");
    }

    @Test
    void withoutBackdropImageFallsBackToPoster() {
        var og = OgVm.movie(MovieDetailVm.of(NO_BACKDROP), "https://ekran.uk/movies/9471");

        assertThat(og.image()).isEqualTo("https://img/poster.jpg");
    }

    @Test
    void longOverviewIsTruncatedAt300Chars() {
        var movie = new Movie(
            1, "Long", null, LocalDate.parse("2000-01-01"), 100,
            List.of(), "x".repeat(500),
            null, null, List.of(), List.of(), List.of(), "en", List.of()
        );
        var og = OgVm.movie(MovieDetailVm.of(movie), "https://ekran.uk/movies/1");

        assertThat(og.description()).hasSize(300).endsWith("…");
    }

    @Test
    void titleWithoutYearHasNoYearSuffix() {
        var movie = new Movie(
            1, "Untitled", null, null, 100,
            List.of(), null, null,
            null, List.of(), List.of(), List.of(), "en", List.of()
        );
        var og = OgVm.movie(MovieDetailVm.of(movie), "https://ekran.uk/movies/1");

        assertThat(og.title()).isEqualTo("Untitled");
    }
}
