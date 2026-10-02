package uk.matvey.ekran.web;

import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.Map;
import uk.matvey.ekran.domain.MovieIds;
import uk.matvey.ekran.domain.NotFoundException;
import uk.matvey.ekran.service.MovieService;
import uk.matvey.ekran.web.viewmodels.MovieCardVm;

public class ListRoutes extends Routes {

    private static final int MAX_NAME = 60;

    private final MovieService movieService;

    public ListRoutes(MovieService movieService) {
        this.movieService = movieService;
    }

    public void register(Javalin app) {
        app.get("/list", this::list);
        app.get("/list/card", this::card);
    }

    private void list(Context ctx) {
        var ids = MovieIds.validOf(ctx.queryParams("movie"));
        var cards = movieService.findByIds(ids).stream().map(MovieCardVm::of).toList();
        ctx.render("list", Map.of("cards", cards, "title", title(ctx.queryParam("name")), "movieIds", ids));
    }

    // fragment for one card — used by marked.js when a movie is marked from the
    // search overlay while viewing /list, so the card can join the view right away
    private void card(Context ctx) {
        var ids = MovieIds.validOf(ctx.queryParams("movie"));
        if (ids.size() != 1) {
            throw new NotFoundException("Expected exactly one movie id");
        }
        var movie = movieService.findById(ids.getFirst())
            .orElseThrow(() -> new NotFoundException("Movie not found: " + ids.getFirst()));
        ctx.render("list-card", Map.of("c", MovieCardVm.of(movie)));
    }

    private String title(String name) {
        if (name == null) {
            return "Marked movies";
        }
        var trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return "Marked movies";
        }
        return trimmed.length() <= MAX_NAME ? trimmed : trimmed.substring(0, MAX_NAME);
    }
}
