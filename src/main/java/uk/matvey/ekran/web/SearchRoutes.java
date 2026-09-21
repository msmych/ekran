package uk.matvey.ekran.web;

import java.util.Map;

import io.javalin.Javalin;
import io.javalin.http.Context;

import uk.matvey.ekran.domain.SearchType;
import uk.matvey.ekran.service.SearchService;
import uk.matvey.ekran.web.viewmodels.SearchResultsVm;

public class SearchRoutes {

    private static final String PERSON_TYPE_PARAM = "person";

    private final SearchService searchService;

    public SearchRoutes(SearchService searchService) {
        this.searchService = searchService;
    }

    public void register(Javalin app) {
        app.get("/", ctx -> renderHome(ctx, normalize(ctx.queryParam("q")), searchType(ctx)));
        app.get("/search", this::search);
    }

    private void search(Context ctx) {
        var normalized = normalize(ctx.queryParam("q"));
        var type = searchType(ctx);
        if (isHtmx(ctx)) {
            ctx.header("Cache-Control", "no-store");
            if (normalized.isEmpty()) {
                // truly empty body (not a whitespace-only fragment) so the client-side
                // :empty-based panel visibility collapses the overlay/home results container
                ctx.result("");
            } else {
                ctx.render("results", Map.of("resultsVm", SearchResultsVm.of(normalized, searchService.search(normalized, type))));
            }
        } else {
            renderHome(ctx, normalized, type);
        }
    }

    private void renderHome(Context ctx, String normalized, SearchType type) {
        if (normalized.isEmpty()) {
            ctx.render("home", Map.of("personSearch", type == SearchType.PERSON));
        } else {
            ctx.render("home", Map.of("q", normalized, "personSearch", type == SearchType.PERSON,
                "resultsVm", SearchResultsVm.of(normalized, searchService.search(normalized, type))));
        }
    }

    private String normalize(String query) {
        return query == null ? "" : query.trim();
    }

    private SearchType searchType(Context ctx) {
        return PERSON_TYPE_PARAM.equalsIgnoreCase(ctx.queryParam("type")) ? SearchType.PERSON : SearchType.MOVIE;
    }

    private boolean isHtmx(Context ctx) {
        return "true".equalsIgnoreCase(ctx.header("HX-Request"));
    }
}