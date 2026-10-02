package uk.matvey.ekran.web;

import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import uk.matvey.ekran.domain.NotFoundException;
import uk.matvey.ekran.marks.MarksService;
import uk.matvey.ekran.playlists.PlaylistsService;
import uk.matvey.ekran.service.MovieService;
import uk.matvey.ekran.web.viewmodels.MovieDetailVm;
import uk.matvey.ekran.web.viewmodels.OgVm;

public class MovieRoutes extends Routes {

    private final MovieService movieService;
    private final PlaylistsService playlistsService;
    private final MarksService marksService;

    public MovieRoutes(MovieService movieService, PlaylistsService playlistsService, MarksService marksService) {
        this.movieService = movieService;
        this.playlistsService = playlistsService;
        this.marksService = marksService;
    }

    public void register(Javalin app) {
        app.get("/movies/{id}", this::movie);
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
            model.put("markNote", marksService.markNote(userId, id));
            var memberships = playlistsService.playlistsWithMovie(userId, id);
            model.put("memberships", memberships);
            model.put("hasMembers", PlaylistsService.hasMemberships(memberships));
        }
        ctx.render("movie", model);
    }
}
