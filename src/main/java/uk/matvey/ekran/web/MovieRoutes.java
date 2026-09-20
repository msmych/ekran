package uk.matvey.ekran.web;

import java.util.Map;

import io.javalin.Javalin;
import io.javalin.http.Context;

import uk.matvey.ekran.domain.NotFoundException;
import uk.matvey.ekran.service.MovieService;
import uk.matvey.ekran.web.viewmodels.MovieDetailVm;
import uk.matvey.ekran.web.viewmodels.OgVm;

public class MovieRoutes extends Routes {

    private final MovieService movieService;

    public MovieRoutes(MovieService movieService) {
        this.movieService = movieService;
    }

    public void register(Javalin app) {
        app.get("/movies/{id}", this::movie);
    }

    private void movie(Context ctx) {
        var id = parseId(ctx);
        var movie = movieService.findById(id)
            .orElseThrow(() -> new NotFoundException("Movie not found: " + id));
        var vm = MovieDetailVm.of(movie);
        ctx.render("movie", Map.of("vm", vm, "og", OgVm.movie(vm, ctx.url())));
    }
}