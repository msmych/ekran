package uk.matvey.ekran.web;

import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import uk.matvey.ekran.domain.MovieIds;
import uk.matvey.ekran.domain.MovieNote;
import uk.matvey.ekran.domain.NotFoundException;
import uk.matvey.ekran.marks.MarksService;
import uk.matvey.ekran.notes.MovieNotesService;
import uk.matvey.ekran.playlists.PlaylistsService;
import uk.matvey.ekran.service.MovieService;
import uk.matvey.ekran.web.viewmodels.MovieDetailVm;
import uk.matvey.ekran.web.viewmodels.OgVm;

public class MovieRoutes extends Routes {

    private final MovieService movieService;
    private final PlaylistsService playlistsService;
    private final MarksService marksService;
    private final MovieNotesService notesService;

    public MovieRoutes(MovieService movieService, PlaylistsService playlistsService, MarksService marksService, MovieNotesService notesService) {
        this.movieService = movieService;
        this.playlistsService = playlistsService;
        this.marksService = marksService;
        this.notesService = notesService;
    }

    public void register(Javalin app) {
        app.get("/movies/{id}", this::movie);
        // the movie-note editor: a note on the movie itself, independent of
        // the mark (marks are the bare inbox) and of any playlist membership
        app.get("/movies/{id}/note", this::noteArea);
        app.get("/movies/{id}/note/edit", this::noteEditor);
        app.post("/movies/{id}/note", this::setNote);
    }

    private void movie(Context ctx) {
        var id = parseId(ctx);
        var movie = movieService.findById(id)
            .orElseThrow(() -> new NotFoundException("Movie not found: " + id));
        var vm = MovieDetailVm.of(movie);
        var model = new HashMap<String, Object>(Map.of("vm", vm, "og", OgVm.movie(vm, ctx.url())));
        var userId = ctx.<Long>attribute("userId");
        if (userId != null) {
            // the middleware already loaded this user's full marked set
            var markedIds = ctx.<List<Long>>attribute("markedIds");
            model.put("marked", new HashSet<>(markedIds).contains(id));
            model.put("movieNote", notesService.note(userId, id));
            var memberships = playlistsService.playlistsWithMovie(userId, id);
            model.put("memberships", memberships);
            model.put("hasMembers", PlaylistsService.hasMemberships(memberships));
        }
        ctx.render("movie", model);
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
        renderNoteArea(ctx, notesService.note(userId, movieId), editUrl(movieId));
    }

    private void noteEditor(Context ctx) {
        var userId = requireUser(ctx);
        var movieId = movieId(ctx);
        if (movieId == null) {
            ctx.status(400);
            return;
        }
        renderNoteEditor(ctx, notesService.note(userId, movieId), noteUrl(movieId));
    }

    private void setNote(Context ctx) {
        var userId = requireUser(ctx);
        var movieId = movieId(ctx);
        if (movieId == null) {
            ctx.status(400);
            return;
        }
        var note = MovieNote.normalize(ctx.formParam("note"));
        if (!MovieNotesService.validNote(note)) {
            ctx.status(400);
            return;
        }
        notesService.setNote(userId, movieId, note);
        renderNoteArea(ctx, note, editUrl(movieId));
    }

    private static String noteUrl(long movieId) {
        return "/movies/" + movieId + "/note";
    }

    private static String editUrl(long movieId) {
        return noteUrl(movieId) + "/edit";
    }

    private static Long movieId(Context ctx) {
        var raw = ctx.pathParam("id");
        return MovieIds.isValid(raw) ? Long.parseLong(raw) : null;
    }
}
