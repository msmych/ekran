package uk.matvey.ekran.config;

import java.time.Duration;
import java.util.function.Function;

public record AppConfig(
    String tmdbApiToken,
    int port,
    String tmdbBaseUrl,
    String imageBaseUrl,
    int tmdbConnectTimeoutMs,
    int tmdbSearchTimeoutMs,
    int tmdbDetailTimeoutMs
) {

    public static AppConfig fromEnv() {
        return fromEnv(System::getenv);
    }

    public static AppConfig fromEnv(Function<String, String> env) {
        var token = env.apply("TMDB_API_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("TMDB_API_TOKEN is not set — get a v4 read access token from https://www.themoviedb.org/settings/api");
        }
        var config = new AppConfig(
            token.strip(),
            intEnv(env, "PORT", 7070),
            stringEnv(env, "TMDB_BASE_URL", "https://api.themoviedb.org/3"),
            stringEnv(env, "TMDB_IMAGE_BASE_URL", "https://image.tmdb.org/t/p"),
            intEnv(env, "TMDB_CONNECT_TIMEOUT_MS", 2_000),
            intEnv(env, "TMDB_SEARCH_TIMEOUT_MS", 3_000),
            intEnv(env, "TMDB_DETAIL_TIMEOUT_MS", 5_000)
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

    @Override
    public String toString() {
        return "AppConfig[tmdbApiToken=****, port=%d, tmdbBaseUrl=%s, imageBaseUrl=%s, timeouts=%d/%d/%d ms]"
            .formatted(port, tmdbBaseUrl, imageBaseUrl, tmdbConnectTimeoutMs, tmdbSearchTimeoutMs, tmdbDetailTimeoutMs);
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