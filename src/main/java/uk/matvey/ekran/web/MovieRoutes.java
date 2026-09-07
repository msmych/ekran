package uk.matvey.ekran.web;

import static java.util.Map.of;

import io.javalin.Javalin;
import io.javalin.http.Context;

import uk.matvey.ekran.domain.NotFoundException;
import uk.matvey.ekran.service.MovieService;
import uk.matvey.ekran.web.viewmodels.MovieDetailVm;

public class MovieRoutes {

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
        ctx.render("movie", of("vm", MovieDetailVm.of(movie)));
    }

    private long parseId(Context ctx) {
        var raw = ctx.pathParam("id");
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new NotFoundException("Invalid id: " + raw);
        }
    }
}