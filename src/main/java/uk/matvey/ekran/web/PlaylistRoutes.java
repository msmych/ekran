package uk.matvey.ekran.web;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import uk.matvey.ekran.domain.MovieIds;
import uk.matvey.ekran.domain.NotFoundException;
import uk.matvey.ekran.marks.MarksService;
import uk.matvey.ekran.playlists.PlaylistMembership;
import uk.matvey.ekran.playlists.PlaylistsService;
import uk.matvey.ekran.service.MovieService;
import uk.matvey.ekran.web.viewmodels.MovieCardVm;

/**
 * Playlist CRUD and the membership selector dialog. Ownership is enforced
 * everywhere: the user id always comes from the session, playlist lookups are
 * scoped by it, and a foreign playlist behaves exactly like a missing one (404).
 */
public class PlaylistRoutes {

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
        app.post("/playlists/{id}/delete", this::delete);
        app.post("/playlists/{id}/movies", this::addMovies);
        app.post("/playlists/{id}/movies/{movieId}", this::addMovie);
        app.delete("/playlists/{id}/movies/{movieId}", this::removeMovie);
    }

    private void index(Context ctx) {
        var userId = userId(ctx);
        if (userId == null) {
            ctx.redirect("/signin?next=%2Fplaylists", HttpStatus.SEE_OTHER);
            return;
        }
        ctx.render("playlists", Map.of("playlists", playlistsService.playlists(userId)));
    }

    private void detail(Context ctx) {
        var userId = userId(ctx);
        var id = parseId(ctx);
        if (userId == null) {
            ctx.redirect("/signin?next=%2Fplaylists%2F" + id, HttpStatus.SEE_OTHER);
            return;
        }
        var playlist = playlistsService.playlist(userId, id)
            .orElseThrow(() -> new NotFoundException("Playlist not found: " + id));
        // one TMDB call per card — the same cap as /list keeps the page bounded
        var movieIds = playlist.movieIds().stream().limit(MovieIds.MAX_SET).toList();
        var cards = movieService.findByIds(movieIds).stream().map(MovieCardVm::of).toList();
        ctx.render("playlist", Map.of(
            "id", id,
            "name", playlist.name(),
            "cards", cards,
            "movieIds", movieIds));
    }

    // dialog body: exactly one movie param → toggle-membership mode (movie page);
    // several → pick-a-playlist bulk mode (shared lists, marked page)
    private void select(Context ctx) {
        var userId = userId(ctx);
        if (userId == null) {
            ctx.status(401);
            return;
        }
        var ids = MovieIds.validOf(ctx.queryParams("movie"));
        if (ids.isEmpty()) {
            ctx.status(400);
            return;
        }
        if (ids.size() == 1) {
            var memberships = playlistsService.playlistsWithMovie(userId, ids.getFirst());
            ctx.render("playlist-select", Map.of(
                "memberships", memberships,
                "movieId", ids.getFirst(),
                "movieIds", List.of(ids.getFirst())));
        } else {
            var memberships = playlistsService.playlists(userId).stream()
                .map(p -> new PlaylistMembership(p.id(), p.name(), false))
                .toList();
            var model = new HashMap<String, Object>();
            model.put("memberships", memberships);
            model.put("movieId", null);
            model.put("movieIds", ids);
            // "Add all to playlist" from /marked: after the movies land in a
            // playlist the marks are cleared (compose marks → name the list)
            model.put("clearMarked", "marked".equals(ctx.queryParam("from")) && ids.size() > 1);
            ctx.render("playlist-select", model);
        }
    }

    private void create(Context ctx) {
        var userId = userId(ctx);
        if (userId == null) {
            ctx.status(401);
            return;
        }
        var name = PlaylistsService.validName(ctx.formParam("name"));
        if (name.isEmpty()) {
            ctx.status(400);
            return;
        }
        var movieIds = MovieIds.validOf(ctx.formParams("movie"));
        var id = playlistsService.createPlaylist(userId, name.get());
        if (!movieIds.isEmpty()) {
            playlistsService.addMovies(userId, id, movieIds);
        }
        clearMarkedIfRequested(ctx, userId, movieIds);
        if (movieIds.size() == 1) {
            // movie-page dialog: stay in the dialog, show the new playlist checked
            ctx.render("playlist-select", Map.of(
                "memberships", playlistsService.playlistsWithMovie(userId, movieIds.getFirst()),
                "movieId", movieIds.getFirst(),
                "movieIds", List.of(movieIds.getFirst())));
            return;
        }
        navigate(ctx, "/playlists/" + id);
    }

    private void rename(Context ctx) {
        var userId = userId(ctx);
        if (userId == null) {
            ctx.status(401);
            return;
        }
        var id = parseId(ctx);
        var name = PlaylistsService.validName(ctx.formParam("name"));
        if (name.isPresent()) {
            if (!playlistsService.renamePlaylist(userId, id, name.get())) {
                throw new NotFoundException("Playlist not found: " + id);
            }
        }
        navigate(ctx, "/playlists/" + id);
    }

    private void delete(Context ctx) {
        var userId = userId(ctx);
        if (userId == null) {
            ctx.status(401);
            return;
        }
        var id = parseId(ctx);
        if (!playlistsService.deletePlaylist(userId, id)) {
            throw new NotFoundException("Playlist not found: " + id);
        }
        navigate(ctx, "/playlists");
    }

    private void addMovies(Context ctx) {
        var userId = userId(ctx);
        if (userId == null) {
            ctx.status(401);
            return;
        }
        var id = parseId(ctx);
        var movieIds = MovieIds.validOf(ctx.formParams("movie"));
        playlistsService.addMovies(userId, id, movieIds);
        clearMarkedIfRequested(ctx, userId, movieIds);
        navigate(ctx, "/playlists/" + id);
    }

    private void addMovie(Context ctx) {
        mutateMembership(ctx, playlistsService::addMovie);
    }

    private void removeMovie(Context ctx) {
        mutateMembership(ctx, playlistsService::removeMovie);
    }

    private void mutateMembership(Context ctx, MembershipOperation operation) {
        var userId = userId(ctx);
        if (userId == null) {
            ctx.status(401);
            return;
        }
        var id = parseId(ctx);
        var rawMovieId = ctx.pathParam("movieId");
        if (!MovieIds.isValid(rawMovieId)) {
            ctx.status(400);
            return;
        }
        var movieId = Long.parseLong(rawMovieId);
        operation.apply(userId, id, movieId);
        // the dialog refreshes itself; the chips div travels out-of-band
        ctx.render("playlist-select", Map.of(
            "memberships", playlistsService.playlistsWithMovie(userId, movieId),
            "movieId", movieId,
            "movieIds", List.of(movieId)));
    }

    // the /marked dialog sends clearMarked=true: composing a playlist out of
    // your marks consumes them — only the movies actually added are unmarked
    private void clearMarkedIfRequested(Context ctx, long userId, List<Long> movieIds) {
        if ("true".equals(ctx.formParam("clearMarked")) && !movieIds.isEmpty()) {
            marksService.unmarkAll(userId, movieIds);
        }
    }

    // fetch-based dialog calls get HX-Redirect (htmx navigates); boosted/plain
    // forms get a plain 303 the browser follows
    private static void navigate(Context ctx, String location) {
        if ("true".equalsIgnoreCase(ctx.header("HX-Request"))) {
            ctx.header("HX-Redirect", location);
        } else {
            ctx.redirect(location, HttpStatus.SEE_OTHER);
        }
    }

    private static long parseId(Context ctx) {
        var raw = ctx.pathParam("id");
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new NotFoundException("Invalid playlist id: " + raw);
        }
    }

    private static Long userId(Context ctx) {
        return ctx.<Long>attribute("userId");
    }

    @FunctionalInterface
    private interface MembershipOperation {
        void apply(long userId, long playlistId, long movieId);
    }
}