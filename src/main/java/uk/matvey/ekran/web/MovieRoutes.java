package uk.matvey.ekran.web;

import java.util.HashMap;
import java.util.Map;

import io.javalin.Javalin;
import io.javalin.http.Context;

import uk.matvey.ekran.domain.NotFoundException;
import uk.matvey.ekran.playlists.PlaylistsService;
import uk.matvey.ekran.service.MovieService;
import uk.matvey.ekran.web.viewmodels.MovieDetailVm;
import uk.matvey.ekran.web.viewmodels.OgVm;

public class MovieRoutes extends Routes {

    private final MovieService movieService;
    private final PlaylistsService playlistsService;

    public MovieRoutes(MovieService movieService, PlaylistsService playlistsService) {
        this.movieService = movieService;
        this.playlistsService = playlistsService;
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
            var markedIds = ctx.<java.util.List<Long>>attribute("markedIds");
            model.put("marked", markedIds.contains(id));
            model.put("memberships", playlistsService.playlistsWithMovie(userId, id));
        }
        ctx.render("movie", model);
    }
}