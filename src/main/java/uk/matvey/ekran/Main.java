package uk.matvey.ekran;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.matvey.ekran.auth.AuthService;
import uk.matvey.ekran.auth.PgAuthRepository;
import uk.matvey.ekran.config.AppConfig;
import uk.matvey.ekran.db.DataSources;
import uk.matvey.ekran.db.DbMigrations;
import uk.matvey.ekran.email.ResendEmailService;
import uk.matvey.ekran.marks.MarksService;
import uk.matvey.ekran.marks.PgMarksRepository;
import uk.matvey.ekran.playlists.PgPlaylistsRepository;
import uk.matvey.ekran.playlists.PlaylistsService;
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

        var dataSource = DataSources.create(config);
        DbMigrations.migrate(dataSource);
        var emailService = new ResendEmailService(httpClient, objectMapper, config.resendApiKey(), config.resendBaseUrl(), config.authFromEmail());
        var authService = new AuthService(
            new PgAuthRepository(dataSource),
            emailService,
            config.publicBaseUrl(),
            config.tokenTtl(),
            config.sessionLifetime(),
            config.secureCookies(),
            Clock.systemUTC());

        var app = EkranApp.create(
            new SearchService(new TmdbSearchRepository(tmdbClient, mapper)),
            new MovieService(new TmdbMovieRepository(tmdbClient, mapper)),
            new PersonService(new TmdbPersonRepository(tmdbClient, mapper)),
            authService,
            new MarksService(new PgMarksRepository(dataSource)),
            new PlaylistsService(new PgPlaylistsRepository(dataSource)),
            config.secureCookies(),
            dataSource
        );
        app.start(config.port());
        log.info("ekran started on port {}", config.port());
    }
}
