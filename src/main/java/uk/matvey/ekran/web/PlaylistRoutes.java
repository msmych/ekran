package uk.matvey.ekran.web;

import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import uk.matvey.ekran.domain.MovieIds;
import uk.matvey.ekran.domain.MovieNote;
import uk.matvey.ekran.domain.NotFoundException;
import uk.matvey.ekran.marks.MarksService;
import uk.matvey.ekran.playlists.PlaylistDetail;
import uk.matvey.ekran.playlists.PlaylistMembership;
import uk.matvey.ekran.playlists.PlaylistsService;
import uk.matvey.ekran.service.MovieService;
import uk.matvey.ekran.web.viewmodels.MovieCardVm;

/**
 * Playlist CRUD and the membership selector dialog. Ownership is enforced
 * everywhere: the user id always comes from the session, playlist lookups are
 * scoped by it, and a foreign playlist behaves exactly like a missing one (404).
 */
public class PlaylistRoutes extends Routes {

    private final PlaylistsService playlistsService;
    private final MovieService movieService;
    private final MarksService marksService;

    public PlaylistRoutes(PlaylistsService playlistsService, MovieService movieService, MarksService marksService) {
        this.playlistsService = playlistsService;
        this.movieService = movieService;
        this.marksService = marksService;
    }

    public void register(Javalin app) {
        app.get("/playlists", this::index);
        app.post("/playlists", this::create);
        app.get("/playlists/select", this::select);
        app.get("/playlists/{id}", this::detail);
        app.post("/playlists/{id}/rename", this::rename);
        app.post("/playlists/{id}/description", this::updateDescription);
        app.post("/playlists/{id}/delete", this::delete);
        app.post("/playlists/{id}/movies", this::addMovies);
        app.post("/playlists/{id}/movies/{movieId}", this::addMovie);
        app.delete("/playlists/{id}/movies/{movieId}", this::removeMovie);
        app.get("/playlists/{id}/movies/{movieId}/note", this::noteArea);
        app.get("/playlists/{id}/movies/{movieId}/note/edit", this::noteEditor);
        app.post("/playlists/{id}/movies/{movieId}/note", this::setMovieNote);
        app.post("/playlists/{id}/movies/{movieId}/move", this::moveMovie);
    }

    private void index(Context ctx) {
        var userId = userId(ctx);
        if (userId == null) {
            redirectToSignin(ctx, "/playlists");
            return;
        }
        ctx.render("playlists", Map.of("playlists", playlistsService.playlists(userId)));
    }

    private void detail(Context ctx) {
        var userId = userId(ctx);
        var id = parseId(ctx);
        if (userId == null) {
            redirectToSignin(ctx, "/playlists/" + id);
            return;
        }
        var playlist = playlistsService.playlist(userId, id)
            .orElseThrow(() -> new NotFoundException("Playlist not found: " + id));
        // one TMDB call per card — the same cap as /list keeps the page bounded
        var movies = playlist.movies().stream().limit(MovieIds.MAX_SET).toList();
        var movieIds = movies.stream().map(MovieNote::movieId).toList();
        var cards = movieService.findByIds(movieIds).stream().map(MovieCardVm::of).toList();
        var notes = new HashMap<Long, String>();
        movies.forEach(m -> {
            if (m.note() != null) {
                notes.put(m.movieId(), m.note());
            }
        });
        var model = new HashMap<String, Object>();
        model.put("id", id);
        model.put("name", playlist.name());
        model.put("description", playlist.description());
        model.put("cards", cards);
        model.put("notes", notes);
        model.put("movieIds", movieIds);
        ctx.render("playlist", model);
    }

    // dialog body: exactly one movie param → toggle-membership mode (movie page);
    // several → pick-a-playlist bulk mode (shared lists, marked page)
    private void select(Context ctx) {
        var userId = requireUser(ctx);
        var ids = MovieIds.validOf(ctx.queryParams("movie"));
        if (ids.isEmpty()) {
            ctx.status(400);
            return;
        }
        if (ids.size() == 1) {
            ctx.render("playlist-select", singleMovieSelect(userId, ids.getFirst()));
        } else {
            ctx.render("playlist-select", bulkSelect(userId, ids, null, null));
        }
    }

    // composing a playlist out of marked movies is a copy, not a move: the
    // marks stay, and each mark's note is carried into the new membership
    private void create(Context ctx) {
        var userId = requireUser(ctx);
        var name = PlaylistsService.validName(ctx.formParam("name"));
        if (name.isEmpty()) {
            ctx.status(400);
            return;
        }
        var description = PlaylistsService.normalizedDescription(ctx.formParam("description"));
        if (invalidDescription(description)) {
            ctx.status(400);
            return;
        }
        var movieIds = MovieIds.validOf(ctx.formParams("movie"));
        // one transaction: the playlist is created with its movies or not at all
        var id = playlistsService.createPlaylist(userId, name.get(), description, withMarkNotes(userId, movieIds));
        if (movieIds.size() == 1) {
            // movie-page dialog: stay in the dialog, show the new playlist checked
            ctx.render("playlist-select", singleMovieSelect(userId, movieIds.getFirst()));
            return;
        }
        if (movieIds.size() > 1 && htmx(ctx)) {
            // bulk dialog: stay on the page the dialog was opened from, with a
            // confirmation — the picker keeps its movie set, so more adds work
            ctx.render("playlist-select", bulkSelect(userId, movieIds,
                "Created " + name.get() + " with " + movieIds.size() + " movies", "/playlists/" + id));
            return;
        }
        navigate(ctx, "/playlists/" + id);
    }

    private void rename(Context ctx) {
        var userId = requireUser(ctx);
        var id = parseId(ctx);
        var name = PlaylistsService.validName(ctx.formParam("name"));
        if (name.isPresent()) {
            if (!playlistsService.renamePlaylist(userId, id, name.get())) {
                throw new NotFoundException("Playlist not found: " + id);
            }
        }
        navigate(ctx, "/playlists/" + id);
    }

    private void updateDescription(Context ctx) {
        var userId = requireUser(ctx);
        var id = parseId(ctx);
        var description = PlaylistsService.normalizedDescription(ctx.formParam("description"));
        if (invalidDescription(description)) {
            ctx.status(400);
            return;
        }
        if (!playlistsService.updateDescription(userId, id, description)) {
            throw new NotFoundException("Playlist not found: " + id);
        }
        navigate(ctx, "/playlists/" + id);
    }

    private void delete(Context ctx) {
        var userId = requireUser(ctx);
        var id = parseId(ctx);
        if (!playlistsService.deletePlaylist(userId, id)) {
            throw new NotFoundException("Playlist not found: " + id);
        }
        navigate(ctx, "/playlists");
    }

    private void addMovies(Context ctx) {
        var userId = requireUser(ctx);
        var id = parseId(ctx);
        var movieIds = MovieIds.validOf(ctx.formParams("movie"));
        playlistsService.addMovies(userId, id, withMarkNotes(userId, movieIds));
        if (htmx(ctx)) {
            // bulk dialog: stay on the page, confirm in the picker
            var name = playlistsService.playlist(userId, id)
                .map(PlaylistDetail::name)
                .orElse("");
            ctx.render("playlist-select", bulkSelect(userId, movieIds,
                "Added " + movieIds.size() + " movies to " + name, "/playlists/" + id));
            return;
        }
        navigate(ctx, "/playlists/" + id);
    }

    private void addMovie(Context ctx) {
        mutateMembership(ctx, playlistsService::addMovie);
    }

    private void removeMovie(Context ctx) {
        mutateMembership(ctx, playlistsService::removeMovie);
    }

    // the inline membership-note editor, mirroring the mark-note fragments:
    // GET /note renders the display area (the ✕ cancel target), GET /note/edit
    // swaps it into the form, POST saves and renders the display area back
    private void noteArea(Context ctx) {
        var userId = requireUser(ctx);
        var id = parseId(ctx);
        var movieId = movieId(ctx);
        var note = membershipNote(userId, id, movieId);
        renderNoteArea(ctx, note, editUrl(id, movieId));
    }

    private void noteEditor(Context ctx) {
        var userId = requireUser(ctx);
        var id = parseId(ctx);
        var movieId = movieId(ctx);
        var note = membershipNote(userId, id, movieId);
        renderNoteEditor(ctx, note, noteUrl(id, movieId));
    }

    private String membershipNote(long userId, long playlistId, long movieId) {
        var playlist = playlistsService.playlist(userId, playlistId)
            .orElseThrow(() -> new NotFoundException("Playlist not found: " + playlistId));
        return playlist.movies().stream()
            .filter(m -> m.movieId() == movieId)
            .findFirst()
            .map(MovieNote::note)
            .orElse(null);
    }

    private void setMovieNote(Context ctx) {
        var userId = requireUser(ctx);
        var id = parseId(ctx);
        var movieId = movieId(ctx);
        var note = MovieNote.normalize(ctx.formParam("note"));
        if (!MarksService.validNote(note)) {
            ctx.status(400);
            return;
        }
        if (!playlistsService.setMovieNote(userId, id, movieId, note)) {
            throw new NotFoundException("Playlist not found: " + id);
        }
        renderNoteArea(ctx, note, editUrl(id, movieId));
    }

    // reorder mode: the button posts here, the server swaps the position
    // (wrapping at the edges); the response is empty — the client mirrors
    // the move in the DOM, no re-render needed
    private void moveMovie(Context ctx) {
        var userId = requireUser(ctx);
        var id = parseId(ctx);
        var rawMovieId = ctx.pathParam("movieId");
        if (!MovieIds.isValid(rawMovieId)) {
            ctx.status(400);
            return;
        }
        var dir = ctx.formParam("dir");
        if (!"up".equals(dir) && !"down".equals(dir)) {
            ctx.status(400);
            return;
        }
        playlistsService.moveMovie(userId, id, Long.parseLong(rawMovieId), "up".equals(dir));
    }

    private void mutateMembership(Context ctx, MembershipOperation operation) {
        var userId = requireUser(ctx);
        var id = parseId(ctx);
        var rawMovieId = ctx.pathParam("movieId");
        if (!MovieIds.isValid(rawMovieId)) {
            ctx.status(400);
            return;
        }
        var movieId = Long.parseLong(rawMovieId);
        operation.apply(userId, id, movieId);
        // the dialog refreshes itself; the chips div travels out-of-band
        ctx.render("playlist-select", singleMovieSelect(userId, movieId));
    }

    // the single-movie (movie-page) dialog model, shared by open, create and
    // membership mutations: memberships with their member flags drive both the
    // picker list and the out-of-band chips row, hasMembers flips its button
    private Map<String, Object> singleMovieSelect(long userId, long movieId) {
        var memberships = playlistsService.playlistsWithMovie(userId, movieId);
        return Map.of(
            "memberships", memberships,
            "hasMembers", PlaylistsService.hasMemberships(memberships),
            "movieId", movieId,
            "movieIds", List.of(movieId));
    }

    // the bulk (shared lists, marked page) dialog model: one pick button per
    // playlist, the movie set kept as hidden inputs so further adds carry it
    private Map<String, Object> bulkSelect(long userId, List<Long> movieIds, String confirmation, String playlistUrl) {
        var memberships = playlistsService.playlists(userId).stream()
            .map(p -> new PlaylistMembership(p.id(), p.name(), false, null))
            .toList();
        var model = new HashMap<String, Object>();
        model.put("memberships", memberships);
        model.put("movieId", null);
        model.put("movieIds", movieIds);
        model.put("confirmation", confirmation);
        model.put("playlistUrl", playlistUrl);
        return model;
    }

    // new memberships carry the movie's mark note, where one exists; the marks
    // themselves are never touched
    private List<MovieNote> withMarkNotes(long userId, List<Long> movieIds) {
        var markNotes = new HashMap<Long, String>();
        marksService.markedMovies(userId).forEach(m -> {
            if (m.note() != null) {
                markNotes.put(m.movieId(), m.note());
            }
        });
        return movieIds.stream().map(id -> new MovieNote(id, markNotes.get(id))).toList();
    }

    // a description is optional: absent/blank means none; only too long is invalid
    private boolean invalidDescription(String description) {
        return description != null && description.length() > PlaylistsService.MAX_DESCRIPTION;
    }

    private static String noteUrl(long playlistId, long movieId) {
        return "/playlists/" + playlistId + "/movies/" + movieId + "/note";
    }

    private static String editUrl(long playlistId, long movieId) {
        return noteUrl(playlistId, movieId) + "/edit";
    }

    // a malformed movie id in a fragment route is a client wiring problem, not
    // a missing resource — 400, same as the mark-note routes
    private static long movieId(Context ctx) {
        var raw = ctx.pathParam("movieId");
        if (!MovieIds.isValid(raw)) {
            throw new BadRequestResponse("Invalid movie id: " + raw);
        }
        return Long.parseLong(raw);
    }

    @FunctionalInterface
    private interface MembershipOperation {
        void apply(long userId, long playlistId, long movieId);
    }
}
