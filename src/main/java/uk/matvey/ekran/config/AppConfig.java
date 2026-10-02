package uk.matvey.ekran.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Function;

public record AppConfig(
    String tmdbApiToken,
    int port,
    String tmdbBaseUrl,
    String imageBaseUrl,
    int tmdbConnectTimeoutMs,
    int tmdbSearchTimeoutMs,
    int tmdbDetailTimeoutMs,
    String databaseUrl,
    String resendApiKey,
    String resendBaseUrl,
    String authFromEmail,
    String publicBaseUrl,
    int tokenTtlMinutes,
    int sessionDays
) {

    public static AppConfig fromEnv() {
        return fromEnv(System::getenv);
    }

    public static AppConfig fromEnv(Function<String, String> env) {
        var token = env.apply("TMDB_API_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("TMDB_API_TOKEN is not set — get a v4 read access token from https://www.themoviedb.org/settings/api");
        }
        var databaseUrl = env.apply("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) {
            throw new IllegalStateException("DATABASE_URL is not set — e.g. postgres://user:pass@host:5432/ekran");
        }
        var resendApiKey = env.apply("RESEND_API_KEY");
        if (resendApiKey == null || resendApiKey.isBlank()) {
            throw new IllegalStateException("RESEND_API_KEY is not set — get one at https://resend.com/api-keys");
        }
        var config = new AppConfig(
            token.strip(),
            intEnv(env, "PORT", 7070),
            stringEnv(env, "TMDB_BASE_URL", "https://api.themoviedb.org/3"),
            stringEnv(env, "TMDB_IMAGE_BASE_URL", "https://image.tmdb.org/t/p"),
            intEnv(env, "TMDB_CONNECT_TIMEOUT_MS", 2_000),
            intEnv(env, "TMDB_SEARCH_TIMEOUT_MS", 3_000),
            intEnv(env, "TMDB_DETAIL_TIMEOUT_MS", 5_000),
            databaseUrl.strip(),
            resendApiKey.strip(),
            stringEnv(env, "RESEND_BASE_URL", "https://api.resend.com"),
            stringEnv(env, "AUTH_FROM_EMAIL", "ekran <no-reply@ekran.uk>"),
            stringEnv(env, "PUBLIC_BASE_URL", "https://ekran.uk"),
            intEnv(env, "AUTH_TOKEN_TTL_MINUTES", 15),
            intEnv(env, "AUTH_SESSION_DAYS", 30)
        );
        config.validate();
        return config;
    }

    public Duration connectTimeout() {
        return Duration.ofMillis(tmdbConnectTimeoutMs);
    }

    public Duration searchTimeout() {
        return Duration.ofMillis(tmdbSearchTimeoutMs);
    }

    public Duration detailTimeout() {
        return Duration.ofMillis(tmdbDetailTimeoutMs);
    }

    public Duration tokenTtl() {
        return Duration.ofMinutes(tokenTtlMinutes);
    }

    public Duration sessionLifetime() {
        return Duration.ofDays(sessionDays);
    }

    public boolean secureCookies() {
        return publicBaseUrl.startsWith("https://");
    }

    public DbConnection dbConnection() {
        var uri = databaseUri();
        var host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("DATABASE_URL has no host: " + maskedDatabaseUrl());
        }
        var path = uri.getPath();
        if (path == null || path.equals("/") || path.isBlank()) {
            throw new IllegalStateException("DATABASE_URL has no database name: " + maskedDatabaseUrl());
        }
        var jdbcUrl = "jdbc:postgresql://" + host + (uri.getPort() >= 0 ? ":" + uri.getPort() : "") + path
            + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
        var userInfo = uri.getUserInfo();
        var user = "";
        var password = "";
        if (userInfo != null) {
            var separator = userInfo.indexOf(':');
            user = decode(separator >= 0 ? userInfo.substring(0, separator) : userInfo);
            password = separator >= 0 ? decode(userInfo.substring(separator + 1)) : "";
        }
        return new DbConnection(jdbcUrl, user, password);
    }

    public record DbConnection(String jdbcUrl, String user, String password) {
    }

    @Override
    public String toString() {
        return ("AppConfig[tmdbApiToken=****, port=%d, tmdbBaseUrl=%s, imageBaseUrl=%s, timeouts=%d/%d/%d ms, "
                + "databaseUrl=%s, resendApiKey=****, resendBaseUrl=%s, authFromEmail=%s, publicBaseUrl=%s, "
                + "tokenTtlMinutes=%d, sessionDays=%d]")
            .formatted(port, tmdbBaseUrl, imageBaseUrl, tmdbConnectTimeoutMs, tmdbSearchTimeoutMs, tmdbDetailTimeoutMs,
                maskedDatabaseUrl(), resendBaseUrl, authFromEmail, publicBaseUrl, tokenTtlMinutes, sessionDays);
    }

    private URI databaseUri() {
        var https = databaseUrl.replaceFirst("^postgres(ql)?://", "https://");
        if (https.equals(databaseUrl)) {
            throw new IllegalStateException("DATABASE_URL must start with postgres:// or postgresql://");
        }
        try {
            var uri = URI.create(https);
            if (!"https".equals(uri.getScheme())) {
                throw new IllegalStateException("DATABASE_URL must start with postgres:// or postgresql://");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("DATABASE_URL is not a valid URL — percent-encode special characters");
        }
    }

    // regex-based masking on the raw string: never parses, so it cannot recurse
    // into databaseUri() from inside its own error message
    private String maskedDatabaseUrl() {
        return databaseUrl.replaceFirst("^(postgres(?:ql)?://)[^@/]+@", "$1***@");
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private void validate() {
        if (tmdbApiToken.isBlank()) {
            throw new IllegalStateException("TMDB_API_TOKEN must not be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalStateException("PORT must be between 1 and 65535, got " + port);
        }
        if (tmdbBaseUrl.isBlank() || imageBaseUrl.isBlank()) {
            throw new IllegalStateException("TMDB_BASE_URL and TMDB_IMAGE_BASE_URL must not be blank");
        }
        if (tmdbConnectTimeoutMs <= 0 || tmdbSearchTimeoutMs <= 0 || tmdbDetailTimeoutMs <= 0) {
            throw new IllegalStateException("TMDB timeouts must be positive");
        }
        dbConnection(); // fails fast on a malformed DATABASE_URL
        if (authFromEmail.isBlank()) {
            throw new IllegalStateException("AUTH_FROM_EMAIL must not be blank");
        }
        if (!publicBaseUrl.startsWith("https://") && !publicBaseUrl.startsWith("http://")) {
            throw new IllegalStateException("PUBLIC_BASE_URL must start with http:// or https://, got '" + publicBaseUrl + "'");
        }
        if (publicBaseUrl.endsWith("/")) {
            throw new IllegalStateException("PUBLIC_BASE_URL must not end with a slash");
        }
        if (tokenTtlMinutes <= 0 || tokenTtlMinutes > 24 * 60) {
            throw new IllegalStateException("AUTH_TOKEN_TTL_MINUTES must be between 1 and 1440, got " + tokenTtlMinutes);
        }
        if (sessionDays <= 0 || sessionDays > 365) {
            throw new IllegalStateException("AUTH_SESSION_DAYS must be between 1 and 365, got " + sessionDays);
        }
    }

    private static String stringEnv(Function<String, String> env, String key, String defaultValue) {
        var value = env.apply(key);
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }

    private static int intEnv(Function<String, String> env, String key, int defaultValue) {
        var value = env.apply(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Expected an integer for " + key + ", got '" + value + "'");
        }
    }
}
