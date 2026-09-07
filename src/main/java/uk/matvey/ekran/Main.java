package uk.matvey.ekran;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;

import uk.matvey.ekran.config.AppConfig;
import uk.matvey.ekran.service.MovieService;
import uk.matvey.ekran.service.PersonService;
import uk.matvey.ekran.service.SearchService;
import uk.matvey.ekran.tmdb.TmdbClient;
import uk.matvey.ekran.tmdb.TmdbMapper;
import uk.matvey.ekran.tmdb.TmdbMovieRepository;
import uk.matvey.ekran.tmdb.TmdbPersonRepository;
import uk.matvey.ekran.tmdb.TmdbSearchRepository;
import uk.matvey.ekran.web.EkranApp;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        var config = AppConfig.fromEnv();
        var httpClient = HttpClient.newBuilder()
            .connectTimeout(config.connectTimeout())
            .build();
        var objectMapper = new ObjectMapper();
        var tmdbClient = new TmdbClient(httpClient, objectMapper, config);
        var mapper = new TmdbMapper(config);

        var app = EkranApp.create(
            new SearchService(new TmdbSearchRepository(tmdbClient, mapper)),
            new MovieService(new TmdbMovieRepository(tmdbClient, mapper)),
            new PersonService(new TmdbPersonRepository(tmdbClient, mapper))
        );
        app.start(config.port());
        log.info("ekran started on port {}", config.port());
    }
}