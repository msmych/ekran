package uk.matvey.ekran.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import uk.matvey.ekran.db.DbMigrations;

/**
 * Full lifecycle against real PostgreSQL (Testcontainers) — migrations, token
 * lifecycle, session lifecycle. Requires Docker.
 */
class PgAuthRepositoryTest {

    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static DataSource dataSource;
    private static PgAuthRepository repository;

    @BeforeAll
    static void startPostgres() {
        PG.start();
        var config = new HikariConfig();
        config.setJdbcUrl(PG.getJdbcUrl());
        config.setUsername(PG.getUsername());
        config.setPassword(PG.getPassword());
        config.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(config);
        DbMigrations.migrate(dataSource);
        repository = new PgAuthRepository(dataSource);
    }

    @AfterAll
    static void stopPostgres() {
        if (PG.isRunning()) {
            PG.stop();
        }
    }

    @Test
    void migrationsAreIdempotent() {
        DbMigrations.migrate(dataSource);

        assertThat(migrationCount()).isEqualTo(6);
    }

    @Test
    void userCreationAndLookup() {
        var id = repository.insertUser("foo@bar.com");

        assertThat(repository.findUserIdByEmail("foo@bar.com")).contains(id);
        assertThat(repository.findUserIdByEmail("other@bar.com")).isEmpty();

        // inserting the same email again resolves to the existing id (concurrent first sign-in)
        assertThat(repository.insertUser("foo@bar.com")).isEqualTo(id);
    }

    @Test
    void loginTokenIsSingleUse() {
        var userId = repository.insertUser("single@bar.com");
        var hash = Tokens.sha256("raw-token-single-use");
        repository.insertLoginToken(userId, hash, NOW.plus(Duration.ofMinutes(15)), NOW);

        assertThat(repository.consumeLoginToken(hash, NOW.plusSeconds(1))).contains(userId);
        assertThat(repository.consumeLoginToken(hash, NOW.plusSeconds(2))).isEmpty();
    }

    @Test
    void expiredLoginTokenCannotBeConsumed() {
        var userId = repository.insertUser("expired@bar.com");
        var hash = Tokens.sha256("raw-token-expired");
        repository.insertLoginToken(userId, hash, NOW.minus(Duration.ofMinutes(1)), NOW.minus(Duration.ofMinutes(16)));

        assertThat(repository.consumeLoginToken(hash, NOW)).isEmpty();
    }

    @Test
    void unknownLoginTokenCannotBeConsumed() {
        assertThat(repository.consumeLoginToken(Tokens.sha256("never-issued"), NOW)).isEmpty();
    }

    @Test
    void expiredLoginTokensAreCleanedUp() {
        var userId = repository.insertUser("cleanup@bar.com");
        repository.insertLoginToken(userId, Tokens.sha256("stale-token"), NOW.minusSeconds(1), NOW.minus(Duration.ofMinutes(16)));
        repository.insertLoginToken(userId, Tokens.sha256("fresh-token"), NOW.plus(Duration.ofMinutes(15)), NOW);

        repository.deleteExpiredLoginTokens(NOW);

        assertThat(repository.consumeLoginToken(Tokens.sha256("stale-token"), NOW.plusSeconds(1))).isEmpty();
        assertThat(repository.consumeLoginToken(Tokens.sha256("fresh-token"), NOW.plusSeconds(1))).contains(userId);
    }

    @Test
    void sessionLifecycle() {
        var userId = repository.insertUser("session@bar.com");
        var sessionHash = Tokens.sha256("raw-session-id");
        var expiresAt = NOW.plus(Duration.ofDays(30));

        repository.insertSession(userId, sessionHash, expiresAt, NOW);

        assertThat(repository.findSession(sessionHash, NOW.plusSeconds(1)).map(AuthRepository.SessionUser::email)).contains("session@bar.com");
        // expiry is strict
        assertThat(repository.findSession(sessionHash, expiresAt)).isEmpty();
        assertThat(repository.findSession(Tokens.sha256("unknown-session"), NOW)).isEmpty();

        assertThat(repository.deleteSession(sessionHash)).isTrue();
        assertThat(repository.deleteSession(sessionHash)).isFalse();
        assertThat(repository.findSession(sessionHash, NOW.plusSeconds(1))).isEmpty();
    }

    @Test
    void expiredSessionsAreCleanedUpOnInsert() {
        var userId = repository.insertUser("sweeper@bar.com");
        var staleHash = Tokens.sha256("stale-session");
        repository.insertSession(userId, staleHash, NOW.minusSeconds(1), NOW.minus(Duration.ofDays(1)));

        repository.insertSession(userId, Tokens.sha256("live-session"), NOW.plus(Duration.ofDays(30)), NOW);

        assertThat(repository.findSession(staleHash, NOW.plusSeconds(1))).isEmpty();
    }

    private static int migrationCount() {
        try (var conn = dataSource.getConnection();
             var st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM schema_migrations")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
