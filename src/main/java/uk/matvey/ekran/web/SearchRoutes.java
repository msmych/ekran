package uk.matvey.ekran.web;

import static java.util.Map.of;

import io.javalin.Javalin;
import io.javalin.http.Context;

import uk.matvey.ekran.service.SearchService;
import uk.matvey.ekran.web.viewmodels.SearchResultsVm;

public class SearchRoutes {

    private final SearchService searchService;

    public SearchRoutes(SearchService searchService) {
        this.searchService = searchService;
    }

    public void register(Javalin app) {
        app.get("/", ctx -> renderHome(ctx, normalize(ctx.queryParam("q"))));
        app.get("/search", this::search);
    }

    private void search(Context ctx) {
        var normalized = normalize(ctx.queryParam("q"));
        if (isHtmx(ctx)) {
            ctx.header("Cache-Control", "no-store");
            if (normalized.isEmpty()) {
                // truly empty body (not a whitespace-only fragment) so the client-side
                // :empty-based panel visibility collapses the overlay/home results container
                ctx.result("");
            } else {
                ctx.render("results", of("resultsVm", SearchResultsVm.of(normalized, searchService.search(normalized))));
            }
        } else {
            renderHome(ctx, normalized);
        }
    }

    private void renderHome(Context ctx, String normalized) {
        if (normalized.isEmpty()) {
            ctx.render("home", of());
        } else {
            ctx.render("home", of("q", normalized, "resultsVm", SearchResultsVm.of(normalized, searchService.search(normalized))));
        }
    }

    private String normalize(String query) {
        return query == null ? "" : query.trim();
    }

    private boolean isHtmx(Context ctx) {
        return "true".equalsIgnoreCase(ctx.header("HX-Request"));
    }
}