package uk.matvey.ekran.web;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.javalin.Javalin;
import io.javalin.http.Context;

import uk.matvey.ekran.service.MovieService;
import uk.matvey.ekran.web.viewmodels.MovieCardVm;

public class ListRoutes {

    private static final int MAX_MOVIES = 100;

    private static final Pattern MOVIE_ID = Pattern.compile("[1-9][0-9]{0,9}");

    private final MovieService movieService;

    public ListRoutes(MovieService movieService) {
        this.movieService = movieService;
    }

    public void register(Javalin app) {
        app.get("/list", this::list);
    }

    private void list(Context ctx) {
        var ids = movieIds(ctx.queryParams("movie"));
        var cards = movieService.findByIds(ids).stream().map(MovieCardVm::of).toList();
        ctx.render("list", Map.of("cards", cards));
    }

    private List<Long> movieIds(List<String> params) {
        var unique = new LinkedHashSet<Long>();
        for (var param : params) {
            var raw = param == null ? "" : param.trim();
            if (MOVIE_ID.matcher(raw).matches()) {
                unique.add(Long.parseLong(raw));
            }
        }
        return unique.stream().limit(MAX_MOVIES).toList();
    }
}