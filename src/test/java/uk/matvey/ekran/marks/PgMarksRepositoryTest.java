package uk.matvey.ekran.marks;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import uk.matvey.ekran.auth.PgAuthRepository;
import uk.matvey.ekran.db.DbMigrations;

import javax.sql.DataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PgMarksRepository against real PostgreSQL (Testcontainers). Requires Docker. */
class PgMarksRepositoryTest {

    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:18-alpine");

    private static PgMarksRepository repository;
    private static PgAuthRepository authRepository;

    @BeforeAll
    static void startPostgres() {
        PG.start();
        var config = new HikariConfig();
        config.setJdbcUrl(PG.getJdbcUrl());
        config.setUsername(PG.getUsername());
        config.setPassword(PG.getPassword());
        config.setMaximumPoolSize(4);
        DataSource dataSource = new HikariDataSource(config);
        DbMigrations.migrate(dataSource);
        repository = new PgMarksRepository(dataSource);
        authRepository = new PgAuthRepository(dataSource);
    }

    @AfterAll
    static void stopPostgres() {
        if (PG.isRunning()) {
            PG.stop();
        }
    }

    @Test
    void markIsIdempotentAndPerUser() {
        var alice = authRepository.insertUser("alice@bar.com");
        var bob = authRepository.insertUser("bob@bar.com");

        repository.mark(alice, 238);
        repository.mark(alice, 238);

        assertThat(repository.markedMovieIds(alice)).containsExactly(238L);
        assertThat(repository.markedMovieIds(bob)).isEmpty();
    }

    @Test
    void unmarkIsIdempotent() {
        var userId = authRepository.insertUser("unmark@bar.com");

        repository.mark(userId, 680);
        repository.unmark(userId, 680);
        repository.unmark(userId, 680);

        assertThat(repository.markedMovieIds(userId)).isEmpty();
    }

    @Test
    void markAllIsTransactionalAndIdempotent() {
        var userId = authRepository.insertUser("bulk@bar.com");
        repository.mark(userId, 155);

        repository.markAll(userId, List.of(155L, 680L, 238L));
        repository.markAll(userId, List.of(155L, 680L, 238L));

        assertThat(repository.markedMovieIds(userId)).containsExactlyInAnyOrder(155L, 680L, 238L);
    }

    @Test
    void unmarkAllLeavesOthersIntact() {
        var userId = authRepository.insertUser("bulk-unmark@bar.com");
        repository.markAll(userId, List.of(1L, 2L, 3L));

        repository.unmarkAll(userId, List.of(2L, 3L));

        assertThat(repository.markedMovieIds(userId)).containsExactly(1L);
    }

    @Test
    void marksDoNotLeakAcrossUsers() {
        var alice = authRepository.insertUser("isolate-a@bar.com");
        var bob = authRepository.insertUser("isolate-b@bar.com");

        repository.markAll(alice, List.of(1L, 2L));
        repository.unmarkAll(bob, List.of(1L));

        assertThat(repository.markedMovieIds(alice)).containsExactly(1L, 2L);
    }
}