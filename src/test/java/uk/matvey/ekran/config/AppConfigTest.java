package uk.matvey.ekran.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppConfigTest {

    @Test
    void readsDefaults() {
        var config = AppConfig.fromEnv(withToken()::get);

        assertThat(config.port()).isEqualTo(7070);
        assertThat(config.tmdbBaseUrl()).isEqualTo("https://api.themoviedb.org/3");
        assertThat(config.imageBaseUrl()).isEqualTo("https://image.tmdb.org/t/p");
        assertThat(config.connectTimeout().toMillis()).isEqualTo(2000);
        assertThat(config.searchTimeout().toMillis()).isEqualTo(3000);
        assertThat(config.detailTimeout().toMillis()).isEqualTo(5000);
    }

    @Test
    void readsOverridesAndStripsToken() {
        var config = AppConfig.fromEnv(Map.of(
            "TMDB_API_TOKEN", "  tok  ",
            "PORT", "8080",
            "TMDB_BASE_URL", "https://tmdb-proxy.example.com/3",
            "TMDB_SEARCH_TIMEOUT_MS", "1500"
        )::get);

        assertThat(config.tmdbApiToken()).isEqualTo("tok");
        assertThat(config.port()).isEqualTo(8080);
        assertThat(config.tmdbBaseUrl()).isEqualTo("https://tmdb-proxy.example.com/3");
        assertThat(config.searchTimeout().toMillis()).isEqualTo(1500);
    }

    @Test
    void failsFastWhenTokenMissing() {
        var env = new HashMap<String, String>();

        assertThatThrownBy(() -> AppConfig.fromEnv(env::get))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TMDB_API_TOKEN");
    }

    @Test
    void failsFastOnInvalidPort() {
        var env = withToken();
        env.put("PORT", "not-a-port");

        assertThatThrownBy(() -> AppConfig.fromEnv(env::get))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PORT");
    }

    @Test
    void toStringMasksToken() {
        var config = AppConfig.fromEnv(withToken()::get);

        assertThat(config.toString()).contains("****").doesNotContain("secret-token");
    }

    private HashMap<String, String> withToken() {
        var env = new HashMap<String, String>();
        env.put("TMDB_API_TOKEN", "secret-token");
        return env;
    }
}