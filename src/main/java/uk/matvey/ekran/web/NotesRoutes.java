package uk.matvey.ekran.web;

import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.HashMap;
import java.util.Map;
import uk.matvey.ekran.domain.MovieIds;
import uk.matvey.ekran.domain.MovieNote;
import uk.matvey.ekran.notes.MovieNotesService;
import uk.matvey.ekran.service.MovieService;
import uk.matvey.ekran.web.viewmodels.MovieCardVm;

/**
 * The signed-in notes index: every movie the user has annotated, most
 * recently edited first — notes are a signed-in feature, anonymous visitors
 * are sent to sign in and come back afterwards.
 */
public class NotesRoutes extends Routes {

    private final MovieNotesService notesService;
    private final MovieService movieService;

    public NotesRoutes(MovieNotesService notesService, MovieService movieService) {
        this.notesService = notesService;
        this.movieService = movieService;
    }

    public void register(Javalin app) {
        app.get("/notes", this::notes);
    }

    private void notes(Context ctx) {
        var userId = userId(ctx);
        if (userId == null) {
            redirectToSignin(ctx, "/notes");
            return;
        }
        var noted = notesService.notedMovies(userId);
        // one TMDB call per card — the same cap as /list keeps the page bounded
        var ids = noted.stream().map(MovieNote::movieId).limit(MovieIds.MAX_SET).toList();
        var cards = movieService.findByIds(ids).stream().map(MovieCardVm::of).toList();
        var movieNotes = new HashMap<>(notesService.notes(userId, ids));
        ctx.render("notes", Map.of("cards", cards, "movieNotes", movieNotes));
    }
}
