package uk.matvey.ekran.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import uk.matvey.ekran.domain.MovieIds;
import uk.matvey.ekran.marks.MarksService;
import uk.matvey.ekran.service.MovieService;
import uk.matvey.ekran.web.viewmodels.MovieCardVm;

/**
 * Authenticated marked movies — PostgreSQL-backed, as opposed to the anonymous
 * localStorage flow handled entirely client-side. The session middleware has
 * already resolved the user; these endpoints never accept a user id from the browser.
 */
public class MarkedRoutes {

    private final MarksService marksService;
    private final MovieService movieService;

    public MarkedRoutes(MarksService marksService, MovieService movieService) {
        this.marksService = marksService;
        this.movieService = movieService;
    }

    public void register(Javalin app) {
        app.get("/marked", this::marked);
        app.get("/marked/ids", this::ids);
        app.post("/marked/{movieId}", this::mark);
        app.delete("/marked/{movieId}", this::unmark);
        app.post("/marked", this::markAll);
        app.delete("/marked", this::unmarkAll);
    }

    private void marked(Context ctx) {
        var userId = userId(ctx);
        if (userId == null) {
            ctx.redirect("/signin?next=" + URLEncoder.encode("/marked", StandardCharsets.UTF_8), HttpStatus.SEE_OTHER);
            return;
        }
        // one TMDB call per card — the same cap as /list keeps the page bounded
        var ids = marksService.markedMovieIds(userId).stream().limit(MovieIds.MAX_SET).toList();
        var cards = movieService.findByIds(ids).stream().map(MovieCardVm::of).toList();
        ctx.render("marked", Map.of("cards", cards, "movieIds", ids));
    }

    // lightweight reseed for JS after bfcache restores and the like
    private void ids(Context ctx) {
        var userId = userId(ctx);
        if (userId == null) {
            ctx.status(401);
            return;
        }
        ctx.result(String.join(",", marksService.markedMovieIds(userId).stream().map(String::valueOf).toList()));
    }

    private void mark(Context ctx) {
        mutate(ctx, marksService::mark);
    }

    private void unmark(Context ctx) {
        mutate(ctx, marksService::unmark);
    }

    private void mutate(Context ctx, BiConsumer<Long, Long> operation) {
        var userId = userId(ctx);
        if (userId == null) {
            ctx.status(401);
            return;
        }
        var movieId = ctx.pathParam("movieId");
        if (!MovieIds.isValid(movieId)) {
            ctx.status(400);
            return;
        }
        operation.accept(userId, Long.parseLong(movieId));
        ctx.status(204);
    }

    private void markAll(Context ctx) {
        bulk(ctx, marksService::markAll);
    }

    private void unmarkAll(Context ctx) {
        bulk(ctx, marksService::unmarkAll);
    }

    // the local-marks merge and "Mark all"/"Clear all" share this shape:
    // movie params, tolerant of invalid ids, idempotent
    private void bulk(Context ctx, BiConsumer<Long, List<Long>> operation) {
        var userId = userId(ctx);
        if (userId == null) {
            ctx.status(401);
            return;
        }
        operation.accept(userId, MovieIds.validOf(ctx.formParams("movie")));
        ctx.status(204);
    }

    private static Long userId(Context ctx) {
        return ctx.<Long>attribute("userId");
    }
}