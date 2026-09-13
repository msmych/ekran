package uk.matvey.ekran.tmdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import uk.matvey.ekran.config.AppConfig;
import uk.matvey.ekran.domain.Department;
import uk.matvey.ekran.tmdb.dto.MovieDetailResponse;
import uk.matvey.ekran.tmdb.dto.MovieSearchResponse;
import uk.matvey.ekran.tmdb.dto.PersonDetailResponse;

import static org.assertj.core.api.Assertions.assertThat;

class TmdbMapperTest {

    private static final AppConfig CONFIG = new AppConfig(
        "test-token", 7070,
        "https://api.themoviedb.org/3", "https://image.tmdb.org/t/p",
        2000, 3000, 5000
    );

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TmdbMapper mapper = new TmdbMapper(CONFIG);

    @Test
    void mapsMovieSearchResults() throws Exception {
        var response = objectMapper.readValue("""
            {
              "page": 1,
              "results": [
                {"id": 348, "title": "Alien", "original_title": "Alien",
                 "release_date": "1979-05-25", "poster_path": "/abc.jpg", "vote_average": 8.2},
                {"id": 9471, "title": "Aliens", "release_date": "", "poster_path": null},
                {"id": 1000, "title": "Wings of Desire", "original_title": "Der Himmel über Berlin",
                 "release_date": "1987-09-16", "poster_path": null, "vote_average": 8.0}
              ]
            }
            """, MovieSearchResponse.class);

        var page = mapper.toSearchResults(response);

        assertThat(page.results()).hasSize(3);
        var first = page.results().get(0);
        assertThat(first.tmdbId()).isEqualTo(348);
        assertThat(first.title()).isEqualTo("Alien");
        assertThat(first.year()).isEqualTo(1979);
        assertThat(first.subtitle()).isEqualTo("8.2");
        assertThat(first.thumbUrl()).hasToString("https://image.tmdb.org/t/p/w92/abc.jpg");

        var second = page.results().get(1);
        assertThat(second.year()).isNull();
        assertThat(second.subtitle()).isNull();
        assertThat(second.thumbUrl()).isNull();

        var third = page.results().get(2);
        assertThat(third.title()).isEqualTo("Wings of Desire");
        assertThat(third.originalTitle()).isEqualTo("Der Himmel über Berlin");
    }

    @Test
    void mapsMovieDetailWithCredits() throws Exception {
        var response = objectMapper.readValue("""
            {
              "id": 348, "title": "Alien", "original_title": "Alien",
              "overview": "ov", "release_date": "1979-05-25", "runtime": 117,
              "vote_average": 8.2, "poster_path": "/p.jpg", "backdrop_path": "/b.jpg",
              "genres": [{"id": 18, "name": "Science Fiction"}, {"id": 27, "name": "Horror"}],
              "credits": {
                "crew": [
                  {"id": 1, "name": "Ridley Scott", "job": "Director", "department": "Directing"},
                  {"id": 2, "name": "Dan O'Bannon", "job": "Screenplay", "department": "Writing"},
                  {"id": 3, "name": "Ronald Shusett", "job": "Story", "department": "Writing"},
                  {"id": 1, "name": "Ridley Scott", "job": "Producer", "department": "Production"}
                ],
                "cast": [
                  {"id": 4, "name": "Tom Skerritt", "character": "Dallas", "order": 1},
                  {"id": 5, "name": "Sigourney Weaver", "character": "Ripley", "order": 0}
                ]
              }
            }
            """, MovieDetailResponse.class);

        var movie = mapper.toMovie(response);

        assertThat(movie.title()).isEqualTo("Alien");
        assertThat(movie.originalTitle()).isNull();
        assertThat(movie.releaseDate()).hasToString("1979-05-25");
        assertThat(movie.runtimeMinutes()).isEqualTo(117);
        assertThat(movie.genres()).containsExactly("Science Fiction", "Horror");
        assertThat(movie.posterUrl()).hasToString("https://image.tmdb.org/t/p/w342/p.jpg");
        assertThat(movie.backdropUrl()).hasToString("https://image.tmdb.org/t/p/w780/b.jpg");
        assertThat(movie.directors()).hasSize(1);
        assertThat(movie.directors().get(0).name()).isEqualTo("Ridley Scott");
        assertThat(movie.directors().get(0).department()).isEqualTo(Department.DIRECTING);
        assertThat(movie.writers()).extracting(w -> w.name() + ":" + w.role())
            .containsExactly("Dan O'Bannon:Screenplay", "Ronald Shusett:Story");
        assertThat(movie.cast()).extracting(c -> c.name())
            .containsExactly("Sigourney Weaver", "Tom Skerritt");
        assertThat(movie.cast().get(0).role()).isEqualTo("Ripley");
    }

    @Test
    void mapsYoutubeVideosAndFiltersOtherSites() throws Exception {
        var response = objectMapper.readValue("""
            {
              "id": 348, "title": "Alien",
              "original_language": "en",
              "videos": {
                "results": [
                  {"key": "trailerKey", "name": "Official Trailer", "site": "YouTube",
                   "type": "Trailer", "official": true, "iso_639_1": "en",
                   "published_at": "1979-04-01T00:00:00Z"},
                  {"key": "vimeoKey", "name": "Vimeo Trailer", "site": "Vimeo",
                   "type": "Trailer", "official": true, "iso_639_1": "en",
                   "published_at": "1979-04-01T00:00:00Z"}
                ]
              }
            }
            """, MovieDetailResponse.class);

        var movie = mapper.toMovie(response);

        assertThat(movie.originalLanguage()).isEqualTo("en");
        assertThat(movie.videos()).hasSize(1);
        var video = movie.videos().get(0);
        assertThat(video.key()).isEqualTo("trailerKey");
        assertThat(video.name()).isEqualTo("Official Trailer");
        assertThat(video.type()).isEqualTo("Trailer");
        assertThat(video.official()).isTrue();
        assertThat(video.language()).isEqualTo("en");
        assertThat(video.publishedAt()).isEqualTo("1979-04-01T00:00:00Z");
    }

    @Test
    void missingVideosYieldEmptyList() throws Exception {
        var response = objectMapper.readValue("""
            {"id": 348, "title": "Alien"}
            """, MovieDetailResponse.class);

        assertThat(mapper.toMovie(response).videos()).isEmpty();
        assertThat(mapper.toMovie(response).originalLanguage()).isNull();
    }

    @Test
    void mapsMovieDetailWithMissingFields() throws Exception {
        var response = objectMapper.readValue("""
            {"id": 1, "title": "Untitled", "overview": null}
            """, MovieDetailResponse.class);

        var movie = mapper.toMovie(response);

        assertThat(movie.releaseDate()).isNull();
        assertThat(movie.runtimeMinutes()).isNull();
        assertThat(movie.genres()).isEmpty();
        assertThat(movie.posterUrl()).isNull();
        assertThat(movie.directors()).isEmpty();
        assertThat(movie.writers()).isEmpty();
        assertThat(movie.cast()).isEmpty();
    }

    @Test
    void mapsPersonWithFilmography() throws Exception {
        var response = objectMapper.readValue("""
            {
              "id": 1, "name": "Ridley Scott", "known_for_department": "Directing",
              "biography": "bio", "profile_path": "/r.jpg",
              "movie_credits": {
                "crew": [
                  {"id": 348, "title": "Alien", "job": "Director", "department": "Directing", "release_date": "1979-05-25"},
                  {"id": 68, "title": "Alien: Covenant", "job": "Director", "department": "Directing", "release_date": "2017-05-19"},
                  {"id": 400, "title": "Blade Runner", "job": "Director", "department": "Directing", "release_date": "1982-06-25"},
                  {"id": 999, "title": "Untitled", "job": "Writer", "department": "Writing", "release_date": ""}
                ],
                "cast": [
                  {"id": 500, "title": "Some Cameo", "character": "Himself", "order": 0, "release_date": "1990-01-01"}
                ]
              }
            }
            """, PersonDetailResponse.class);

        var person = mapper.toPerson(response);

        assertThat(person.name()).isEqualTo("Ridley Scott");
        assertThat(person.knownFor()).isEqualTo(Department.DIRECTING);
        assertThat(person.profileUrl()).hasToString("https://image.tmdb.org/t/p/h632/r.jpg");
        assertThat(person.filmography().directing())
            .extracting(f -> f.title() + ":" + f.year())
            .containsExactly("Alien: Covenant:2017", "Blade Runner:1982", "Alien:1979");
        assertThat(person.filmography().writing())
            .extracting(f -> f.title() + ":" + f.year() + ":" + f.role())
            .containsExactly("Untitled:null:Writer");
        assertThat(person.filmography().acting())
            .extracting(f -> f.title() + ":" + f.role())
            .containsExactly("Some Cameo:Himself");
    }

    @Test
    void mapsUnknownKnownForDepartmentToOther() throws Exception {
        var response = objectMapper.readValue("""
            {"id": 2, "name": "Producer Person", "known_for_department": "Production"}
            """, PersonDetailResponse.class);

        assertThat(mapper.toPerson(response).knownFor()).isEqualTo(Department.OTHER);
    }

    @Test
    void toleratesMalformedDates() throws Exception {
        var response = objectMapper.readValue("""
            {"id": 5, "title": "Broken", "release_date": "not-a-date", "credits": {"crew": [], "cast": []}}
            """, MovieDetailResponse.class);

        assertThat(mapper.toMovie(response).releaseDate()).isNull();
    }

    @Test
    void mapsNullSearchResponseToEmptyPage() {
        assertThat(mapper.toSearchResults(null).results()).isEmpty();
    }
}