package uk.matvey.ekran.web;

import java.util.Map;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.rendering.template.JavalinThymeleaf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Map;

import uk.matvey.ekran.domain.NotFoundException;
import uk.matvey.ekran.domain.TmdbAuthException;
import uk.matvey.ekran.domain.TmdbUnavailableException;
import uk.matvey.ekran.service.MovieService;
import uk.matvey.ekran.service.PersonService;
import uk.matvey.ekran.service.SearchService;

public final class EkranApp {

    private static final Logger log = LoggerFactory.getLogger(EkranApp.class);

    private static final String UNAVAILABLE_MESSAGE = "Search is temporarily unavailable. Please try again in a moment.";

    private EkranApp() {
    }

    public static Javalin create(SearchService searchService, MovieService movieService, PersonService personService) {
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
            cfg.fileRenderer(new JavalinThymeleaf(templateEngine));
            cfg.requestLogger.http((ctx, ms) ->
                log.info("{} {} -> {} ({} ms)", ctx.method(), ctx.path(), ctx.status(), ms == null ? "-" : Math.round(ms)));
        });
        // explicit revalidation everywhere: without it browsers heuristically cache
        // pages and assets (Safari pairs max-age=0 with the fake 1980 Last-Modified
        // and serves stale JS after deploys — mismatched markup/JS versions follow)
        app.before(ctx -> ctx.header("Cache-Control", "no-cache"));
        new SearchRoutes(searchService).register(app);
        new MovieRoutes(movieService).register(app);
        new PersonRoutes(personService).register(app);
        new ListRoutes(movieService).register(app);
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
        app.get("/health", ctx -> ctx.json(Map.of("status", "UP")));
        return app;
    }

    private static void registerErrorHandlers(Javalin app) {
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