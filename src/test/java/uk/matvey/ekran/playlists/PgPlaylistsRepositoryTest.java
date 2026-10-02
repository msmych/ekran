package uk.matvey.ekran.playlists;

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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

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

        var id = repository.createPlaylist(userId, "Watch soon");
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
        var id = repository.createPlaylist(userId, "Ordered");

        repository.addMovies(userId, id, List.of(238L, 680L, 155L));
        repository.addMovie(userId, id, 13L);
        repository.removeMovie(userId, id, 680L);
        repository.addMovie(userId, id, 90L);

        assertThat(repository.playlist(userId, id)).map(PlaylistDetail::movieIds).contains(List.of(238L, 155L, 13L, 90L));
    }

    @Test
    void membershipIsIdempotentAndCounted() {
        var userId = authRepository.insertUser("member@bar.com");
        var id = repository.createPlaylist(userId, "Counted");

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
    void deletingPlaylistDeletesMemberships() {
        var userId = authRepository.insertUser("cascade@bar.com");
        var id = repository.createPlaylist(userId, "Doomed");
        repository.addMovies(userId, id, List.of(238L, 680L));

        repository.deletePlaylist(userId, id);

        assertThat(repository.playlist(userId, id)).isEmpty();
        var fresh = repository.createPlaylist(userId, "Fresh");
        assertThat(repository.playlist(userId, fresh)).map(PlaylistDetail::movieIds).contains(List.of());
    }

    @Test
    void otherUsersPlaylistIsInvisible() {
        var alice = authRepository.insertUser("owner@bar.com");
        var bob = authRepository.insertUser("intruder@bar.com");
        var id = repository.createPlaylist(alice, "Private");
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
    var id = repository.createPlaylist(userId, "Ordered");
    repository.addMovies(userId, id, List.of(238L, 680L, 155L));

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
    var single = repository.createPlaylist(userId, "Single");
    repository.addMovie(userId, single, 238L);
    assertThat(repository.moveMovie(userId, single, 238L, true)).isFalse();
}

@Test
void moveMovieIsOwnershipScoped() {
    var alice = authRepository.insertUser("mover@bar.com");
    var bob = authRepository.insertUser("meddler@bar.com");
    var id = repository.createPlaylist(alice, "Private order");
    repository.addMovies(alice, id, List.of(238L, 680L));

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
        var first = repository.createPlaylist(userId, "Old");
        var second = repository.createPlaylist(userId, "New");
        repository.renamePlaylist(userId, first, "Old touched");

        assertThat(repository.playlists(userId))
            .extracting(Playlist::name)
            .containsExactly("Old touched", "New");
        assertThat(Optional.of(repository.playlists(userId).getFirst().id())).contains(first);
        assertThat(repository.playlists(userId).get(1).id()).isEqualTo(second);
    }
}