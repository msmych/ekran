package uk.matvey.ekran.notes;

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
import uk.matvey.ekran.marks.PgMarksRepository;

/** PgMovieNotesRepository against real PostgreSQL (Testcontainers). Requires Docker. */
class PgMovieNotesRepositoryTest {

    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:18-alpine");

    private static DataSource dataSource;
    private static MovieNotesRepository repository;
    private static PgMarksRepository marksRepository;
    private static PgAuthRepository authRepository;

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
        repository = new PgMovieNotesRepository(dataSource);
        marksRepository = new PgMarksRepository(dataSource);
        authRepository = new PgAuthRepository(dataSource);
    }

    @AfterAll
    static void stopPostgres() {
        if (PG.isRunning()) {
            PG.stop();
        }
    }

    @Test
    void noteNeedsNoMark() {
        var userId = authRepository.insertUser("notes@bar.com");

        // notes are detached from marks — annotating needs no mark
        assertThat(repository.note(userId, 238)).isNull();
        repository.setNote(userId, 238, "watch with family");
        assertThat(repository.note(userId, 238)).isEqualTo("watch with family");

        // an empty note removes it
        repository.setNote(userId, 238, null);
        assertThat(repository.note(userId, 238)).isNull();
    }

    @Test
    void notesMapCarriesOnlyPresentNotes() {
        var userId = authRepository.insertUser("notes-map@bar.com");
        repository.setNote(userId, 238, "watch with family");
        repository.setNote(userId, 680, "with mom");

        var notes = repository.notes(userId, List.of(238L, 680L, 155L));

        assertThat(notes).containsOnlyKeys(238L, 680L);
        assertThat(notes.get(238L)).isEqualTo("watch with family");
        assertThat(notes.get(680L)).isEqualTo("with mom");
    }

    @Test
    void notesDoNotLeakAcrossUsers() {
        var alice = authRepository.insertUser("notes-a@bar.com");
        var bob = authRepository.insertUser("notes-b@bar.com");

        repository.setNote(alice, 238, "mine");

        assertThat(repository.note(bob, 238)).isNull();
        assertThat(repository.notes(bob, List.of(238L))).isEmpty();
    }

    @Test
    void unmarkingKeepsTheNote() {
        // the detach itself: a mark may come and go, the note stays
        var userId = authRepository.insertUser("notes-detach@bar.com");

        repository.setNote(userId, 680, "with mom");
        marksRepository.mark(userId, 680);
        marksRepository.unmark(userId, 680);
        marksRepository.mark(userId, 680);

        assertThat(repository.note(userId, 680)).isEqualTo("with mom");
    }

    @Test
    void mergeNotesKeepsExistingServerNotes() {
        var userId = authRepository.insertUser("notes-merge@bar.com");

        // an existing server-side note
        repository.setNote(userId, 680, "server 680");

        // local (browser) notes migrate in: 238 fresh, 680 conflicting, 155 without one
        repository.mergeNotes(userId, List.of(
            new MovieNote(238, "watch with family"),
            new MovieNote(680, "local 680"),
            new MovieNote(155, null)));

        assertThat(repository.note(userId, 238)).isEqualTo("watch with family");
        assertThat(repository.note(userId, 680)).isEqualTo("server 680");
        assertThat(repository.note(userId, 155)).isNull();

        // idempotent: a second pass changes nothing
        repository.mergeNotes(userId, List.of(new MovieNote(680, "local 680")));
        assertThat(repository.note(userId, 680)).isEqualTo("server 680");
    }
}
