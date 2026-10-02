package uk.matvey.ekran.web;

import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.UnauthorizedResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import uk.matvey.ekran.domain.NotFoundException;

/**
 * Cross-route plumbing shared by every route group: the session-resolved
 * user (never a browser-supplied id), auth guards, htmx detection, and the
 * note fragments both marks and playlists render.
 */
public abstract class Routes {

    protected static long parseId(Context ctx) {
        var raw = ctx.pathParam("id");
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new NotFoundException("Invalid id: " + raw);
        }
    }

    // the session middleware has already resolved the user; null means anonymous
    protected static Long userId(Context ctx) {
        return ctx.<Long>attribute("userId");
    }

    // API/fragment endpoints: the browser only checks the status code
    protected static long requireUser(Context ctx) {
        var userId = userId(ctx);
        if (userId == null) {
            throw new UnauthorizedResponse();
        }
        return userId;
    }

    // page endpoints: anonymous visitors are sent to sign in and come back afterwards
    protected static void redirectToSignin(Context ctx, String path) {
        ctx.redirect("/signin?next=" + URLEncoder.encode(path, StandardCharsets.UTF_8), HttpStatus.SEE_OTHER);
    }

    protected static boolean htmx(Context ctx) {
        return "true".equalsIgnoreCase(ctx.header("HX-Request"));
    }

    // fetch-based dialog calls get HX-Redirect (htmx navigates); boosted/plain
    // forms get a plain 303 the browser follows
    protected static void navigate(Context ctx, String location) {
        if (htmx(ctx)) {
            ctx.header("HX-Redirect", location);
        } else {
            ctx.redirect(location, HttpStatus.SEE_OTHER);
        }
    }

    // the shared note fragments: display area (the ✕ cancel target) and editor
    protected static void renderNoteArea(Context ctx, String note, String editUrl) {
        var model = new HashMap<String, Object>();
        model.put("note", note);
        model.put("editUrl", editUrl);
        ctx.render("note-area", model);
    }

    protected static void renderNoteEditor(Context ctx, String note, String url) {
        var model = new HashMap<String, Object>();
        model.put("note", note);
        model.put("url", url);
        ctx.render("note-editor", model);
    }
}
