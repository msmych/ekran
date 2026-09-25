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
        assertThat(config.resendBaseUrl()).isEqualTo("https://api.resend.com");
        assertThat(config.authFromEmail()).isEqualTo("ekran <no-reply@ekran.uk>");
        assertThat(config.publicBaseUrl()).isEqualTo("https://ekran.uk");
        assertThat(config.tokenTtl().toMinutes()).isEqualTo(15);
        assertThat(config.sessionLifetime().toDays()).isEqualTo(30);
        assertThat(config.secureCookies()).isTrue();
    }

    @Test
    void readsOverridesAndStripsToken() {
        var config = AppConfig.fromEnv(withTokenPlus(Map.of(
            "PORT", "8080",
            "TMDB_BASE_URL", "https://tmdb-proxy.example.com/3",
            "TMDB_SEARCH_TIMEOUT_MS", "1500"
        ))::get);

        assertThat(config.tmdbApiToken()).isEqualTo("tok");
        assertThat(config.port()).isEqualTo(8080);
        assertThat(config.tmdbBaseUrl()).isEqualTo("https://tmdb-proxy.example.com/3");
        assertThat(config.searchTimeout().toMillis()).isEqualTo(1500);
    }

    @Test
    void parsesDatabaseUrl() {
        var config = AppConfig.fromEnv(withTokenPlus(Map.of(
            "DATABASE_URL", "postgres://ekran:p%40ss@db.example.com:5433/ekran?sslmode=require"
        ))::get);

        var db = config.dbConnection();
        assertThat(db.jdbcUrl()).isEqualTo("jdbc:postgresql://db.example.com:5433/ekran?sslmode=require");
        assertThat(db.user()).isEqualTo("ekran");
        assertThat(db.password()).isEqualTo("p@ss");
    }

    @Test
    void acceptsPostgresqlSchemeWithoutPort() {
        var config = AppConfig.fromEnv(withTokenPlus(Map.of(
            "DATABASE_URL", "postgresql://ekran:pass@localhost/ekran"
        ))::get);

        assertThat(config.dbConnection().jdbcUrl()).isEqualTo("jdbc:postgresql://localhost/ekran");
    }

    @Test
    void failsFastWhenTokenMissing() {
        var env = new HashMap<String, String>();

        assertThatThrownBy(() -> AppConfig.fromEnv(env::get))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TMDB_API_TOKEN");
    }

    @Test
    void failsFastWhenDatabaseUrlMissing() {
        var env = new HashMap<String, String>();
        env.put("TMDB_API_TOKEN", "secret-token");
        env.put("RESEND_API_KEY", "re_secret");

        assertThatThrownBy(() -> AppConfig.fromEnv(env::get))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DATABASE_URL");
    }

    @Test
    void failsFastWhenResendApiKeyMissing() {
        var env = new HashMap<String, String>();
        env.put("TMDB_API_TOKEN", "secret-token");
        env.put("DATABASE_URL", "postgres://ekran:pg@localhost:5432/ekran");

        assertThatThrownBy(() -> AppConfig.fromEnv(env::get))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("RESEND_API_KEY");
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
    void failsFastOnMalformedDatabaseUrl() {
        var env = withToken();
        env.put("DATABASE_URL", "mysql://ekran:pg@localhost:5432/ekran");

        assertThatThrownBy(() -> AppConfig.fromEnv(env::get))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DATABASE_URL");
    }

    @Test
    void toStringMasksSecrets() {
        var config = AppConfig.fromEnv(withToken()::get);

        assertThat(config.toString())
            .contains("****")
            .doesNotContain("secret-token")
            .doesNotContain("pgpass")
            .doesNotContain("re_secret");
    }

    @Test
    void httpPublicBaseUrlMeansInsecureCookiesForLocalDev() {
        var config = AppConfig.fromEnv(withTokenPlus(Map.of(
            "PUBLIC_BASE_URL", "http://localhost:7070"
        ))::get);

        assertThat(config.secureCookies()).isFalse();
    }

    private HashMap<String, String> withToken() {
        var env = new HashMap<String, String>();
        env.put("TMDB_API_TOKEN", "secret-token");
        env.put("DATABASE_URL", "postgres://ekran:pgpass@localhost:5432/ekran");
        env.put("RESEND_API_KEY", "re_secret");
        return env;
    }

    private HashMap<String, String> withTokenPlus(Map<String, String> overrides) {
        var env = withToken();
        env.put("TMDB_API_TOKEN", "  tok  ");
        env.putAll(overrides);
        return env;
    }
}