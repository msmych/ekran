package uk.matvey.ekran.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class AuthServiceTest {

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([A-Za-z0-9_-]{43})");

    private final InMemoryAuthRepository repository = new InMemoryAuthRepository();
    private final CapturingEmailService emails = new CapturingEmailService();
    private final SteppingClock clock = new SteppingClock();
    private final AuthService auth = new AuthService(
        repository, emails, "https://ekran.test", Duration.ofMinutes(15), Duration.ofDays(30), false, clock);

    @Test
    void firstRequestCreatesAccountAndEmailsMagicLink() {
        auth.requestMagicLink("Foo@Bar.com", "1.2.3.4", "/movies/238");

        assertThat(repository.findUserIdByEmail("foo@bar.com")).isPresent(); // normalized
        var email = emails.last().orElseThrow();
        assertThat(email.to()).isEqualTo("foo@bar.com");
        assertThat(email.subject()).isEqualTo("Sign in to ekran");
        assertThat(email.html()).contains("https://ekran.test/auth/link?token=");
        assertThat(email.html()).contains("expires in 15 minutes");
        assertThat(email.text()).contains("https://ekran.test/auth/link?token=");
    }

    @Test
    void linkCarriesEncodedContinuationPath() {
        auth.requestMagicLink("foo@bar.com", "1.2.3.4", "/list?movie=348");

        assertThat(emails.last().orElseThrow().html())
            .contains("&next=%2Flist%3Fmovie%3D348");
    }

    @Test
    void homeContinuationIsOmitted() {
        auth.requestMagicLink("foo@bar.com", "1.2.3.4", "/");

        assertThat(emails.last().orElseThrow().html()).doesNotContain("next=");
    }

    @Test
    void consumingLinkCreatesSession() {
        auth.requestMagicLink("foo@bar.com", "1.2.3.4", "/");
        var token = rawToken();

        var session = auth.consumeLoginLink(token).orElseThrow();

        assertThat(session.id()).isNotBlank();
        // the stored session key is the hash, never the raw cookie value
        assertThat(repository.hasSessionForRaw(session.id())).isTrue();
        assertThat(auth.sessionUser(session.id()).map(AuthRepository.SessionUser::email)).contains("foo@bar.com");
        assertThat(session.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(30)));
    }

    @Test
    void tokenIsSingleUse() {
        auth.requestMagicLink("foo@bar.com", "1.2.3.4", "/");
        var token = rawToken();

        assertThat(auth.consumeLoginLink(token)).isPresent();
        assertThat(auth.consumeLoginLink(token)).isEmpty();
    }

    @Test
    void tokenExpiresAfterTtl() {
        auth.requestMagicLink("foo@bar.com", "1.2.3.4", "/");
        var token = rawToken();

        clock.advance(Duration.ofMinutes(15));

        assertThat(auth.consumeLoginLink(token)).isEmpty();
    }

    @Test
    void sessionExpires() {
        auth.requestMagicLink("foo@bar.com", "1.2.3.4", "/");
        var session = auth.consumeLoginLink(rawToken()).orElseThrow();

        clock.advance(Duration.ofDays(30));

        assertThat(auth.sessionUser(session.id())).isEmpty();
    }

    @Test
    void rateLimitsPerEmail() {
        for (var i = 0; i < 6; i++) {
            auth.requestMagicLink("foo@bar.com", "10.0.0." + i, "/"); // distinct IPs
        }

        assertThat(emails.sent).hasSize(5);
    }

    @Test
    void rateLimitsPerIp() {
        for (var i = 0; i < 11; i++) {
            auth.requestMagicLink("user" + i + "@bar.com", "1.2.3.4", "/"); // distinct emails
        }

        assertThat(emails.sent).hasSize(10);
    }

    @Test
    void emailFailurePropagates() {
        var failing = new AuthService(repository,
            (to, subject, html, text) -> {
                throw new uk.matvey.ekran.email.EmailException("Email delivery failed");
            },
            "https://ekran.test", Duration.ofMinutes(15), Duration.ofDays(30), false, clock);

        assertThatThrownBy(() -> failing.requestMagicLink("foo@bar.com", "1.2.3.4", "/"))
            .isInstanceOf(uk.matvey.ekran.email.EmailException.class);
    }

    @Test
    void signOutInvalidatesSession() {
        auth.requestMagicLink("foo@bar.com", "1.2.3.4", "/");
        var session = auth.consumeLoginLink(rawToken()).orElseThrow();

        auth.signOut(session.id());

        assertThat(auth.sessionUser(session.id())).isEmpty();
    }

    @Test
    void onlyTokenHashesAreStored() {
        auth.requestMagicLink("foo@bar.com", "1.2.3.4", "/");
        var token = rawToken();

        assertThat(repository.loginTokens().getFirst().tokenHash()).isEqualTo(Tokens.sha256(token));
    }

    private String rawToken() {
        var email = emails.last().orElseThrow();
        var matcher = TOKEN_IN_LINK.matcher(email.text());
        assertThat(matcher.find()).as("magic link with token in email").isTrue();
        return matcher.group(1);
    }
}
