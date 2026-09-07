package uk.matvey.ekran.tmdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import uk.matvey.ekran.config.AppConfig;
import uk.matvey.ekran.domain.NotFoundException;
import uk.matvey.ekran.domain.TmdbAuthException;
import uk.matvey.ekran.domain.TmdbUnavailableException;

import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TmdbClientTest {

    private final MockWebServer server = new MockWebServer();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private TmdbClient client;

    @BeforeEach
    void start() throws Exception {
        server.start();
        var config = new AppConfig(
            "secret-token", 7070,
            server.url("/3").toString(),
            "https://image.tmdb.org/t/p",
            1000, 300, 1000
        );
        client = new TmdbClient(HttpClient.newHttpClient(), objectMapper, config);
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    @Test
    void sendsBearerTokenAndQueryParams() throws Exception {
        server.enqueue(new MockResponse().setBody("""
            {"page": 1, "results": [{"id": 348, "title": "Alien"}]}
            """));

        var response = client.searchMovies("the alien", 1);

        var recorded = server.takeRequest();
        assertThat(recorded.getPath())
            .isEqualTo("/3/search/movie?query=the+alien&include_adult=false&page=1");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer secret-token");
        assertThat(recorded.getHeader("Accept")).isEqualTo("application/json");
        assertThat(response.results()).hasSize(1);
    }

    @Test
    void appendsCreditsAndLanguageForMovieDetail() throws Exception {
        server.enqueue(new MockResponse().setBody("""
            {"id": 348, "title": "Alien", "credits": {"crew": [], "cast": []}}
            """));

        client.movieWithCredits(348);

        assertThat(server.takeRequest().getPath())
            .isEqualTo("/3/movie/348?append_to_response=credits&language=en-US");
    }

    @Test
    void appendsMovieCreditsForPerson() throws Exception {
        server.enqueue(new MockResponse().setBody("""
            {"id": 1, "name": "Ridley Scott", "movie_credits": {"crew": [], "cast": []}}
            """));

        client.personWithMovieCredits(1);

        assertThat(server.takeRequest().getPath())
            .isEqualTo("/3/person/1?append_to_response=movie_credits&language=en-US");
    }

    @Test
    void maps404ToNotFound() {
        server.enqueue(new MockResponse().setResponseCode(404));

        assertThatThrownBy(() -> client.movieWithCredits(999))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void maps401ToAuthErrorWithoutLeakingToken() {
        server.enqueue(new MockResponse().setResponseCode(401));

        assertThatThrownBy(() -> client.searchMovies("alien", 1))
            .isInstanceOf(TmdbAuthException.class)
            .hasMessageNotContaining("secret-token");
    }

    @Test
    void maps500ToUnavailable() {
        server.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> client.searchMovies("alien", 1))
            .isInstanceOf(TmdbUnavailableException.class);
    }

    @Test
    void mapsGarbageJsonToUnavailable() {
        server.enqueue(new MockResponse().setBody("<html>not json</html>"));

        assertThatThrownBy(() -> client.searchMovies("alien", 1))
            .isInstanceOf(TmdbUnavailableException.class);
    }

    @Test
    void mapsTimeoutToUnavailable() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        assertThatThrownBy(() -> client.searchMovies("alien", 1))
            .isInstanceOf(TmdbUnavailableException.class);
    }
}