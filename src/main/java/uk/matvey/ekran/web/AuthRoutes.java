package uk.matvey.ekran.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.HttpStatus;
import io.javalin.http.SameSite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.matvey.ekran.auth.AuthService;
import uk.matvey.ekran.auth.Emails;
import uk.matvey.ekran.auth.NextUrl;
import uk.matvey.ekran.email.EmailException;

public class AuthRoutes {

    private static final Logger log = LoggerFactory.getLogger(AuthRoutes.class);

    private static final String INVALID_LINK =
        "This sign-in link has expired or was already used. Request a new one.";

    private final AuthService authService;
    private final boolean secureCookies;

    public AuthRoutes(AuthService authService, boolean secureCookies) {
        this.authService = authService;
        this.secureCookies = secureCookies;
    }

    public void register(Javalin app) {
        app.get("/signin", this::signin);
        app.post("/signin", this::requestLink);
        app.get("/signin/sent", this::sent);
        app.get("/auth/link", this::consumeLink);
        app.post("/signout", this::signout);
        app.get("/account", this::account);
    }

    private void signin(Context ctx) {
        if (ctx.<String>attribute("userEmail") != null) {
            ctx.redirect("/", HttpStatus.SEE_OTHER);
            return;
        }
        ctx.render("signin", model("next", NextUrl.safe(ctx.queryParam("next"))));
    }

    private void requestLink(Context ctx) {
        var email = ctx.formParam("email");
        var next = NextUrl.safe(ctx.formParam("next"));
        if (!Emails.isValid(email)) {
            ctx.status(400);
            ctx.render("signin", model("next", next, "error", "Enter a valid email address."));
            return;
        }
        try {
            authService.requestMagicLink(email, clientIp(ctx), next);
        } catch (EmailException e) {
            log.warn("magic-link email delivery failed: {}", e.getMessage());
            ctx.status(503);
            ctx.render("signin", model("next", next, "error",
                "Couldn't send the email right now. Please try again in a moment."));
            return;
        }
        ctx.redirect("/signin/sent?email=" + URLEncoder.encode(Emails.normalize(email), StandardCharsets.UTF_8), HttpStatus.SEE_OTHER);
    }

    private void sent(Context ctx) {
        ctx.render("signin-sent", model(
            "email", ctx.queryParam("email"),
            "ttlMinutes", authService.tokenTtlMinutes()));
    }

    private void consumeLink(Context ctx) {
        var token = ctx.queryParam("token");
        var next = NextUrl.safe(ctx.queryParam("next"));
        if (token == null || token.isBlank()) {
            ctx.render("signin", model("next", next, "error", INVALID_LINK));
            return;
        }
        var session = authService.consumeLoginLink(token.trim());
        if (session.isEmpty()) {
            ctx.render("signin", model("next", next, "error", INVALID_LINK));
            return;
        }
        setSessionCookie(ctx, session.get().id());
        ctx.redirect(next, HttpStatus.SEE_OTHER);
    }

    private void signout(Context ctx) {
        var sessionId = ctx.cookie(authService.sessionCookieName());
        if (sessionId != null) {
            authService.signOut(sessionId);
        }
        ctx.removeCookie(authService.sessionCookieName(), "/");
        ctx.redirect(NextUrl.safe(ctx.formParam("next")), HttpStatus.SEE_OTHER);
    }

    private void account(Context ctx) {
        var email = ctx.<String>attribute("userEmail");
        if (email == null) {
            ctx.redirect("/signin?next=" + URLEncoder.encode("/account", StandardCharsets.UTF_8), HttpStatus.SEE_OTHER);
            return;
        }
        ctx.render("account", model("userEmail", email));
    }

    private void setSessionCookie(Context ctx, String sessionId) {
        var cookie = new Cookie(authService.sessionCookieName(), sessionId);
        cookie.setPath("/");
        cookie.setMaxAge((int) authService.sessionMaxAgeSeconds());
        cookie.setHttpOnly(true);
        cookie.setSecure(secureCookies);
        cookie.setSameSite(SameSite.LAX);
        ctx.cookie(cookie);
    }

    // nginx appends the real client address as the last X-Forwarded-For entry;
    // client-supplied entries come before it and are never trusted
    private static String clientIp(Context ctx) {
        var forwarded = ctx.header("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            var entries = forwarded.split(",");
            return entries[entries.length - 1].trim();
        }
        return ctx.ip();
    }

    private static Map<String, Object> model(Object... keyValuePairs) {
        var model = new LinkedHashMap<String, Object>();
        for (var i = 0; i < keyValuePairs.length; i += 2) {
            model.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return model;
    }
}