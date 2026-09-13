package uk.matvey.ekran.web;

import io.javalin.http.Context;

import uk.matvey.ekran.domain.NotFoundException;

public abstract class Routes {

    protected static long parseId(Context ctx) {
        var raw = ctx.pathParam("id");
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new NotFoundException("Invalid id: " + raw);
        }
    }
}