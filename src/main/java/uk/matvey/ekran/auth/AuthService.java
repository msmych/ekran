package uk.matvey.ekran.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.matvey.ekran.email.EmailException;
import uk.matvey.ekran.email.EmailService;
import uk.matvey.ekran.email.MagicLinkEmail;

public class AuthService {

    // __Host- prefix pins the cookie to Secure + path=/ + no domain attribute (https only);
    // plain name for local http runs, where Secure cookies are dropped by browsers
    private static final String SESSION_COOKIE = "ekran_session";
    private static final String SECURE_SESSION_COOKIE = "__Host-ekran_session";

    private static final int EMAIL_REQUESTS_PER_HOUR = 5;
    private static final int IP_REQUESTS_PER_HOUR = 10;

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    public record Session(String id, Instant expiresAt) {
    }

    private final AuthRepository repository;
    private final EmailService emailService;
    private final String publicBaseUrl;
    private final Duration tokenTtl;
    private final Duration sessionLifetime;
    private final boolean secureCookies;
    private final Clock clock;
    private final RateLimiter perEmail = new RateLimiter(EMAIL_REQUESTS_PER_HOUR, Duration.ofHours(1));
    private final RateLimiter perIp = new RateLimiter(IP_REQUESTS_PER_HOUR, Duration.ofHours(1));

    public AuthService(
        AuthRepository repository,
        EmailService emailService,
        String publicBaseUrl,
        Duration tokenTtl,
        Duration sessionLifetime,
        boolean secureCookies,
        Clock clock
    ) {
        this.repository = repository;
        this.emailService = emailService;
        this.publicBaseUrl = publicBaseUrl;
        this.tokenTtl = tokenTtl;
        this.sessionLifetime = sessionLifetime;
        this.secureCookies = secureCookies;
        this.clock = clock;
    }

    public long tokenTtlMinutes() {
        return tokenTtl.toMinutes();
    }

    public long sessionMaxAgeSeconds() {
        return sessionLifetime.toSeconds();
    }

    public String sessionCookieName() {
        return secureCookies ? SECURE_SESSION_COOKIE : SESSION_COOKIE;
    }

    /**
     * Creates (or reuses) the account and emails a one-time sign-in link.
     * Rate-limited requests are dropped silently — the caller must always show
     * the same generic response either way, so the flow reveals nothing about
     * whether the email has an account.
     */
    public void requestMagicLink(String email, String ip, String next) {
        var normalized = Emails.normalize(email);
        if (!perEmail.allow(normalized) || !perIp.allow(ip)) {
            log.info("magic-link request rate-limited");
            return;
        }
        var userId = repository.findUserIdByEmail(normalized)
            .orElseGet(() -> repository.insertUser(normalized));
        var token = Tokens.randomToken();
        var now = clock.instant();
        repository.deleteExpiredLoginTokens(now);
        repository.insertLoginToken(userId, Tokens.sha256(token), now.plus(tokenTtl), now);
        var link = magicLink(token, next);
        emailService.send(
            normalized,
            MagicLinkEmail.subject(),
            MagicLinkEmail.html(link, tokenTtl),
            MagicLinkEmail.text(link, tokenTtl));
    }

    /** Single-use: consumes the token and creates a session, or empty if the link is invalid/expired/used. */
    public Optional<Session> consumeLoginLink(String rawToken) {
        var now = clock.instant();
        return repository.consumeLoginToken(Tokens.sha256(rawToken), now).map(userId -> {
            var sessionId = Tokens.randomToken();
            repository.insertSession(userId, Tokens.sha256(sessionId), now.plus(sessionLifetime), now);
            return new Session(sessionId, now.plus(sessionLifetime));
        });
    }

    public Optional<String> userEmailFor(String rawSessionId) {
        return repository.findSessionEmail(Tokens.sha256(rawSessionId), clock.instant());
    }

    public void signOut(String rawSessionId) {
        repository.deleteSession(Tokens.sha256(rawSessionId));
    }

    private String magicLink(String token, String next) {
        var link = new StringBuilder(publicBaseUrl).append("/auth/link?token=").append(token);
        if (next != null && !next.isEmpty() && !next.equals("/")) {
            link.append("&next=").append(URLEncoder.encode(next, StandardCharsets.UTF_8));
        }
        return link.toString();
    }
}