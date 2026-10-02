package uk.matvey.ekran.marks;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import uk.matvey.ekran.auth.PgAuthRepository;
import uk.matvey.ekran.db.DbMigrations;
import uk.matvey.ekran.domain.MovieNote;

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

    @Test
    void markNotesBelongToTheMark() {
        var userId = authRepository.insertUser("notes@bar.com");

        // no note without a mark
        assertThat(repository.setMarkNote(userId, 238, "nope")).isFalse();
        assertThat(repository.markNote(userId, 238)).isNull();

        repository.mark(userId, 238);
        assertThat(repository.setMarkNote(userId, 238, "watch with family")).isTrue();
        assertThat(repository.markNote(userId, 238)).isEqualTo("watch with family");
        assertThat(repository.markedMovies(userId))
            .containsExactly(new MovieNote(238, "watch with family"));

        // a null note removes it; the mark stays
        assertThat(repository.setMarkNote(userId, 238, null)).isTrue();
        assertThat(repository.markNote(userId, 238)).isNull();
        assertThat(repository.markedMovieIds(userId)).containsExactly(238L);

        // notes do not leak across users
        var other = authRepository.insertUser("notes-other@bar.com");
        assertThat(repository.markNote(other, 238)).isNull();
        assertThat(repository.setMarkNote(other, 238, "hijack")).isFalse();

        // unmarking drops the note; re-marking starts fresh
        repository.setMarkNote(userId, 238, "temporary");
        repository.unmark(userId, 238);
        repository.mark(userId, 238);
        assertThat(repository.markNote(userId, 238)).isNull();
    }

    @Test
    void mergeMarksKeepsExistingServerNotes() {
        var userId = authRepository.insertUser("merge@bar.com");

        // an existing account mark with its own note
        repository.mark(userId, 680);
        repository.setMarkNote(userId, 680, "server 680");

        // local (browser) marks migrate in: 238 with a note, 680 with a
        // conflicting note, 155 without one
        repository.mergeMarks(userId, List.of(
            new MovieNote(238, "watch with family"),
            new MovieNote(680, "local 680"),
            new MovieNote(155, null)));

        assertThat(repository.markedMovieIds(userId)).containsExactlyInAnyOrder(238L, 680L, 155L);
        assertThat(repository.markNote(userId, 238)).isEqualTo("watch with family");
        assertThat(repository.markNote(userId, 680)).isEqualTo("server 680");
        assertThat(repository.markNote(userId, 155)).isNull();

        // idempotent: a second pass changes nothing
        repository.mergeMarks(userId, List.of(new MovieNote(680, "local 680")));
        assertThat(repository.markNote(userId, 680)).isEqualTo("server 680");
    }
}
