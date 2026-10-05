package uk.matvey.ekran.playlists;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import uk.matvey.ekran.auth.PgAuthRepository;
import uk.matvey.ekran.db.DbMigrations;
import uk.matvey.ekran.domain.MovieNote;

/** PgPlaylistsRepository against real PostgreSQL (Testcontainers). Requires Docker. */
class PgPlaylistsRepositoryTest {

    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:18-alpine");

    private static PgPlaylistsRepository repository;
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
        repository = new PgPlaylistsRepository(dataSource);
        authRepository = new PgAuthRepository(dataSource);
    }

    @AfterAll
    static void stopPostgres() {
        if (PG.isRunning()) {
            PG.stop();
        }
    }

    @Test
    void createListRenameDelete() {
        var userId = authRepository.insertUser("crud@bar.com");

        var id = repository.createPlaylist(userId, "Watch soon", null);
        assertThat(repository.playlistsCount(userId)).isEqualTo(1);
        assertThat(repository.playlists(userId))
            .singleElement()
            .satisfies(p -> {
                assertThat(p.id()).isEqualTo(id);
                assertThat(p.name()).isEqualTo("Watch soon");
                assertThat(p.movieCount()).isZero();
            });

        assertThat(repository.renamePlaylist(userId, id, "Watch later")).isTrue();
        assertThat(repository.playlist(userId, id)).map(PlaylistDetail::name).contains("Watch later");

        assertThat(repository.deletePlaylist(userId, id)).isTrue();
        assertThat(repository.playlists(userId)).isEmpty();
        assertThat(repository.playlistsCount(userId)).isZero();
        assertThat(repository.deletePlaylist(userId, id)).isFalse();
    }

    @Test
    void membershipFollowsStableAddOrder() {
        var userId = authRepository.insertUser("order@bar.com");
        var id = repository.createPlaylist(userId, "Ordered", null);

        repository.addMovies(userId, id, notes(238L, 680L, 155L));
        repository.addMovie(userId, id, 13L);
        repository.removeMovie(userId, id, 680L);
        repository.addMovie(userId, id, 90L);

        assertThat(repository.playlist(userId, id)).map(PlaylistDetail::movieIds).contains(List.of(238L, 155L, 13L, 90L));
    }

    @Test
    void membershipIsIdempotentAndCounted() {
        var userId = authRepository.insertUser("member@bar.com");
        var id = repository.createPlaylist(userId, "Counted", null);

        repository.addMovie(userId, id, 238L);
        repository.addMovie(userId, id, 238L);
        repository.removeMovie(userId, id, 999L);

        assertThat(repository.playlist(userId, id)).map(PlaylistDetail::movieIds).contains(List.of(238L));
        assertThat(repository.playlists(userId)).singleElement()
            .satisfies(p -> assertThat(p.movieCount()).isEqualTo(1));
        assertThat(repository.playlistsWithMovie(userId, 238L))
            .singleElement()
            .satisfies(m -> assertThat(m.member()).isTrue());
    }

    @Test
    void indexRowsCarryDescriptionAndOrderedMovieIds() {
        var userId = authRepository.insertUser("index@bar.com");
        var id = repository.createPlaylist(userId, "Sci-fi", "Cowboys in orbit");

        repository.addMovies(userId, id, notes(680L, 238L, 155L));
        repository.updateDescription(userId, id, null);

        assertThat(repository.playlists(userId)).singleElement()
            .satisfies(p -> {
                assertThat(p.description()).isNull();
                assertThat(p.movieCount()).isEqualTo(3);
                assertThat(p.movieIds()).containsExactly(680L, 238L, 155L);
            });
    }

    @Test
    void deletingPlaylistDeletesMemberships() {
        var userId = authRepository.insertUser("cascade@bar.com");
        var id = repository.createPlaylist(userId, "Doomed", null);
        repository.addMovies(userId, id, notes(238L, 680L));

        repository.deletePlaylist(userId, id);

        assertThat(repository.playlist(userId, id)).isEmpty();
        var fresh = repository.createPlaylist(userId, "Fresh", null);
        assertThat(repository.playlist(userId, fresh)).map(PlaylistDetail::movieIds).contains(List.of());
    }

    @Test
    void otherUsersPlaylistIsInvisible() {
        var alice = authRepository.insertUser("owner@bar.com");
        var bob = authRepository.insertUser("intruder@bar.com");
        var id = repository.createPlaylist(alice, "Private", null);
        repository.addMovie(alice, id, 238L);

        assertThat(repository.playlist(bob, id)).isEmpty();
        assertThat(repository.playlistsCount(bob)).isZero();
        assertThat(repository.playlistsCount(alice)).isEqualTo(1);
        assertThat(repository.renamePlaylist(bob, id, "Hacked")).isFalse();
        assertThat(repository.deletePlaylist(bob, id)).isFalse();
        assertThat(repository.playlistsWithMovie(bob, 238L)).isEmpty();
        assertThat(repository.playlist(alice, id)).isPresent();

        var caught = false;
        try {
            repository.addMovie(bob, id, 680L);
        } catch (uk.matvey.ekran.domain.NotFoundException e) {
            caught = true;
        }
        assertThat(caught).isTrue();
        assertThat(repository.playlist(alice, id)).map(PlaylistDetail::movieIds).contains(List.of(238L));
    }

@Test
void moveMovieSwapsAndWraps() {
    var userId = authRepository.insertUser("reorder@bar.com");
    var id = repository.createPlaylist(userId, "Ordered", null);
    repository.addMovies(userId, id, notes(238L, 680L, 155L));

    // adjacent swap in the middle
    assertThat(repository.moveMovie(userId, id, 680L, true)).isTrue();
    assertThat(repository.playlist(userId, id)).map(PlaylistDetail::movieIds).contains(List.of(680L, 238L, 155L));

    // first moves up → wraps to the end
    assertThat(repository.moveMovie(userId, id, 680L, true)).isTrue();
    assertThat(repository.playlist(userId, id)).map(PlaylistDetail::movieIds).contains(List.of(238L, 155L, 680L));

    // last moves down → wraps to the front
    assertThat(repository.moveMovie(userId, id, 680L, false)).isTrue();
    assertThat(repository.playlist(userId, id)).map(PlaylistDetail::movieIds).contains(List.of(680L, 238L, 155L));

    // adjacent swap down in the middle
    assertThat(repository.moveMovie(userId, id, 238L, false)).isTrue();
    assertThat(repository.playlist(userId, id)).map(PlaylistDetail::movieIds).contains(List.of(680L, 155L, 238L));

    // positions have gaps after removals — wrapping still lands correctly
    repository.removeMovie(userId, id, 155L);
    assertThat(repository.moveMovie(userId, id, 238L, false)).isTrue();
    assertThat(repository.playlist(userId, id)).map(PlaylistDetail::movieIds).contains(List.of(238L, 680L));

    // unknown movie and single-member playlists are no-ops
    assertThat(repository.moveMovie(userId, id, 999L, true)).isFalse();
    assertThat(repository.playlist(userId, id)).map(PlaylistDetail::movieIds).contains(List.of(238L, 680L));
    var single = repository.createPlaylist(userId, "Single", null);
    repository.addMovie(userId, single, 238L);
    assertThat(repository.moveMovie(userId, single, 238L, true)).isFalse();
}

@Test
void moveMovieIsOwnershipScoped() {
    var alice = authRepository.insertUser("mover@bar.com");
    var bob = authRepository.insertUser("meddler@bar.com");
    var id = repository.createPlaylist(alice, "Private order", null);
    repository.addMovies(alice, id, notes(238L, 680L));

    var caught = false;
    try {
        repository.moveMovie(bob, id, 680L, true);
    } catch (uk.matvey.ekran.domain.NotFoundException e) {
        caught = true;
    }
    assertThat(caught).isTrue();
    assertThat(repository.playlist(alice, id)).map(PlaylistDetail::movieIds).contains(List.of(238L, 680L));
}

@Test
void indexIsOrderedByRecentlyUpdated() {
        var userId = authRepository.insertUser("recent@bar.com");
        var first = repository.createPlaylist(userId, "Old", null);
        var second = repository.createPlaylist(userId, "New", null);
        repository.renamePlaylist(userId, first, "Old touched");

        assertThat(repository.playlists(userId))
            .extracting(Playlist::name)
            .containsExactly("Old touched", "New");
        assertThat(Optional.of(repository.playlists(userId).getFirst().id())).contains(first);
        assertThat(repository.playlists(userId).get(1).id()).isEqualTo(second);
    }

@Test
void descriptionsAreOptionalAndOwnershipScoped() {
        var alice = authRepository.insertUser("desc-a@bar.com");
        var bob = authRepository.insertUser("desc-b@bar.com");

        var id = repository.createPlaylist(alice, "90s", "Films I keep coming back to.");
        assertThat(repository.playlist(alice, id)).map(PlaylistDetail::description).contains("Films I keep coming back to.");

        assertThat(repository.updateDescription(alice, id, "Nineties essentials")).isTrue();
        assertThat(repository.playlist(alice, id)).map(PlaylistDetail::description).contains("Nineties essentials");

        // blank removes; description edits count as updates
        assertThat(repository.updateDescription(alice, id, null)).isTrue();
        assertThat(repository.playlist(alice, id)).map(PlaylistDetail::description).isEmpty();

        // a foreign playlist behaves like a missing one
        assertThat(repository.updateDescription(bob, id, "hijack")).isFalse();
    }

@Test
void membershipNotesArePerPlaylistAndIdempotentAddsKeepThem() {
        var userId = authRepository.insertUser("mnotes@bar.com");
        var id = repository.createPlaylist(userId, "90s", null);
        var other = repository.createPlaylist(userId, "Watchlist", null);

        // no note on a movie that is not a member
        assertThat(repository.setMovieNote(userId, id, 238, "x")).isFalse();

        // new memberships carry notes; re-adding keeps the existing note
        repository.addMovies(userId, id, List.of(new MovieNote(238, "glasses are cooler"), new MovieNote(680, null)));
        repository.addMovies(userId, id, List.of(new MovieNote(238, "should not overwrite")));
        assertThat(repository.playlist(userId, id)).map(PlaylistDetail::movies)
            .contains(List.of(new MovieNote(238, "glasses are cooler"), new MovieNote(680, null)));

        // the same movie, a different note in another playlist
        repository.addMovies(userId, other, List.of(new MovieNote(238, "watch this month")));
        assertThat(repository.playlist(userId, other)).map(PlaylistDetail::movies)
            .contains(List.of(new MovieNote(238, "watch this month")));

        // setMovieNote edits and clears, and membership queries carry notes
        assertThat(repository.setMovieNote(userId, id, 680, "diner scene")).isTrue();
        assertThat(repository.setMovieNote(userId, id, 238, null)).isTrue();
        assertThat(repository.playlist(userId, id)).map(PlaylistDetail::movies)
            .contains(List.of(new MovieNote(238, null), new MovieNote(680, "diner scene")));
        assertThat(repository.playlistsWithMovie(userId, 680))
            .anySatisfy(m -> {
                assertThat(m.id()).isEqualTo(id);
                assertThat(m.member()).isTrue();
                assertThat(m.note()).isEqualTo("diner scene");
            });
        assertThat(repository.playlistsWithMovie(userId, 238))
            .anySatisfy(m -> {
                assertThat(m.id()).isEqualTo(other);
                assertThat(m.note()).isEqualTo("watch this month");
            });

        // removing the membership drops its note
        repository.removeMovie(userId, id, 680);
        repository.addMovies(userId, id, List.of(new MovieNote(680, null)));
        assertThat(repository.playlist(userId, id)).map(PlaylistDetail::movies)
            .contains(List.of(new MovieNote(238, null), new MovieNote(680, null)));
    }

    private static List<MovieNote> notes(Long... movieIds) {
        return java.util.Arrays.stream(movieIds).map(id -> new MovieNote(id, null)).toList();
    }
}
