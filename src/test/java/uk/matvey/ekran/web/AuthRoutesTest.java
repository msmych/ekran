package uk.matvey.ekran.web;

import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import io.javalin.testtools.TestCase;
import io.javalin.testtools.TestConfig;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import uk.matvey.ekran.auth.AuthService;
import uk.matvey.ekran.auth.CapturingEmailService;
import uk.matvey.ekran.auth.InMemoryAuthRepository;
import uk.matvey.ekran.auth.CapturingEmailService.SentEmail;
import uk.matvey.ekran.domain.SearchResultPage;
import uk.matvey.ekran.service.MovieService;
import uk.matvey.ekran.service.PersonService;
import uk.matvey.ekran.service.SearchService;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRoutesTest {

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([A-Za-z0-9_-]{43})");

    // redirect assertions need the raw 3xx — the default client follows them
    private static final OkHttpClient NO_REDIRECTS = new OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build();

    private final InMemoryAuthRepository repository = new InMemoryAuthRepository();
    private final CapturingEmailService emails = new CapturingEmailService();
    private final AuthService authService = new AuthService(
        repository, emails, "https://ekran.test", Duration.ofMinutes(15), Duration.ofDays(30), true, Clock.systemUTC());

    @Test
    void signinPageRendersForm() {
        authTest((server, http) -> {
            var response = http.get("/signin");

            assertThat(response.code()).isEqualTo(200);
            var body = response.body().string();
            assertThat(body).contains("Sign in to ekran");
            assertThat(body).contains("Enter your email and we'll send you a magic link.");
            assertThat(body).contains("action=\"/signin\"");
            assertThat(body).contains("type=\"email\"");
            assertThat(body).contains("Send magic link");
            // unauthenticated header carries the unobtrusive Sign in entry
            assertThat(body).contains("Sign in</a>");
            assertThat(body).doesNotContain("data-account-menu");
        });
    }

    @Test
    void signinRequestRedirectsToGenericSentPage() {
        authTest((server, http) -> {
            var response = postForm(http, "/signin",
                "email", "foo@bar.com",
                "next", "/movies/348");

            assertThat(response.code()).isEqualTo(303);
            assertThat(response.header("Location")).isEqualTo("/signin/sent?email=foo%40bar.com");
            assertThat(emails.sent).hasSize(1);
            assertThat(emails.sent.getFirst().to()).isEqualTo("foo@bar.com");

            var sent = http.get("/signin/sent?email=foo%40bar.com");
            assertThat(sent.code()).isEqualTo(200);
            var body = sent.body().string();
            assertThat(body).contains("Check your email");
            assertThat(body).contains("foo@bar.com");
            assertThat(body).contains("If an account can be created or signed in with this email, we've sent you a link.");
            assertThat(body).contains("expires in 15 minutes");
        });
    }

    @Test
    void invalidEmailReRendersFormWithFriendlyError() {
        authTest((server, http) -> {
            var response = postForm(http, "/signin", "email", "not-an-email");

            assertThat(response.code()).isEqualTo(400);
            assertThat(response.body().string()).contains("Enter a valid email address.");
            assertThat(emails.sent).isEmpty();
        });
    }

    @Test
    void rateLimitedRequestsGetIdenticalGenericResponses() {
        authTest((server, http) -> {
            for (var i = 0; i < 6; i++) {
                var response = postForm(http, "/signin", "email", "foo@bar.com");
                assertThat(response.code()).isEqualTo(303);
                assertThat(response.header("Location")).isEqualTo("/signin/sent?email=foo%40bar.com");
            }
            // the 6th request was rate-limited: no email went out, the response was the same
            assertThat(emails.sent).hasSize(5);
        });
    }

    @Test
    void magicLinkSignsInSetsCookieAndRedirectsBack() {
        authTest((server, http) -> {
            postForm(http, "/signin", "email", "foo@bar.com", "next", "/movies/348");
            var token = rawTokenOf(emails.sent.getFirst());

            var response = http.get("/auth/link?token=" + token + "&next=%2Fmovies%2F348");

            assertThat(response.code()).isEqualTo(303);
            assertThat(response.header("Location")).isEqualTo("/movies/348");
            var setCookie = response.header("Set-Cookie");
            assertThat(setCookie).contains("__Host-ekran_session=");
            assertThat(setCookie).contains("HttpOnly");
            assertThat(setCookie).contains("Secure");
            assertThat(setCookie).contains("SameSite=Lax");
            assertThat(setCookie).contains("Path=/");

            // signed-in pages show the account menu instead of Sign in
            var sessionId = cookieValue(setCookie);
            var authed = http.get("/about", r -> r.header("Cookie", "__Host-ekran_session=" + sessionId));
            assertThat(authed.code()).isEqualTo(200);
            var authedBody = authed.body().string();
            assertThat(authedBody).contains("data-account-menu");
            assertThat(authedBody).contains("foo@bar.com");
            assertThat(authedBody).doesNotContain(">Sign in</a>");

            var anonymous = http.get("/about");
            assertThat(anonymous.body().string()).contains("Sign in</a>");
        });
    }

    @Test
    void openRedirectsAreRejectedAtLinkConsumption() {
        authTest((server, http) -> {
            postForm(http, "/signin", "email", "foo@bar.com");
            var token = rawTokenOf(emails.sent.getFirst());

            var absolute = http.get("/auth/link?token=" + token + "&next=https%3A%2F%2Fevil.com");
            // the token was consumed by this request — request a fresh one for the second case
            assertThat(absolute.header("Location")).isEqualTo("/");

            postForm(http, "/signin", "email", "other@bar.com");
            var secondToken = rawTokenOf(emails.sent.getLast());
            var protocolRelative = http.get("/auth/link?token=" + secondToken + "&next=%2F%2Fevil.com");
            assertThat(protocolRelative.header("Location")).isEqualTo("/");
        });
    }

    @Test
    void invalidOrUsedTokenRendersFriendlyPage() {
        authTest((server, http) -> {
            var noToken = http.get("/auth/link");
            assertThat(noToken.code()).isEqualTo(200);
            assertThat(noToken.body().string()).contains("expired or was already used");

            var bogus = http.get("/auth/link?token=bogusbogusbogusbogusbogusbogusbogus1234");
            assertThat(bogus.code()).isEqualTo(200);
            assertThat(bogus.body().string()).contains("expired or was already used");

            // a consumed token cannot be reused
            postForm(http, "/signin", "email", "foo@bar.com");
            var token = rawTokenOf(emails.sent.getFirst());
            http.get("/auth/link?token=" + token);
            var reuse = http.get("/auth/link?token=" + token);
            assertThat(reuse.code()).isEqualTo(200);
            assertThat(reuse.body().string()).contains("expired or was already used");
        });
    }

    @Test
    void accountPageRequiresSignIn() {
        authTest((server, http) -> {
            var anonymous = http.get("/account");
            assertThat(anonymous.code()).isEqualTo(303);
            assertThat(anonymous.header("Location")).isEqualTo("/signin?next=%2Faccount");

            var sessionId = signIn(http, "foo@bar.com");
            var authed = http.get("/account", r -> r.header("Cookie", "__Host-ekran_session=" + sessionId));
            assertThat(authed.code()).isEqualTo(200);
            var body = authed.body().string();
            assertThat(body).contains("Account");
            assertThat(body).contains("foo@bar.com");
            assertThat(body).contains("Sign out");
        });
    }

    @Test
    void signinPageRedirectsWhenAlreadySignedIn() {
        authTest((server, http) -> {
            var sessionId = signIn(http, "foo@bar.com");

            var response = http.get("/signin", r -> r.header("Cookie", "__Host-ekran_session=" + sessionId));

            assertThat(response.code()).isEqualTo(303);
            assertThat(response.header("Location")).isEqualTo("/");
        });
    }

    @Test
    void signOutInvalidatesServerSessionAndCookie() {
        authTest((server, http) -> {
            var sessionId = signIn(http, "foo@bar.com");

            var response = http.request("/signout", r ->
                r.header("Cookie", "__Host-ekran_session=" + sessionId)
                    .post(new FormBody.Builder().add("next", "/about").build()));

            assertThat(response.code()).isEqualTo(303);
            assertThat(response.header("Location")).isEqualTo("/about");
            assertThat(response.header("Set-Cookie")).contains("Max-Age=0");
            assertThat(repository.hasSessionForRaw(sessionId)).isFalse();

            var after = http.get("/account", r -> r.header("Cookie", "__Host-ekran_session=" + sessionId));
            assertThat(after.code()).isEqualTo(303);
            assertThat(after.header("Location")).isEqualTo("/signin?next=%2Faccount");
        });
    }

    private String signIn(io.javalin.testtools.HttpClient http, String email) {
        postForm(http, "/signin", "email", email);
        var token = rawTokenOf(emails.sent.getLast());
        var response = http.get("/auth/link?token=" + token);
        return cookieValue(response.header("Set-Cookie"));
    }

    private static Response postForm(io.javalin.testtools.HttpClient http, String path, String... keyValuePairs) {
        var form = new FormBody.Builder();
        for (var i = 0; i < keyValuePairs.length; i += 2) {
            form.add(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return http.request(path, r -> r.post(form.build()));
    }

    private void authTest(TestCase testCase) {
        JavalinTest.test(app(), new TestConfig(false, false, NO_REDIRECTS), testCase);
    }

    private static String cookieValue(String setCookie) {
        return setCookie.split(";")[0].split("=", 2)[1];
    }

    private static String rawTokenOf(SentEmail email) {
        var matcher = TOKEN_IN_LINK.matcher(email.text());
        assertThat(matcher.find()).as("magic link with token in email").isTrue();
        return matcher.group(1);
    }

    private Javalin app() {
        return EkranApp.create(
            new SearchService((q, p, t) -> new SearchResultPage(List.of())),
            new MovieService(id -> java.util.Optional.empty()),
            new PersonService(id -> java.util.Optional.empty()),
            authService,
            true,
            null
        );
    }
}