package uk.matvey.ekran.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import io.javalin.testtools.TestCase;
import io.javalin.testtools.TestConfig;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import uk.matvey.ekran.auth.AuthService;
import uk.matvey.ekran.auth.CapturingEmailService;
import uk.matvey.ekran.auth.CapturingEmailService.SentEmail;
import uk.matvey.ekran.auth.InMemoryAuthRepository;
import uk.matvey.ekran.domain.Movie;
import uk.matvey.ekran.domain.SearchResultPage;
import uk.matvey.ekran.marks.InMemoryMarksRepository;
import uk.matvey.ekran.marks.MarksService;
import uk.matvey.ekran.playlists.InMemoryPlaylistsRepository;
import uk.matvey.ekran.playlists.PlaylistsService;
import uk.matvey.ekran.service.MovieService;
import uk.matvey.ekran.service.PersonService;
import uk.matvey.ekran.service.SearchService;

class AuthRoutesTest {

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([A-Za-z0-9_-]{43})");

    private static final Movie GODFATHER_MOVIE = new Movie(
        238, "The Godfather", null, null, null, List.of(), null, null, null, null,
        List.of(), List.of(), List.of(), null, List.of());

    private static final Movie PULP_MOVIE = new Movie(
        680, "Pulp Fiction", null, null, null, List.of(), null, null, null, null,
        List.of(), List.of(), List.of(), null, List.of());

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

    @Test
    void markedPageRequiresAuthentication() {
        authTest((server, http) -> {
            var response = http.get("/marked");

            assertThat(response.code()).isEqualTo(303);
            assertThat(response.header("Location")).startsWith("/signin");
        });
    }

    @Test
    void markedEndpointsRejectAnonymousMutations() {
        authTest((server, http) -> {
            assertThat(postForm(http, "/marked/238").code()).isEqualTo(401);
            assertThat(http.request("/marked/238", r -> r.method("DELETE", null)).code()).isEqualTo(401);
            assertThat(postForm(http, "/marked", "movie", "238").code()).isEqualTo(401);
        });
    }

    @Test
    void authenticatedMarkingIsServerBacked() {
        authTest((server, http) -> {
            var sessionId = signIn(http, "marker@bar.com");

            assertThat(http.request("/marked/238", r -> r.post(noBody())).code()).isEqualTo(401);
            var response = http.request("/marked/238", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(noBody());
            });
            assertThat(response.code()).isEqualTo(204);

            assertThat(idsOf(http, sessionId)).isEqualTo("238");
            assertThat(http.get("/marked/238/ids").code()).isEqualTo(404); // no such route — ids live on /marked/ids

            var page = http.get("/marked", r -> r.header("Cookie", cookie(sessionId)));
            assertThat(page.code()).isEqualTo(200);
            var pageBody = page.body().string();
            assertThat(pageBody).contains("Marked movies");
            // the grid ⇄ rows view toggle rides along on every cards surface
            assertThat(pageBody).contains("data-view-toggle");
            // the header seeds the server-side set for marked.js
            var home = http.get("/about", r -> r.header("Cookie", cookie(sessionId)));
            var homeBody = home.body().string();
            assertThat(homeBody).contains("data-authenticated");
            assertThat(homeBody).contains("data-marked-ids=\"238\"");
        });
    }

    @Test
    void markingIsIdempotentAndPerUser() {
        authTest((server, http) -> {
            var alice = signIn(http, "alice@bar.com");
            var bob = signIn(http, "bob@bar.com");

            mark(http, alice, 238);
            mark(http, alice, 238);
            mark(http, bob, 680);

            assertThat(idsOf(http, alice)).isEqualTo("238");
            assertThat(idsOf(http, bob)).isEqualTo("680");
        });
    }

    @Test
    void unmarkingIsIdempotent() {
        authTest((server, http) -> {
            var sessionId = signIn(http, "unmarker@bar.com");
            mark(http, sessionId, 238);
            unmark(http, sessionId, 238);
            unmark(http, sessionId, 238);

            assertThat(idsOf(http, sessionId)).isEmpty();
        });
    }

    @Test
    void bulkMergeIsTolerantOfBadIdsAndIdempotent() {
        authTest((server, http) -> {
            var sessionId = signIn(http, "merge@bar.com");

            var merge = http.request("/marked", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(new FormBody.Builder()
                    .add("movie", "238")
                    .add("movie", "not-an-id")
                    .add("movie", "680")
                    .add("movie", "238")
                    .add("movie", "-1")
                    .build());
            });
            assertThat(merge.code()).isEqualTo(204);
            merge = http.request("/marked", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(new FormBody.Builder().add("movie", "238").add("movie", "680").build());
            });
            assertThat(merge.code()).isEqualTo(204);

            assertThat(idsOf(http, sessionId)).isEqualTo("238,680");
        });
    }

    @Test
    void invalidSingleMovieIdIsRejected() {
        authTest((server, http) -> {
            var sessionId = signIn(http, "validator@bar.com");

            assertThat(http.request("/marked/abc", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(noBody());
            }).code()).isEqualTo(400);
        });
    }

    @Test
    void bulkUnmarkOnlyTouchesGivenIds() {
        authTest((server, http) -> {
            var sessionId = signIn(http, "bulk-unmark@bar.com");
            mark(http, sessionId, 238);
            mark(http, sessionId, 680);

            var response = http.request("/marked", r -> {
                r.header("Cookie", cookie(sessionId));
                r.method("DELETE", new FormBody.Builder().add("movie", "238").build());
            });
            assertThat(response.code()).isEqualTo(204);

            assertThat(idsOf(http, sessionId)).isEqualTo("680");
        });
    }

    @Test
    void playlistsPageRequiresAuthentication() {
        authTest((server, http) -> {
            var response = http.get("/playlists");

            assertThat(response.code()).isEqualTo(303);
            assertThat(response.header("Location")).startsWith("/signin");
        });
    }

    @Test
    void playlistCrudLifecycle() {
        authTest((server, http) -> {
            var sessionId = signIn(http, "curator@bar.com");

            // create from the index form (boosted-style plain POST)
            var create = http.request("/playlists", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(new FormBody.Builder().add("name", "Sci-fi").build());
            });
            assertThat(create.code()).isEqualTo(303);
            var location = create.header("Location");

            var index = http.get("/playlists", r -> r.header("Cookie", cookie(sessionId)));
            var indexBody = index.body().string();
            assertThat(indexBody).contains("Sci-fi");
            assertThat(indexBody).contains("0 movies");
            assertThat(location).matches("/playlists/\\d+");

            var playlistId = location.substring("/playlists/".length());

            // the account popup shows the playlist count, like the marked count
            var about = http.get("/about", r -> r.header("Cookie", cookie(sessionId)));
            assertThat(about.body().string()).contains("Playlists · <span>1</span>");

            // add membership, then the detail page shows the dialog wiring
            var add = http.request("/playlists/" + playlistId + "/movies/238", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(noBody());
            });
            assertThat(add.code()).isEqualTo(200);
            var addBody = add.body().string();
            assertThat(addBody).contains("Sci-fi");
            // the dialog pick button still toggles membership
            assertThat(addBody).contains("hx-delete=\"/playlists/" + playlistId + "/movies/238\"");
            // out-of-band chips refresh for the movie page:
            // the chip is a marker link now, and the button flips to Edit
            assertThat(addBody).contains("hx-swap-oob=\"true\"");
            assertThat(addBody).contains("class=\"playlist-chip on\"");
            assertThat(addBody).contains("href=\"/playlists/" + playlistId + "\">Sci-fi</a>");
            assertThat(addBody).contains(">Edit</button>");

            var detail = http.get("/playlists/" + playlistId, r -> r.header("Cookie", cookie(sessionId)));
            var detailBody = detail.body().string();
            assertThat(detailBody).contains("Rename");
            assertThat(detailBody).contains("/playlists/" + playlistId + "/delete");
            assertThat(detailBody).contains("movie=238");

            // rename
            http.request("/playlists/" + playlistId + "/rename", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(new FormBody.Builder().add("name", "Space westerns").build());
            });
            assertThat(http.get("/playlists/" + playlistId, r -> r.header("Cookie", cookie(sessionId)))
                .body().string()).contains("Space westerns");

            // remove membership
            var remove = http.request("/playlists/" + playlistId + "/movies/238", r -> {
                r.header("Cookie", cookie(sessionId));
                r.method("DELETE", null);
            });
            assertThat(remove.code()).isEqualTo(200);
            var removeBody = remove.body().string();
            assertThat(removeBody).contains("hx-post=\"/playlists/" + playlistId + "/movies/238\"");
            // with no memberships left, the chips button reverts to + Add
            assertThat(removeBody).contains("+ Add");

            // delete
            var delete = http.request("/playlists/" + playlistId + "/delete", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(new FormBody.Builder().build());
            });
            assertThat(delete.code()).isEqualTo(303);
            assertThat(delete.header("Location")).isEqualTo("/playlists");
            assertThat(http.get("/about", r -> r.header("Cookie", cookie(sessionId)))
                .body().string()).contains("Playlists · <span>0</span>");
        });
    }

    @Test
    void playlistCreateWithMoviesStaysInDialogAndIsBulkIdempotent() {
        authTest((server, http) -> {
            var sessionId = signIn(http, "sharer@bar.com");

            // bulk dialog: no redirect — the picker re-renders with a
            // confirmation, the page the dialog was opened from stays put
            var create = http.request("/playlists", r -> {
                r.header("Cookie", cookie(sessionId));
                r.header("HX-Request", "true");
                r.post(new FormBody.Builder()
                    .add("name", "From a shared list")
                    .add("movie", "238")
                    .add("movie", "680")
                    .add("movie", "238")
                    .build());
            });
            assertThat(create.code()).isEqualTo(200);
            assertThat(create.header("HX-Redirect")).isNull();
            var createBody = create.body().string();
            assertThat(createBody)
                .contains("Created From a shared list with 2 movies")
                .contains("hx-post=\"/playlists/");
            assertThat(createBody).containsPattern("href=\"/playlists/\\d+\"[^>]*>Open playlist");
            var playlistId = playlistIdFromDialog(http, sessionId);

            var bulk = http.request("/playlists/" + playlistId + "/movies", r -> {
                r.header("Cookie", cookie(sessionId));
                r.header("HX-Request", "true");
                r.post(new FormBody.Builder().add("movie", "238").add("movie", "680").build());
            });
            assertThat(bulk.header("HX-Redirect")).isNull();
            var bulkBody = bulk.body().string();
            assertThat(bulkBody).contains("Added 2 movies to From a shared list");
            assertThat(bulkBody)
                .contains("href=\"/playlists/" + playlistId + "\"")
                .contains("Open playlist");

            var detail = http.get("/playlists/" + playlistId, r -> r.header("Cookie", cookie(sessionId)));
            assertThat(detail.body().string()).contains("movie=238");
        });
    }

    @Test
    void playlistOperationsAreOwnershipScoped() {
        authTest((server, http) -> {
            var alice = signIn(http, "pl-alice@bar.com");
            var bob = signIn(http, "pl-bob@bar.com");

            var create = http.request("/playlists", r -> {
                r.header("Cookie", cookie(alice));
                r.post(new FormBody.Builder().add("name", "Alice's").build());
            });
            var playlistId = create.header("Location").substring("/playlists/".length());
            http.request("/playlists/" + playlistId + "/movies/238", r -> {
                r.header("Cookie", cookie(alice));
                r.post(noBody());
            });

            // bob cannot view, mutate, or even detect alice's playlist
            assertThat(http.get("/playlists/" + playlistId, r -> r.header("Cookie", cookie(bob))).code()).isEqualTo(404);
            assertThat(http.request("/playlists/" + playlistId + "/movies/680", r -> {
                r.header("Cookie", cookie(bob));
                r.header("HX-Request", "true");
                r.post(noBody());
            }).code()).isEqualTo(404);
            assertThat(http.request("/playlists/" + playlistId + "/movies/238", r -> {
                r.header("Cookie", cookie(bob));
                r.method("DELETE", null);
            }).code()).isEqualTo(404);
            assertThat(http.request("/playlists/" + playlistId + "/rename", r -> {
                r.header("Cookie", cookie(bob));
                r.post(new FormBody.Builder().add("name", "Bob's").build());
            }).code()).isEqualTo(404);
            assertThat(http.request("/playlists/" + playlistId + "/movies/680/move", r -> {
                r.header("Cookie", cookie(bob));
                r.post(new FormBody.Builder().add("dir", "up").build());
            }).code()).isEqualTo(404);

            // alice's data is untouched
            assertThat(http.get("/playlists/" + playlistId, r -> r.header("Cookie", cookie(alice)))
                .body().string()).contains("Alice&#39;s");
        });
    }

@Test
void playlistMovieMoveSwapsAndWraps() {
    authTest((server, http) -> {
        var sessionId = signIn(http, "orderer@bar.com");
        var create = namedCreate(http, sessionId, "Marathon");
        var playlistId = create.header("Location").substring("/playlists/".length());
        // bulk add preserves the form order: 238, then 680
        http.request("/playlists/" + playlistId + "/movies", r -> {
            r.header("Cookie", cookie(sessionId));
            r.post(new FormBody.Builder().add("movie", "238").add("movie", "680").build());
        });

        // the share URL mirrors the playlist order — move 680 up and it leads
        assertThat(move(http, sessionId, playlistId, 680, "up").code()).isEqualTo(200);
        assertThat(shareUrlOf(http, sessionId, playlistId)).contains("movie=680&amp;movie=238");

        // first moves up → wraps to the end
        assertThat(move(http, sessionId, playlistId, 680, "up").code()).isEqualTo(200);
        assertThat(shareUrlOf(http, sessionId, playlistId)).contains("movie=238&amp;movie=680");

        // last moves down → wraps to the front
        assertThat(move(http, sessionId, playlistId, 680, "down").code()).isEqualTo(200);
        assertThat(shareUrlOf(http, sessionId, playlistId)).contains("movie=680&amp;movie=238");

        // unknown movies are accepted no-ops, bad input is rejected
        assertThat(move(http, sessionId, playlistId, 999, "up").code()).isEqualTo(200);
        assertThat(shareUrlOf(http, sessionId, playlistId)).contains("movie=680&amp;movie=238");
        assertThat(move(http, sessionId, playlistId, 680, "sideways").code()).isEqualTo(400);
        assertThat(http.request("/playlists/" + playlistId + "/movies/abc/move", r -> {
            r.header("Cookie", cookie(sessionId));
            r.post(new FormBody.Builder().add("dir", "up").build());
        }).code()).isEqualTo(400);
    });
}

@Test
void playlistDetailRendersReorderControls() {
    // cards need real movie data — this test gets its own app with stubs
    var app = EkranApp.create(
        new SearchService((q, p, t) -> new SearchResultPage(List.of())),
        new MovieService(id -> id == 238
            ? java.util.Optional.of(GODFATHER_MOVIE)
            : id == 680 ? java.util.Optional.of(PULP_MOVIE) : java.util.Optional.empty()),
        new PersonService(id -> java.util.Optional.empty()),
        authService,
        new MarksService(new InMemoryMarksRepository()),
        new PlaylistsService(new InMemoryPlaylistsRepository()),
        true,
        null);
    JavalinTest.test(app, new TestConfig(false, false, NO_REDIRECTS), (server, http) -> {
        var sessionId = signIn(http, "reorder-viewer@bar.com");
        var create = namedCreate(http, sessionId, "Watch order");
        var playlistId = create.header("Location").substring("/playlists/".length());
        http.request("/playlists/" + playlistId + "/movies", r -> {
            r.header("Cookie", cookie(sessionId));
            r.post(new FormBody.Builder().add("movie", "238").add("movie", "680").build());
        });

        var detail = http.get("/playlists/" + playlistId, r -> r.header("Cookie", cookie(sessionId)));
        var body = detail.body().string();
        // the Reorder toggle and per-card move arrows, wired to the move route
        assertThat(body).contains("data-reorder-toggle");
        assertThat(body).contains(">Reorder</button>");
        assertThat(body).contains("class=\"card-move\"");
        assertThat(body).contains("hx-post=\"/playlists/" + playlistId + "/movies/238/move\"");
        assertThat(body).contains("data-move=\"up\"");
        assertThat(body).contains("data-move=\"down\"");
        // cards render in playlist order
        assertThat(body.indexOf("data-movie-id=\"238\"")).isLessThan(body.indexOf("data-movie-id=\"680\""));

        // the server-side swap shows up in a plain re-render — no JS involved
        assertThat(move(http, sessionId, playlistId, 680, "up").code()).isEqualTo(200);
        var reordered = http.get("/playlists/" + playlistId, r -> r.header("Cookie", cookie(sessionId))).body().string();
        assertThat(reordered.indexOf("data-movie-id=\"680\"")).isLessThan(reordered.indexOf("data-movie-id=\"238\""));

        // other card surfaces (search, /list, /marked, person pages) never get the controls
        assertThat(http.get("/list?movie=238&movie=680").body().string()).doesNotContain("card-move");
    });
}

@Test
void playlistEndpointsRejectAnonymous() {
        authTest((server, http) -> {
            assertThat(postForm(http, "/playlists", "name", "Nope").code()).isEqualTo(401);
            assertThat(http.get("/playlists/select?movie=238").code()).isEqualTo(401);
            assertThat(http.request("/playlists/1/movies/238", r -> r.post(noBody())).code()).isEqualTo(401);
            assertThat(http.request("/playlists/1/movies/238", r -> r.method("DELETE", null)).code()).isEqualTo(401);
            assertThat(http.request("/playlists/1/movies/238/move", r -> r.post(noBody())).code()).isEqualTo(401);
        });
    }

    @Test
    void playlistNameValidation() {
        authTest((server, http) -> {
            var sessionId = signIn(http, "namer@bar.com");

            assertThat(namedCreate(http, sessionId, "   ").code()).isEqualTo(400);
            assertThat(namedCreate(http, sessionId, "x".repeat(61)).code()).isEqualTo(400);
            var created = namedCreate(http, sessionId, "  Padded  ");
            assertThat(created.code()).isEqualTo(303);
            var playlistId = created.header("Location").substring("/playlists/".length());
            assertThat(http.get("/playlists/" + playlistId, r -> r.header("Cookie", cookie(sessionId)))
                .body().string()).contains("Padded");
        });
    }

    @Test
    void selectorFragmentModes() {
        authTest((server, http) -> {
            var sessionId = signIn(http, "selector@bar.com");
            var create = namedCreate(http, sessionId, "Favorites");
            var playlistId = create.header("Location").substring("/playlists/".length());

            // single movie: toggle mode with membership state
            var toggle = http.get("/playlists/select?movie=238", r -> r.header("Cookie", cookie(sessionId)));
            var toggleBody = toggle.body().string();
            assertThat(toggleBody).contains("hx-post=\"/playlists/" + playlistId + "/movies/238\"");
            assertThat(toggleBody).contains("New playlist name");

            // several movies: bulk mode, one add button per playlist; the compose
            // flow never clears marks anymore
            var bulk = http.get("/playlists/select?movie=238&movie=680", r -> r.header("Cookie", cookie(sessionId)));
            var bulkBody = bulk.body().string();
            assertThat(bulkBody).contains("Add 2 movies to…");
            assertThat(bulkBody).contains("hx-post=\"/playlists/" + playlistId + "/movies\"");
            assertThat(bulkBody).doesNotContain("clearMarked");

            // creating from the movie-page dialog keeps the dialog open with the new state
            var dialogCreate = http.request("/playlists", r -> {
                r.header("Cookie", cookie(sessionId));
                r.header("HX-Request", "true");
                r.post(new FormBody.Builder().add("name", "Watch soon").add("movie", "238").build());
            });
            var dialogBody = dialogCreate.body().string();
            assertThat(dialogBody).doesNotContain("HX-Redirect");
            assertThat(dialogBody).contains("hx-delete=\"/playlists/");
            assertThat(dialogBody).contains("/movies/238\"");
        });
    }

@Test
void playlistDialogFromMarkedCopiesMarksAndNotes() {
        authTest((server, http) -> {
            var sessionId = signIn(http, "composer@bar.com");
            http.request("/marked", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(new FormBody.Builder().add("movie", "238").add("movie", "680").build());
            });
            http.request("/marked/238/note", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(new FormBody.Builder().add("note", "diner scene is perfect").build());
            });

            // composing a playlist out of the marks is a copy, not a move:
            // the marks stay and each mark's note carries into the membership;
            // the dialog stays on the marked page with a confirmation
            var create = http.request("/playlists", r -> {
                r.header("Cookie", cookie(sessionId));
                r.header("HX-Request", "true");
                r.post(new FormBody.Builder()
                    .add("name", "Watched in 2026")
                    .add("movie", "238")
                    .add("movie", "680")
                    .build());
            });
            assertThat(create.header("HX-Redirect")).isNull();
            assertThat(create.body().string()).contains("Created Watched in 2026 with 2 movies");
            var playlistId = playlistIdFromDialog(http, sessionId);
            assertThat(idsOf(http, sessionId)).isEqualTo("238,680");
            // the mark note was carried into the membership
            var membershipNote = http.get("/playlists/" + playlistId + "/movies/238/note",
                r -> r.header("Cookie", cookie(sessionId)));
            assertThat(membershipNote.body().string()).contains("diner scene is perfect");
            assertThat(http.get("/playlists/" + playlistId + "/movies/680/note",
                r -> r.header("Cookie", cookie(sessionId))).body().string()).contains("+ note");

            // bulk-adding to an existing playlist keeps the marks too, and an
            // existing membership keeps its note (no overwrite on re-add)
            http.request("/playlists/" + playlistId + "/movies", r -> {
                r.header("Cookie", cookie(sessionId));
                r.header("HX-Request", "true");
                r.post(new FormBody.Builder().add("movie", "238").add("movie", "680").build());
            });
            assertThat(idsOf(http, sessionId)).isEqualTo("238,680");
            assertThat(http.get("/playlists/" + playlistId + "/movies/238/note",
                r -> r.header("Cookie", cookie(sessionId))).body().string()).contains("diner scene is perfect");
        });
    }

    private static Response namedCreate(io.javalin.testtools.HttpClient http, String sessionId, String name) {
        return http.request("/playlists", r -> {
            r.header("Cookie", cookie(sessionId));
            r.post(new FormBody.Builder().add("name", name).build());
        });
    }

    // the bulk dialog re-renders instead of redirecting, so the new playlist's
    // id is read back from the picker's pick buttons (newest playlist last)
    private static String playlistIdFromDialog(io.javalin.testtools.HttpClient http, String sessionId) throws java.io.IOException {
        var body = http.get("/playlists/select?movie=238&movie=680", r -> r.header("Cookie", cookie(sessionId)))
            .body().string();
        var matcher = Pattern.compile("hx-post=\"/playlists/(\\d+)/movies\"").matcher(body);
        String id = null;
        while (matcher.find()) {
            id = matcher.group(1);
        }
        assertThat(id).isNotNull();
        return id;
    }

    @Test
    void markNoteLifecycle() {
        // pages render cards — this test gets its own app with movie stubs
        JavalinTest.test(appWithMovies(), new TestConfig(false, false, NO_REDIRECTS), (server, http) -> {
            var sessionId = signIn(http, "noter@bar.com");

            // the note belongs to the mark: nothing to annotate before marking —
            // but the movie page always renders the note slot (:empty until marked,
            // so a fresh mark can drop the fragment in client-side)
            assertThat(http.request("/marked/238/note", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(new FormBody.Builder().add("note", "nope").build());
            }).code()).isEqualTo(404);
            var unmarkedMovie = http.get("/movies/238", r -> r.header("Cookie", cookie(sessionId))).body().string();
            assertThat(unmarkedMovie).contains("<div class=\"movie-note\"></div>");
            assertThat(unmarkedMovie).doesNotContain("card-note");

            mark(http, sessionId, 238);

            // editor pair: the display area (cancel target) and the editor form
            assertThat(http.get("/marked/238/note", r -> r.header("Cookie", cookie(sessionId)))
                .body().string()).contains("+ note");
            var editor = http.get("/marked/238/note/edit", r -> r.header("Cookie", cookie(sessionId)));
            var editorBody = editor.body().string();
            assertThat(editorBody).contains("<textarea");
            assertThat(editorBody).contains("hx-post=\"/marked/238/note\"");
            assertThat(editorBody).contains("hx-get=\"/marked/238/note\"");

            // saving trims and renders back the display area
            var save = http.request("/marked/238/note", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(new FormBody.Builder().add("note", "  watch with family late Oct  ").build());
            });
            assertThat(save.body().string()).contains("watch with family late Oct");

            // the note shows up on the marked page and the movie page
            assertThat(http.get("/marked", r -> r.header("Cookie", cookie(sessionId))).body().string())
                .contains("watch with family late Oct");
            assertThat(http.get("/movies/238", r -> r.header("Cookie", cookie(sessionId))).body().string())
                .contains("watch with family late Oct");

            // a blank note removes it without unmarking
            http.request("/marked/238/note", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(new FormBody.Builder().add("note", "   ").build());
            });
            assertThat(idsOf(http, sessionId)).isEqualTo("238");
            assertThat(http.get("/marked/238/note", r -> r.header("Cookie", cookie(sessionId)))
                .body().string()).contains("+ note");

            // too long is rejected, the note stays gone
            assertThat(http.request("/marked/238/note", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(new FormBody.Builder().add("note", "x".repeat(501)).build());
            }).code()).isEqualTo(400);

            // unmarking removes the note along with the mark
            mark(http, sessionId, 238);
            http.request("/marked/238/note", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(new FormBody.Builder().add("note", "gone soon").build());
            });
            unmark(http, sessionId, 238);
            mark(http, sessionId, 238);
            assertThat(http.get("/marked/238/note", r -> r.header("Cookie", cookie(sessionId)))
                .body().string()).contains("+ note");

            // anonymous users get nothing
            assertThat(http.get("/marked/238/note").code()).isEqualTo(401);
            assertThat(http.get("/marked/238/note/edit").code()).isEqualTo(401);
        });
    }

    @Test
    void membershipNoteLifecycle() {
        JavalinTest.test(appWithMovies(), new TestConfig(false, false, NO_REDIRECTS), (server, http) -> {
            var sessionId = signIn(http, "member-noter@bar.com");
            var playlistId = namedCreate(http, sessionId, "90s").header("Location").substring("/playlists/".length());
            http.request("/playlists/" + playlistId + "/movies/238", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(noBody());
            });

            // the editor pair is ownership-checked and wired to the membership route
            var editor = http.get("/playlists/" + playlistId + "/movies/238/note/edit",
                r -> r.header("Cookie", cookie(sessionId)));
            var editorBody = editor.body().string();
            assertThat(editorBody).contains("hx-post=\"/playlists/" + playlistId + "/movies/238/note\"");
            assertThat(editorBody).contains("hx-get=\"/playlists/" + playlistId + "/movies/238/note\"");

            var save = http.request("/playlists/" + playlistId + "/movies/238/note", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(new FormBody.Builder().add("note", "glasses are cooler than in The Matrix").build());
            });
            assertThat(save.body().string()).contains("glasses are cooler than in The Matrix");

            // shown on the playlist page and in the movie-page picker dialog
            assertThat(http.get("/playlists/" + playlistId, r -> r.header("Cookie", cookie(sessionId)))
                .body().string()).contains("glasses are cooler than in The Matrix");
            assertThat(http.get("/playlists/select?movie=238", r -> r.header("Cookie", cookie(sessionId)))
                .body().string()).contains("glasses are cooler than in The Matrix");

            // the same movie can carry a different note in another playlist
            var otherId = namedCreate(http, sessionId, "Watchlist").header("Location").substring("/playlists/".length());
            http.request("/playlists/" + otherId + "/movies/238", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(noBody());
            });
            http.request("/playlists/" + otherId + "/movies/238/note", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(new FormBody.Builder().add("note", "watch this month").build());
            });
            var dialog = http.get("/playlists/select?movie=238", r -> r.header("Cookie", cookie(sessionId)))
                .body().string();
            assertThat(dialog).contains("glasses are cooler than in The Matrix");
            assertThat(dialog).contains("watch this month");

            // not a member → nothing to annotate; anonymous → 401
            assertThat(http.request("/playlists/" + playlistId + "/movies/680/note", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(new FormBody.Builder().add("note", "x").build());
            }).code()).isEqualTo(404);
            assertThat(http.get("/playlists/" + playlistId + "/movies/238/note/edit").code()).isEqualTo(401);
        });
    }

    @Test
    void playlistDescriptionLifecycle() {
        authTest((server, http) -> {
            var sessionId = signIn(http, "describer@bar.com");

            // created with a description, trimmed
            var create = http.request("/playlists", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(new FormBody.Builder()
                    .add("name", "90s")
                    .add("description", "  Films I keep coming back to.  ")
                    .build());
            });
            var playlistId = create.header("Location").substring("/playlists/".length());
            var page = http.get("/playlists/" + playlistId, r -> r.header("Cookie", cookie(sessionId))).body().string();
            assertThat(page).contains("Films I keep coming back to.");
            // the description edit form is wired to the route
            assertThat(page).contains("action=\"/playlists/" + playlistId + "/description\"");

            // editing updates, blank removes
            http.request("/playlists/" + playlistId + "/description", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(new FormBody.Builder().add("description", "Updated").build());
            });
            assertThat(http.get("/playlists/" + playlistId, r -> r.header("Cookie", cookie(sessionId)))
                .body().string()).contains("Updated");
            http.request("/playlists/" + playlistId + "/description", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(new FormBody.Builder().add("description", "   ").build());
            });
            assertThat(http.get("/playlists/" + playlistId, r -> r.header("Cookie", cookie(sessionId)))
                .body().string()).doesNotContain("Updated");

            // too long is rejected
            assertThat(http.request("/playlists/" + playlistId + "/description", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(new FormBody.Builder().add("description", "x".repeat(1001)).build());
            }).code()).isEqualTo(400);
            assertThat(http.request("/playlists/" + playlistId + "/description", r -> r.post(noBody())).code()).isEqualTo(401);
        });
    }

    @Test
    void anonymousMarksMigrateWithNotesAndServerNoteWins() {
        authTest((server, http) -> {
            var sessionId = signIn(http, "migrator@bar.com");

            // an existing account mark with its own note
            mark(http, sessionId, 680);
            http.request("/marked/680/note", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(new FormBody.Builder().add("note", "server 680").build());
            });

            // the local (browser) marks migrate: 238 with a note, 680 with a
            // conflicting note — the server note must win, the local one must
            // still create 238's mark with its note
            var migrate = http.request("/marked/migrate", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(okhttp3.RequestBody.create(
                    "{\"movies\":[{\"movieId\":238,\"note\":\"watch with family\"},{\"movieId\":680,\"note\":\"local 680\"}]}",
                    okhttp3.MediaType.parse("application/json")));
            });
            assertThat(migrate.code()).isEqualTo(204);

            assertThat(idsOf(http, sessionId)).isEqualTo("680,238");
            assertThat(http.get("/marked/238/note", r -> r.header("Cookie", cookie(sessionId)))
                .body().string()).contains("watch with family");
            assertThat(http.get("/marked/680/note", r -> r.header("Cookie", cookie(sessionId)))
                .body().string()).contains("server 680");
            assertThat(http.get("/marked/680/note", r -> r.header("Cookie", cookie(sessionId)))
                .body().string()).doesNotContain("local 680");

            // idempotent: migrating again changes nothing
            http.request("/marked/migrate", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(okhttp3.RequestBody.create(
                    "{\"movies\":[{\"movieId\":680,\"note\":\"local 680\"}]}",
                    okhttp3.MediaType.parse("application/json")));
            });
            assertThat(http.get("/marked/680/note", r -> r.header("Cookie", cookie(sessionId)))
                .body().string()).contains("server 680");

            // garbage bodies are rejected, not 500s
            assertThat(http.request("/marked/migrate", r -> {
                r.header("Cookie", cookie(sessionId));
                r.post(okhttp3.RequestBody.create("not json", okhttp3.MediaType.parse("application/json")));
            }).code()).isEqualTo(400);
            assertThat(http.request("/marked/migrate", r -> r.post(noBody())).code()).isEqualTo(401);
        });
    }

    /** The shared app with movie stubs, for tests that render cards or movie pages. */
    private Javalin appWithMovies() {
        return EkranApp.create(
            new SearchService((q, p, t) -> new SearchResultPage(List.of())),
            new MovieService(id -> id == 238
                ? java.util.Optional.of(GODFATHER_MOVIE)
                : id == 680 ? java.util.Optional.of(PULP_MOVIE) : java.util.Optional.empty()),
            new PersonService(id -> java.util.Optional.empty()),
            authService,
            new MarksService(new InMemoryMarksRepository()),
            new PlaylistsService(new InMemoryPlaylistsRepository()),
            true,
            null);
    }

    private static okhttp3.RequestBody noBody() {
        return okhttp3.RequestBody.create(new byte[0], null);
    }

    private static Response move(io.javalin.testtools.HttpClient http, String sessionId,
                                 String playlistId, long movieId, String dir) {
        return http.request("/playlists/" + playlistId + "/movies/" + movieId + "/move", r -> {
            r.header("Cookie", cookie(sessionId));
            r.post(new FormBody.Builder().add("dir", dir).build());
        });
    }

    private static String shareUrlOf(io.javalin.testtools.HttpClient http, String sessionId,
                                    String playlistId) throws java.io.IOException {
        var body = http.get("/playlists/" + playlistId, r -> r.header("Cookie", cookie(sessionId))).body().string();
        var start = body.indexOf("data-share-url=\"");
        return start < 0 ? "" : body.substring(start, start + 200);
    }

    private static String cookie(String sessionId) {
        return "__Host-ekran_session=" + sessionId;
    }

    private static void mark(io.javalin.testtools.HttpClient http, String sessionId, long movieId) {
        var response = http.request("/marked/" + movieId, r -> {
            r.header("Cookie", cookie(sessionId));
            r.post(noBody());
        });
        assertThat(response.code()).isEqualTo(204);
    }

    private static void unmark(io.javalin.testtools.HttpClient http, String sessionId, long movieId) {
        var response = http.request("/marked/" + movieId, r -> {
            r.header("Cookie", cookie(sessionId));
            r.method("DELETE", null);
        });
        assertThat(response.code()).isEqualTo(204);
    }

    private static String idsOf(io.javalin.testtools.HttpClient http, String sessionId) throws java.io.IOException {
        var response = http.get("/marked/ids", r -> r.header("Cookie", cookie(sessionId)));
        assertThat(response.code()).isEqualTo(200);
        return response.body().string().strip();
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
            new MarksService(new InMemoryMarksRepository()),
            new PlaylistsService(new InMemoryPlaylistsRepository()),
            true,
            null
        );
    }
}
