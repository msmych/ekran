package uk.matvey.ekran.web;

import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import uk.matvey.ekran.domain.MovieIds;
import uk.matvey.ekran.domain.MovieNote;
import uk.matvey.ekran.marks.MarksService;
import uk.matvey.ekran.service.MovieService;
import uk.matvey.ekran.web.viewmodels.MovieCardVm;

/**
 * Authenticated marked movies — PostgreSQL-backed, as opposed to the anonymous
 * localStorage flow handled entirely client-side. The session middleware has
 * already resolved the user; these endpoints never accept a user id from the browser.
 */
public class MarkedRoutes extends Routes {

    private final MarksService marksService;
    private final MovieService movieService;

    public MarkedRoutes(MarksService marksService, MovieService movieService) {
        this.marksService = marksService;
        this.movieService = movieService;
    }

    public void register(Javalin app) {
        app.get("/marked", this::marked);
        app.get("/marked/ids", this::ids);
        // before /marked/{movieId}: registration order decides, and "migrate"
        // would otherwise be parsed as a movie id
        app.post("/marked/migrate", this::migrate);
        app.post("/marked/{movieId}", this::mark);
        app.delete("/marked/{movieId}", this::unmark);
        app.get("/marked/{movieId}/note", this::noteArea);
        app.get("/marked/{movieId}/note/edit", this::noteEditor);
        app.post("/marked/{movieId}/note", this::setNote);
        app.post("/marked", this::markAll);
        app.delete("/marked", this::unmarkAll);
    }

    private void marked(Context ctx) {
        var userId = userId(ctx);
        if (userId == null) {
            redirectToSignin(ctx, "/marked");
            return;
        }
        var marked = marksService.markedMovies(userId);
        // one TMDB call per card — the same cap as /list keeps the page bounded
        var ids = marked.stream().map(MovieNote::movieId).limit(MovieIds.MAX_SET).toList();
        var cards = movieService.findByIds(ids).stream().map(MovieCardVm::of).toList();
        var markNotes = new HashMap<Long, String>();
        marked.stream().limit(MovieIds.MAX_SET).forEach(m -> {
            if (m.note() != null) {
                markNotes.put(m.movieId(), m.note());
            }
        });
        ctx.render("marked", Map.of("cards", cards, "movieIds", ids, "markNotes", markNotes));
    }

    // lightweight reseed for JS after bfcache restores and the like
    private void ids(Context ctx) {
        var userId = requireUser(ctx);
        ctx.result(String.join(",", marksService.markedMovieIds(userId).stream().map(String::valueOf).toList()));
    }

    private void mark(Context ctx) {
        mutate(ctx, marksService::mark);
    }

    private void unmark(Context ctx) {
        mutate(ctx, marksService::unmark);
    }

    private void mutate(Context ctx, BiConsumer<Long, Long> operation) {
        var userId = requireUser(ctx);
        var movieId = ctx.pathParam("movieId");
        if (!MovieIds.isValid(movieId)) {
            ctx.status(400);
            return;
        }
        operation.accept(userId, Long.parseLong(movieId));
        ctx.status(204);
    }

    // the inline note editor pair: GET /note renders the display area (the ✕
    // cancel target), GET /note/edit swaps it into the form, POST saves and
    // renders the display area back — every exit is an in-place swap
    private void noteArea(Context ctx) {
        var userId = requireUser(ctx);
        var movieId = movieId(ctx);
        if (movieId == null) {
            ctx.status(400);
            return;
        }
        renderNoteArea(ctx, marksService.markNote(userId, movieId), editUrl(movieId));
    }

    private void noteEditor(Context ctx) {
        var userId = requireUser(ctx);
        var movieId = movieId(ctx);
        if (movieId == null) {
            ctx.status(400);
            return;
        }
        renderNoteEditor(ctx, marksService.markNote(userId, movieId), noteUrl(movieId));
    }

    private void setNote(Context ctx) {
        var userId = requireUser(ctx);
        var movieId = movieId(ctx);
        if (movieId == null) {
            ctx.status(400);
            return;
        }
        var note = MovieNote.normalize(ctx.formParam("note"));
        if (!MarksService.validNote(note)) {
            ctx.status(400);
            return;
        }
        // the note belongs to the mark: without a mark there is nothing to annotate
        if (!marksService.setMarkNote(userId, movieId, note)) {
            ctx.status(404);
            return;
        }
        renderNoteArea(ctx, note, editUrl(movieId));
    }

    // the anonymous → authenticated migration: {movies: [{movieId, note}]}; ids
    // are marked additively and a note is only written for newly created marks
    // — an existing server-side note always wins
    private void migrate(Context ctx) {
        var userId = requireUser(ctx);
        MigrateRequest request;
        try {
            request = ctx.bodyAsClass(MigrateRequest.class);
        } catch (Exception e) {
            ctx.status(400);
            return;
        }
        if (request.movies() == null) {
            ctx.status(400);
            return;
        }
        // MovieNote's constructor trims the note itself
        var movies = request.movies().stream()
            .filter(m -> m.movieId() != null && MovieIds.isValid(Long.toString(m.movieId())))
            .map(m -> new MovieNote(m.movieId(), m.note()))
            .toList();
        if (movies.stream().anyMatch(m -> !MarksService.validNote(m.note()))) {
            ctx.status(400);
            return;
        }
        marksService.mergeMarks(userId, movies);
        ctx.status(204);
    }

    private static String noteUrl(long movieId) {
        return "/marked/" + movieId + "/note";
    }

    private static String editUrl(long movieId) {
        return noteUrl(movieId) + "/edit";
    }

    private static Long movieId(Context ctx) {
        var raw = ctx.pathParam("movieId");
        return MovieIds.isValid(raw) ? Long.parseLong(raw) : null;
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
        var userId = requireUser(ctx);
        operation.accept(userId, MovieIds.validOf(ctx.formParams("movie")));
        ctx.status(204);
    }

    public record MigrateRequest(List<MigrateMovie> movies) {
    }

    public record MigrateMovie(Long movieId, String note) {
    }
}
