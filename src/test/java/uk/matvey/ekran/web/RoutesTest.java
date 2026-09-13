package uk.matvey.ekran.web;

import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

import uk.matvey.ekran.domain.Department;
import uk.matvey.ekran.domain.Filmography;
import uk.matvey.ekran.domain.FilmographyItem;
import uk.matvey.ekran.domain.Movie;
import uk.matvey.ekran.domain.MovieVideo;
import uk.matvey.ekran.domain.NotFoundException;
import uk.matvey.ekran.domain.Person;
import uk.matvey.ekran.domain.PersonLink;
import uk.matvey.ekran.domain.SearchResult;
import uk.matvey.ekran.domain.SearchResultPage;
import uk.matvey.ekran.domain.SearchType;
import uk.matvey.ekran.domain.TmdbUnavailableException;
import uk.matvey.ekran.repository.MovieRepository;
import uk.matvey.ekran.repository.PersonRepository;
import uk.matvey.ekran.repository.SearchRepository;
import uk.matvey.ekran.service.MovieService;
import uk.matvey.ekran.service.PersonService;
import uk.matvey.ekran.service.SearchService;

import java.net.URI;
import java.time.LocalDate;
import java.util.Optional;

import static java.util.List.of;
import static org.assertj.core.api.Assertions.assertThat;

class RoutesTest {

    private static final SearchResult ALIEN =
        new SearchResult(348, SearchType.MOVIE, "Alien", null, 1979, "8.2", URI.create("https://img/p.jpg"));

    private static final Movie ALIEN_MOVIE = new Movie(
        348, "Alien", null, LocalDate.parse("1979-05-25"), 117,
        of("Science Fiction", "Horror"), 8.2, "In space no one can hear you scream.",
        URI.create("https://img/poster.jpg"), null,
        of(new PersonLink(1, "Ridley Scott", "Director", Department.DIRECTING)),
        of(new PersonLink(2, "Dan O'Bannon", "Screenplay", Department.WRITING)),
        of(new PersonLink(3, "Sigourney Weaver", "Ripley", Department.ACTING)),
        "en",
        of(new MovieVideo("trailerKey1", "Official Trailer", "Trailer", true, "en", "1979-04-01T00:00:00Z"),
           new MovieVideo("teaserKey1", "Teaser", "Teaser", true, "en", "1979-01-01T00:00:00Z"),
           new MovieVideo("clipKey1", "Clip: Chestburster", "Clip", false, "en", "1979-05-01T00:00:00Z"))
    );

    private static final Person RIDLEY_SCOTT = new Person(
        1, "Ridley Scott", Department.DIRECTING, "English filmmaker.",
        URI.create("https://img/profile.jpg"),
        new Filmography(
            of(new FilmographyItem(348, "Alien", 1979, "Director")),
            of(),
            of(new FilmographyItem(500, "Some Cameo", 1990, "Himself"))
        )
    );

    @Test
    void homepageRendersFocusedSearchInput() {
        JavalinTest.test(app(), (server, http) -> {
            var response = http.get("/");

            assertThat(response.code()).isEqualTo(200);
            var body = response.body().string();
            assertThat(body).contains("autofocus");
            assertThat(body).contains("class=\"home-search\"");
            assertThat(body).contains("hx-get=\"/search\"");
            assertThat(body).contains("hx-target=\"#results\"");
            assertThat(body).contains("hx-trigger=\"input changed delay:100ms, search\"");
            assertThat(body).contains("hx-sync=\"this: replace\"");
            assertThat(body).contains("hx-boost=\"true\"");
            assertThat(body).contains("not endorsed or certified by TMDB");
        });
    }

    @Test
    void homeDeepLinkWithQueryRendersResults() {
        JavalinTest.test(app(), (server, http) -> {
            var response = http.get("/?q=alien");

            assertThat(response.code()).isEqualTo(200);
            var body = response.body().string();
            assertThat(body).contains("<!DOCTYPE html>");
            assertThat(body).contains("value=\"alien\"");
            assertThat(body).contains("Alien");
            assertThat(body).contains("href=\"/movies/348\"");
        });
    }

    @Test
    void moviePageSearchBarSearchesInOverlay() {
        JavalinTest.test(app(), (server, http) -> {
            var response = http.get("/movies/348");

            assertThat(response.code()).isEqualTo(200);
            var body = response.body().string();
            assertThat(body).contains("class=\"site-search\"");
            assertThat(body).contains("id=\"search-overlay\"");
            assertThat(body).contains("hx-get=\"/search\"");
            assertThat(body).contains("hx-target=\"#search-overlay\"");
            assertThat(body).doesNotContain("autofocus");
            assertThat(body).doesNotContain("hx-push-url");
        });
    }

    @Test
    void searchHotkeysScriptLoadedOnEveryPage() {
        JavalinTest.test(app(), (server, http) -> {
            for (var path : of("/", "/movies/348", "/persons/1", "/movies/999", "/about")) {
                var body = http.get(path).body().string();
                assertThat(body).contains("src=\"/js/search.js\"");
                assertThat(body).contains("href=\"/about\"");
                assertThat(body).contains("id=\"search-kbd\"");
            }
            var script = http.get("/js/search.js");
            assertThat(script.code()).isEqualTo(200);
            var js = script.body().string();
            assertThat(js).contains("code === 'Slash'");
            assertThat(js).contains("code === 'KeyK'");
            assertThat(js).contains("i.blur()");
            assertThat(js).contains("ArrowDown");
            assertThat(js).contains("'Ctrl K'");
        });
    }

    @Test
    void aboutPageRendersWithSearchBarAndAuthorLink() {
        JavalinTest.test(app(), (server, http) -> {
            var response = http.get("/about");

            assertThat(response.code()).isEqualTo(200);
            var body = response.body().string();
            assertThat(body).contains("quickest movie search");
            assertThat(body).contains("href=\"https://matvey.uk\"");
            assertThat(body).contains("hx-target=\"#search-overlay\"");
            assertThat(body).contains("not endorsed or certified by TMDB");
        });
    }

    @Test
    void faviconsLinkedInHead() {
        JavalinTest.test(app(), (server, http) -> {
            var body = http.get("/").body().string();
            assertThat(body).contains("href=\"/favicons/favicon.ico\"");
            assertThat(body).contains("href=\"/favicons/apple-touch-icon.png\"");
            assertThat(body).contains("href=\"/favicons/site.webmanifest\"");
            assertThat(http.get("/favicons/favicon.ico").code()).isEqualTo(200);
            assertThat(http.get("/favicons/site.webmanifest").code()).isEqualTo(200);
        });
    }

    @Test
    void searchFragmentReturnsOnlyResults() {
        JavalinTest.test(app(), (server, http) -> {
            var response = http.get("/search?q=alien", request ->
                request.header("HX-Request", "true"));

            assertThat(response.code()).isEqualTo(200);
            var body = response.body().string();
            assertThat(body).doesNotContain("<!DOCTYPE");
            assertThat(body).doesNotContain("<html");
            assertThat(body).contains("Alien");
            assertThat(body).contains("href=\"/movies/348\"");
            // htmx only initializes links with an [hx-boost] ancestor within the swapped node —
            // the fragment must carry the boost attributes itself or result links do a full reload
            assertThat(body).contains("hx-boost=\"true\" hx-indicator=\"#topbar\"");
            assertThat(response.header("Cache-Control")).isEqualTo("no-store");
        });
    }

    @Test
    void searchFullPageReturnsDeepLinkablePage() {
        JavalinTest.test(app(), (server, http) -> {
            var response = http.get("/search?q=alien");

            assertThat(response.code()).isEqualTo(200);
            var body = response.body().string();
            assertThat(body).contains("<!DOCTYPE html>");
            assertThat(body).contains("value=\"alien\"");
            assertThat(body).contains("Alien");
        });
    }

    @Test
    void blankSearchShowsEmptyState() {
        JavalinTest.test(app(), (server, http) -> {
            var fragment = http.get("/search?q=%20%20", request ->
                request.header("HX-Request", "true"));

            assertThat(fragment.code()).isEqualTo(200);
            // a truly empty body, not a whitespace-only fragment: whitespace text nodes would
            // defeat the CSS :empty rule that hides the overlay/home results container
            assertThat(fragment.body().string()).isEmpty();
        });
    }

    @Test
    void searchResultsShowOriginalTitleOnlyWhenDistinct() {
        var wings = new SearchResult(1000, SearchType.MOVIE, "Wings of Desire", "Der Himmel über Berlin", 1987, "8.0", null);
        var alien = new SearchResult(348, SearchType.MOVIE, "Alien", "Alien", 1979, "8.2", null);
        var app = appWithRepositories(
            (q, p) -> new SearchResultPage(of(wings, alien)),
            id -> Optional.empty(),
            id -> Optional.empty()
        );
        JavalinTest.test(app, (server, http) -> {
            var response = http.get("/search?q=wings");

            assertThat(response.code()).isEqualTo(200);
            var body = response.body().string();
            assertThat(body).contains("Der Himmel über Berlin");
            assertThat(body.split("class=\"original-title\"", -1).length - 1).isEqualTo(1);
        });
    }

    @Test
    void noResultsShowsFriendlyMessage() {
        var app = appWithRepositories(
            (q, p) -> new SearchResultPage(of()),
            id -> Optional.empty(),
            id -> Optional.empty()
        );
        JavalinTest.test(app, (server, http) -> {
            var response = http.get("/search?q=zzzznothing", request ->
                request.header("HX-Request", "true"));

            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).contains("No results for");
        });
    }

    @Test
    void moviePageShowsTrailersLinkAndDialog() {
        JavalinTest.test(app(), (server, http) -> {
            var response = http.get("/movies/348");

            assertThat(response.code()).isEqualTo(200);
            var body = response.body().string();
            assertThat(body).contains(">Trailers (3)<");
            assertThat(body).doesNotContain("▶");
            assertThat(body).contains("<dialog id=\"trailers\">");
            // best-ranked video frame loads on dialog open, but does not autoplay
            assertThat(body).contains("https://www.youtube-nocookie.com/embed/trailerKey1?enablejsapi=1\"");
            assertThat(body).doesNotContain("autoplay=1");
            // ranked list inside the dialog; each item swaps the player in place
            assertThat(body).contains("https://www.youtube.com/watch?v=trailerKey1");
            assertThat(body).contains("https://www.youtube.com/watch?v=teaserKey1");
            assertThat(body).contains("https://www.youtube.com/watch?v=clipKey1");
            assertThat(body).contains("/videos/trailerKey1");
            assertThat(body).contains("hx-target=\"#player\"");
            assertThat(body).contains("<li class=\"selected\">");
        });
    }

    @Test
    void videoFragmentReturnsNocookieAutoplayPlayer() {
        JavalinTest.test(app(), (server, http) -> {
            var response = http.get("/videos/validkey12?name=Official%20Trailer");

            assertThat(response.code()).isEqualTo(200);
            var body = response.body().string();
            assertThat(body).doesNotContain("<!DOCTYPE");
            assertThat(body).contains("<div id=\"player\">");
            assertThat(body).contains("https://www.youtube-nocookie.com/embed/validkey12?enablejsapi=1&amp;autoplay=1");
            assertThat(body).contains("Official Trailer");
        });
    }

    @Test
    void videoFragmentEscapesName() {
        JavalinTest.test(app(), (server, http) -> {
            var response = http.get("/videos/validkey12?name=%3Cscript%3Ealert(1)%3C/script%3E");

            assertThat(response.code()).isEqualTo(200);
            var body = response.body().string();
            assertThat(body).doesNotContain("<script>");
            assertThat(body).contains("&lt;script&gt;");
        });
    }

    @Test
    void videoFragmentRejectsInvalidKeys() {
        JavalinTest.test(app(), (server, http) -> {
            assertThat(http.get("/videos/bad.key").code()).isEqualTo(404);
            assertThat(http.get("/videos/ok").code()).isEqualTo(404);
            assertThat(http.get("/videos/validkey12").code()).isEqualTo(200);
        });
    }

    @Test
    void moviePageWithoutVideosHasNoTrailersLinkOrDialog() {
        var movie = new Movie(
            100, "Movie", null, null, null,
            of(), null, "Overview", null, null,
            of(), of(), of(), null, of()
        );
        var app = appWithRepositories(
            (q, p) -> new SearchResultPage(of()),
            id -> Optional.of(movie),
            id -> Optional.empty()
        );
        JavalinTest.test(app, (server, http) -> {
            var response = http.get("/movies/100");

            assertThat(response.code()).isEqualTo(200);
            var body = response.body().string();
            assertThat(body).doesNotContain("Trailers");
            assertThat(body).doesNotContain("<dialog");
            assertThat(body).doesNotContain("youtube");
        });
    }

    @Test
    void moviePageShowsTitleAndClickableCredits() {
        JavalinTest.test(app(), (server, http) -> {
            var response = http.get("/movies/348");

            assertThat(response.code()).isEqualTo(200);
            var body = response.body().string();
            assertThat(body).contains("Alien");
            assertThat(body).contains("1h 57m");
            assertThat(body).contains("8.2 ★");
            assertThat(body).contains("<h3>Director</h3>");
            assertThat(body).contains("<h3>Writer</h3>");
            assertThat(body).contains("href=\"/persons/1/directing\"");
            assertThat(body).contains("href=\"/persons/2/writing\"");
            assertThat(body).contains("href=\"/persons/3/acting\"");
            assertThat(body).contains("Ripley");
            assertThat(body).contains("href=\"https://www.themoviedb.org/movie/348\"");
            assertThat(body).contains("not endorsed or certified by TMDB");
        });
    }

    @Test
    void moviePagePluralizesCrewHeadings() {
        var movie = new Movie(
            100, "Movie", null, null, null,
            of(), null, null, null, null,
            of(new PersonLink(1, "One", "Director", Department.DIRECTING),
               new PersonLink(2, "Two", "Director", Department.DIRECTING)),
            of(new PersonLink(3, "Three", "Screenplay", Department.WRITING),
               new PersonLink(4, "Four", "Story", Department.WRITING)),
            of(),
            null,
            of()
        );
        var app = appWithRepositories(
            (q, p) -> new SearchResultPage(of()),
            id -> Optional.of(movie),
            id -> Optional.empty()
        );
        JavalinTest.test(app, (server, http) -> {
            var response = http.get("/movies/100");

            assertThat(response.code()).isEqualTo(200);
            var body = response.body().string();
            assertThat(body).contains("<h3>Directors</h3>");
            assertThat(body).contains("<h3>Writers</h3>");
            assertThat(body).doesNotContain("<h3>Director</h3>");
        });
    }

    @Test
    void unknownMovieIdReturns404() {
        var app = appWithRepositories(
            (q, p) -> new SearchResultPage(of()),
            id -> {
                throw new NotFoundException("no movie");
            },
            id -> Optional.empty()
        );
        JavalinTest.test(app, (server, http) -> {
            var response = http.get("/movies/999");

            assertThat(response.code()).isEqualTo(404);
            assertThat(response.body().string()).contains("Not found");
        });
    }

    @Test
    void nonNumericMovieIdReturns404() {
        JavalinTest.test(app(), (server, http) -> {
            var response = http.get("/movies/abc");

            assertThat(response.code()).isEqualTo(404);
        });
    }

    @Test
    void personPageShowsFilmographyAndTabs() {
        JavalinTest.test(app(), (server, http) -> {
            var response = http.get("/persons/1");

            assertThat(response.code()).isEqualTo(200);
            var body = response.body().string();
            assertThat(body).contains("Ridley Scott");
            assertThat(body).contains("Known for Directing");
            assertThat(body).contains("href=\"/persons/1/directing\"");
            assertThat(body).contains("href=\"/persons/1/acting\"");
            assertThat(body).contains("href=\"/persons/1/writing\"");
            assertThat(body).contains("href=\"/movies/348\"");
            assertThat(body).contains("href=\"https://www.themoviedb.org/person/1\"");
            assertThat(body).contains("not endorsed or certified by TMDB");
        });
    }

    @Test
    void personDepartmentPageShowsOnlyThatSection() {
        JavalinTest.test(app(), (server, http) -> {
            var response = http.get("/persons/1/acting");

            assertThat(response.code()).isEqualTo(200);
            var body = response.body().string();
            assertThat(body).contains("Some Cameo");
            assertThat(body).doesNotContain("<h3>Directing</h3>");
        });
    }

    @Test
    void unknownDepartmentReturns404() {
        JavalinTest.test(app(), (server, http) -> {
            var response = http.get("/persons/1/bogus");

            assertThat(response.code()).isEqualTo(404);
        });
    }

    @Test
    void tmdbFailureReturns503FriendlyPage() {
        var app = appWithRepositories(
            (q, p) -> {
                throw new TmdbUnavailableException("boom");
            },
            id -> Optional.empty(),
            id -> Optional.empty()
        );
        JavalinTest.test(app, (server, http) -> {
            var fullPage = http.get("/search?q=alien");
            assertThat(fullPage.code()).isEqualTo(503);
            var fullBody = fullPage.body().string();
            assertThat(fullBody).contains("temporarily unavailable");
            assertThat(fullBody).doesNotContain("boom");

            var fragment = http.get("/search?q=alien", request ->
                request.header("HX-Request", "true"));
            assertThat(fragment.code()).isEqualTo(503);
            assertThat(fragment.body().string()).doesNotContain("<!DOCTYPE");
        });
    }

    @Test
    void healthzReturnsOk() {
        JavalinTest.test(app(), (server, http) -> {
            var response = http.get("/healthz");

            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).isEqualTo("ok");
        });
    }

    @Test
    void healthReturnsUp() {
        JavalinTest.test(app(), (server, http) -> {
            var response = http.get("/health");

            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).contains("\"UP\"");
        });
    }

    private Javalin app() {
        return appWithRepositories(
            (q, p) -> new SearchResultPage(of(ALIEN)),
            id -> Optional.of(ALIEN_MOVIE),
            id -> Optional.of(RIDLEY_SCOTT)
        );
    }

    private Javalin appWithRepositories(
        SearchRepository searchRepository,
        MovieRepository movieRepository,
        PersonRepository personRepository
    ) {
        return EkranApp.create(
            new SearchService(searchRepository),
            new MovieService(movieRepository),
            new PersonService(personRepository)
        );
    }
}