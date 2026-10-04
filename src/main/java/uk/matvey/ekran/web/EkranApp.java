package uk.matvey.ekran.web;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.rendering.FileRenderer;
import io.javalin.rendering.template.JavalinThymeleaf;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import uk.matvey.ekran.auth.AuthService;
import uk.matvey.ekran.domain.NotFoundException;
import uk.matvey.ekran.domain.TmdbAuthException;
import uk.matvey.ekran.domain.TmdbUnavailableException;
import uk.matvey.ekran.marks.MarksService;
import uk.matvey.ekran.notes.MovieNotesService;
import uk.matvey.ekran.playlists.PlaylistsService;
import uk.matvey.ekran.service.MovieService;
import uk.matvey.ekran.service.PersonService;
import uk.matvey.ekran.service.SearchService;

public final class EkranApp {

    private static final Logger log = LoggerFactory.getLogger(EkranApp.class);

    private static final String UNAVAILABLE_MESSAGE = "Search is temporarily unavailable. Please try again in a moment.";

    private EkranApp() {
    }

    public static Javalin create(
        SearchService searchService,
        MovieService movieService,
        PersonService personService,
        AuthService authService,
        MarksService marksService,
        MovieNotesService notesService,
        PlaylistsService playlistsService,
        boolean secureCookies,
        DataSource dataSource
    ) {
        var templateResolver = new ClassLoaderTemplateResolver();
        templateResolver.setPrefix("/templates/");
        templateResolver.setSuffix(".html");
        templateResolver.setTemplateMode(TemplateMode.HTML);
        var templateEngine = new TemplateEngine();
        templateEngine.setTemplateResolver(templateResolver);

        var app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.staticFiles.add(staticFiles -> {
                staticFiles.directory = "/static";
                staticFiles.location = Location.CLASSPATH;
                staticFiles.headers = Map.of("Cache-Control", "no-cache");
            });
            var thymeleaf = new JavalinThymeleaf(templateEngine);
            cfg.fileRenderer(mergeAuthModel(thymeleaf));
            cfg.requestLogger.http((ctx, ms) ->
                // request URI only, never the query string — it would log raw magic-link tokens
                log.info("{} {} -> {} ({} ms)", ctx.method(), ctx.req().getRequestURI(), ctx.status(), ms == null ? "-" : Math.round(ms)));
        });
        // explicit revalidation everywhere: without it browsers heuristically cache
        // pages and assets (Safari pairs max-age=0 with the fake 1980 Last-Modified
        // and serves stale JS after deploys — mismatched markup/JS versions follow)
        app.before(ctx -> ctx.header("Cache-Control", "no-cache"));
        app.before(ctx -> resolveCurrentUser(ctx, authService, marksService, notesService, playlistsService));
        new SearchRoutes(searchService).register(app);
        new MovieRoutes(movieService, playlistsService, marksService, notesService).register(app);
        new PersonRoutes(personService).register(app);
        new ListRoutes(movieService).register(app);
        new AuthRoutes(authService, secureCookies).register(app);
        new MarkedRoutes(marksService, notesService, movieService).register(app);
        new NotesRoutes(notesService, movieService).register(app);
        new PlaylistRoutes(playlistsService, movieService, marksService).register(app);
        app.get("/about", ctx -> ctx.render("about"));
        app.get("/videos/{key}", ctx -> {
            var key = ctx.pathParam("key");
            if (!key.matches("[A-Za-z0-9_-]{6,}")) {
                throw new NotFoundException("Invalid video key: " + key);
            }
            var name = ctx.queryParam("name");
            ctx.render("video-player", name == null
                ? Map.of("key", key)
                : Map.of("key", key, "name", name));
        });
        registerErrorHandlers(app);
        app.get("/healthz", ctx -> ctx.result("ok"));
        app.get("/health", ctx -> health(ctx, dataSource));
        return app;
    }

    // userEmail/currentPath/marked seed come from the session middleware, not from
    // per-route models — the header needs them on every page (Sign in link vs
    // account menu, and marked.js needs the server-side mark set when authenticated)
    private static FileRenderer mergeAuthModel(FileRenderer delegate) {
        return (filePath, model, ctx) -> {
            var merged = new HashMap<String, Object>(model);
            merged.put("userEmail", ctx.<String>attribute("userEmail"));
            merged.put("currentPath", ctx.<String>attribute("currentPath"));
            merged.put("userId", ctx.<Long>attribute("userId"));
            merged.put("markedIdsCsv", ctx.<String>attribute("markedIdsCsv"));
            merged.put("playlistsCount", ctx.<Long>attribute("playlistsCount"));
            merged.put("notesCount", ctx.<Long>attribute("notesCount"));
            return delegate.render(filePath, merged, ctx);
        };
    }

    private static void resolveCurrentUser(Context ctx, AuthService authService, MarksService marksService, MovieNotesService notesService, PlaylistsService playlistsService) {
        var uri = ctx.req().getRequestURI();
        var query = ctx.req().getQueryString();
        // the sign-in/sign-out continuation: the plain path — except on /list,
        // where the query string IS the content (a shared list signed into
        // from must not lose its movies)
        ctx.attribute("currentPath", query != null && "/list".equals(uri) ? uri + "?" + query : uri);
        var sessionId = ctx.cookie(authService.sessionCookieName());
        if (sessionId != null) {
            authService.sessionUser(sessionId).ifPresent(user -> {
                ctx.attribute("userId", user.userId());
                ctx.attribute("userEmail", user.email());
                var markedIds = marksService.markedMovieIds(user.userId());
                ctx.attribute("markedIds", markedIds);
                ctx.attribute("markedIdsCsv", markedIds.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(",")));
                ctx.attribute("playlistsCount", playlistsService.playlistsCount(user.userId()));
                ctx.attribute("notesCount", notesService.notesCount(user.userId()));
            });
        }
    }

    private static void health(Context ctx, DataSource dataSource) {
        // tests assemble the app without a DB; production always has one
        if (dataSource == null) {
            ctx.json(Map.of("status", "UP"));
            return;
        }
        try (var conn = dataSource.getConnection(); var st = conn.createStatement()) {
            st.executeQuery("SELECT 1");
            ctx.json(Map.of("status", "UP"));
        } catch (SQLException e) {
            log.warn("health check: database unreachable: {}", e.getMessage());
            ctx.status(503).json(Map.of("status", "DOWN"));
        }
    }

    private static void registerErrorHandlers(Javalin app) {
        // typed Javalin responses (UnauthorizedResponse, BadRequestResponse) are
        // mapped to their status codes by the framework's own default handler
        // for HttpResponseException — this catch-all must not touch them
        app.exception(NotFoundException.class, (e, ctx) -> ctx.status(404));
        app.exception(TmdbAuthException.class, (e, ctx) -> {
            log.error("TMDB rejected credentials — check TMDB_API_TOKEN");
            ctx.status(503);
        });
        app.exception(TmdbUnavailableException.class, (e, ctx) -> {
            log.warn("TMDB unavailable: {}", e.getMessage());
            ctx.status(503);
        });
        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled exception on {} {}", ctx.method(), ctx.path(), e);
            ctx.status(500);
        });

        app.error(404, ctx -> renderError(ctx, 404, "Not found"));
        app.error(503, ctx -> renderError(ctx, 503, UNAVAILABLE_MESSAGE));
        app.error(500, ctx -> renderError(ctx, 500, "Something went wrong"));
    }

    private static void renderError(Context ctx, int status, String message) {
        ctx.status(status);
        if ("true".equalsIgnoreCase(ctx.header("HX-Request"))) {
            ctx.header("Cache-Control", "no-store");
            ctx.render("error-fragment", Map.of("message", message));
        } else {
            ctx.render("error", Map.of("message", message));
        }
    }
}
