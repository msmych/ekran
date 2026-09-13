package uk.matvey.ekran.web.viewmodels;

import org.junit.jupiter.api.Test;

import uk.matvey.ekran.domain.Movie;
import uk.matvey.ekran.domain.MovieVideo;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;

class MovieDetailVmTest {

    @Test
    void officialTrailerInOriginalLanguageWins() {
        var ranked = MovieDetailVm.rankVideos(List.of(
            video("enTrailer", "Trailer", true, "en", "2020-01-01T00:00:00Z"),
            video("jaTrailer", "Trailer", true, "ja", "2020-01-01T00:00:00Z"),
            video("enTeaser", "Teaser", true, "en", "2021-01-01T00:00:00Z")
        ), "ja");

        assertThat(ranked).extracting(MovieVideo::key).containsExactly("jaTrailer", "enTrailer", "enTeaser");
    }

    @Test
    void trailersRankAboveTeasersAndOtherTypes() {
        var ranked = MovieDetailVm.rankVideos(List.of(
            video("clip", "Clip", true, "en", "2023-01-01T00:00:00Z"),
            video("teaser", "Teaser", true, "en", "2023-01-01T00:00:00Z"),
            video("trailer", "Trailer", true, "en", "2023-01-01T00:00:00Z")
        ), "en");

        assertThat(ranked).extracting(MovieVideo::key).containsExactly("trailer", "teaser", "clip");
    }

    @Test
    void officialBeatsNonOfficialWithinSameTypeAndLanguage() {
        var ranked = MovieDetailVm.rankVideos(List.of(
            video("fanMade", "Trailer", false, "en", "2023-01-01T00:00:00Z"),
            video("official", "Trailer", true, "en", "2020-01-01T00:00:00Z")
        ), "en");

        assertThat(ranked).extracting(MovieVideo::key).containsExactly("official", "fanMade");
    }

    @Test
    void newestPublishedAtBreaksTies() {
        var ranked = MovieDetailVm.rankVideos(List.of(
            video("older", "Trailer", true, "en", "2020-01-01T00:00:00Z"),
            video("newer", "Trailer", true, "en", "2022-01-01T00:00:00Z"),
            video("undated", "Trailer", true, "en", null)
        ), "en");

        assertThat(ranked).extracting(MovieVideo::key).containsExactly("newer", "older", "undated");
    }

    @Test
    void nullLanguageRanksBelowEnglishAndTiesWithUnknownLanguages() {
        var ranked = MovieDetailVm.rankVideos(List.of(
            video("nullLang", "Trailer", true, null, "2020-01-01T00:00:00Z"),
            video("french", "Trailer", true, "fr", "2020-01-01T00:00:00Z"),
            video("english", "Trailer", true, "en", "2020-01-01T00:00:00Z")
        ), "ja");

        // en ranks above null/unknown languages; null and fr tie (stable input order)
        assertThat(ranked).extracting(MovieVideo::key).containsExactly("english", "nullLang", "french");
    }

    @Test
    void movieWithoutVideosYieldsEmptyList() {
        var vm = MovieDetailVm.of(new Movie(
            1, "Movie", null, null, null, List.of(), null, null, null, null,
            List.of(), List.of(), List.of(), "en", List.of()
        ));

        assertThat(vm.videos()).isEmpty();
    }

    @Test
    void videosAreRankedBestFirst() {
        var vm = MovieDetailVm.of(new Movie(
            1, "Movie", null, null, null, List.of(), null, null, null, null,
            List.of(), List.of(), List.of(), "ja",
            List.of(
                video("enTrailer", "Trailer", true, "en", "2020-01-01T00:00:00Z"),
                video("jaTrailer", "Trailer", true, "ja", "2020-01-01T00:00:00Z"),
                video("jaTeaser", "Teaser", true, "ja", "2020-01-01T00:00:00Z")
            )
        ));

        assertThat(vm.videos()).extracting(MovieDetailVm.VideoVm::key).containsExactly("jaTrailer", "enTrailer", "jaTeaser");
    }

    private static MovieVideo video(String key, String type, boolean official, String language, String publishedAt) {
        return new MovieVideo(key, "Video " + key, type, official, language, publishedAt);
    }
}