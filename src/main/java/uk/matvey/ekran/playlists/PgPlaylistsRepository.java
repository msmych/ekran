package uk.matvey.ekran.playlists;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import uk.matvey.ekran.db.Jdbc;
import uk.matvey.ekran.domain.MovieNote;
import uk.matvey.ekran.domain.NotFoundException;

public class PgPlaylistsRepository implements PlaylistsRepository {

    private final DataSource dataSource;

    public PgPlaylistsRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<Playlist> playlists(long userId) {
        return Jdbc.queryList(dataSource, "load playlists",
            rs -> new Playlist(rs.getLong(1), rs.getString(2), rs.getInt(3)), """
                SELECT p.id, p.name, count(pm.movie_id) AS movie_count
                FROM playlists p
                LEFT JOIN playlist_movies pm ON pm.playlist_id = p.id
                WHERE p.user_id = ?
                GROUP BY p.id, p.name, p.updated_at
                ORDER BY p.updated_at DESC, p.id""", userId);
    }

    @Override
    public long playlistsCount(long userId) {
        return Jdbc.queryOne(dataSource, "count playlists", rs -> rs.getLong(1),
            "SELECT count(*) FROM playlists WHERE user_id = ?", userId).orElse(0L);
    }

    // header and movies on one connection: one pool round-trip and one
    // consistent snapshot of the playlist
    @Override
    public Optional<PlaylistDetail> playlist(long userId, long playlistId) {
        return Jdbc.read(dataSource, "load playlist", conn -> {
            var header = Jdbc.queryOne(conn, rs -> new String[]{rs.getString(1), rs.getString(2)},
                "SELECT name, description FROM playlists WHERE id = ? AND user_id = ?", playlistId, userId);
            if (header.isEmpty()) {
                return Optional.<PlaylistDetail>empty();
            }
            var movies = movies(conn, playlistId);
            return Optional.of(new PlaylistDetail(playlistId, header.get()[0], header.get()[1], movies));
        });
    }

    private static List<MovieNote> movies(Connection conn, long playlistId) throws SQLException {
        return Jdbc.queryList(conn, rs -> new MovieNote(rs.getLong(1), rs.getString(2)),
            "SELECT movie_id, note FROM playlist_movies WHERE playlist_id = ? ORDER BY position", playlistId);
    }

    @Override
    public List<PlaylistMembership> playlistsWithMovie(long userId, long movieId) {
        return Jdbc.queryList(dataSource, "load playlist memberships",
            rs -> new PlaylistMembership(rs.getLong(1), rs.getString(2), rs.getBoolean(3), rs.getString(4)), """
                SELECT p.id, p.name, (pm.movie_id IS NOT NULL) AS member, pm.note
                FROM playlists p
                LEFT JOIN playlist_movies pm
                    ON pm.playlist_id = p.id AND pm.movie_id = ?
                WHERE p.user_id = ?
                ORDER BY p.updated_at DESC, p.id""", movieId, userId);
    }

    @Override
    public long createPlaylist(long userId, String name, String description) {
        return Jdbc.queryOne(dataSource, "create playlist", rs -> rs.getLong(1),
            "INSERT INTO playlists (user_id, name, description) VALUES (?, ?, ?) RETURNING id",
            userId, name, description)
            .orElseThrow();
    }

    @Override
    public long createPlaylist(long userId, String name, String description, List<MovieNote> movies) {
        // one transaction: a failure between the create and the adds leaves
        // no half-created playlist behind
        return Jdbc.inTransaction(dataSource, "create playlist", conn -> {
            var id = Jdbc.queryOne(conn, rs -> rs.getLong(1),
                "INSERT INTO playlists (user_id, name, description) VALUES (?, ?, ?) RETURNING id",
                userId, name, description)
                .orElseThrow();
            if (!movies.isEmpty()) {
                insertMovies(conn, id, movies);
            }
            return id;
        });
    }

    @Override
    public boolean renamePlaylist(long userId, long playlistId, String name) {
        return Jdbc.update(dataSource, "rename playlist",
            "UPDATE playlists SET name = ?, updated_at = now() WHERE id = ? AND user_id = ?",
            name, playlistId, userId) == 1;
    }

    @Override
    public boolean updateDescription(long userId, long playlistId, String description) {
        return Jdbc.update(dataSource, "update playlist description",
            "UPDATE playlists SET description = ?, updated_at = now() WHERE id = ? AND user_id = ?",
            description, playlistId, userId) == 1;
    }

    @Override
    public boolean deletePlaylist(long userId, long playlistId) {
        // playlist_movies rows go via ON DELETE CASCADE
        return Jdbc.update(dataSource, "delete playlist",
            "DELETE FROM playlists WHERE id = ? AND user_id = ?", playlistId, userId) == 1;
    }

    @Override
    public void addMovie(long userId, long playlistId, long movieId) {
        addMovies(userId, playlistId, List.of(new MovieNote(movieId, null)));
    }

    // the FOR UPDATE row lock serializes concurrent mutations of the same
    // playlist: max-position-then-insert can no longer collide on
    // UNIQUE (playlist_id, position), and a delete cannot interleave
    @Override
    public void addMovies(long userId, long playlistId, List<MovieNote> movies) {
        if (movies.isEmpty()) {
            return;
        }
        Jdbc.inTransaction(dataSource, "add movies to playlist", conn -> {
            requireOwned(conn, userId, playlistId);
            insertMovies(conn, playlistId, movies);
            touch(conn, playlistId);
            return null;
        });
    }

    private static void insertMovies(Connection conn, long playlistId, List<MovieNote> movies) throws SQLException {
        var next = maxPosition(conn, playlistId);
        for (var movie : movies) {
            Jdbc.update(conn, """
                INSERT INTO playlist_movies (playlist_id, movie_id, position, note)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (playlist_id, movie_id) DO NOTHING""",
                playlistId, movie.movieId(), ++next, movie.note());
        }
    }

    @Override
    public void removeMovie(long userId, long playlistId, long movieId) {
        Jdbc.inTransaction(dataSource, "remove movie from playlist", conn -> {
            requireOwned(conn, userId, playlistId);
            Jdbc.update(conn,
                "DELETE FROM playlist_movies WHERE playlist_id = ? AND movie_id = ?", playlistId, movieId);
            touch(conn, playlistId);
            return null;
        });
    }

    @Override
    public boolean setMovieNote(long userId, long playlistId, long movieId, String note) {
        return Jdbc.inTransaction(dataSource, "set playlist movie note", conn -> {
            requireOwned(conn, userId, playlistId);
            var updated = Jdbc.update(conn, """
                UPDATE playlist_movies SET note = ?
                WHERE playlist_id = ? AND movie_id = ?""", note, playlistId, movieId) == 1;
            if (updated) {
                touch(conn, playlistId);
            }
            return updated;
        });
    }

    @Override
    public boolean moveMovie(long userId, long playlistId, long movieId, boolean up) {
        return Jdbc.inTransaction(dataSource, "move movie in playlist", conn -> {
            requireOwned(conn, userId, playlistId);
            var moved = movePosition(conn, playlistId, movieId, up);
            if (moved) {
                touch(conn, playlistId);
            }
            return moved;
        });
    }

    // positions are unique per playlist but may have gaps (MAX+1 inserts,
    // deletions), so a move is a swap of the two rows' positions — except at
    // the edges, where wrap-around is a single reposition beyond the end
    private static boolean movePosition(Connection conn, long playlistId, long movieId, boolean up)
            throws SQLException {
        var rows = Jdbc.queryList(conn,
            r -> new MoviePosition(r.getLong(1), r.getInt(2)),
            "SELECT movie_id, position FROM playlist_movies WHERE playlist_id = ? ORDER BY position", playlistId);
        var index = -1;
        for (var i = 0; i < rows.size(); i++) {
            if (rows.get(i).movieId() == movieId) {
                index = i;
                break;
            }
        }
        if (index < 0 || rows.size() < 2) {
            return false;
        }
        // (playlist_id, position) is UNIQUE, so an adjacent swap steps through
        // a temporary position beyond the end — every intermediate state is
        // collision-free
        var first = rows.getFirst().position();
        var last = rows.getLast().position();
        if (up && index == 0) {
            setPosition(conn, playlistId, movieId, last + 1); // wrap to the end
        } else if (!up && index == rows.size() - 1) {
            setPosition(conn, playlistId, movieId, first - 1); // wrap to the front
        } else {
            var own = rows.get(index);
            var neighbor = rows.get(up ? index - 1 : index + 1);
            setPosition(conn, playlistId, movieId, last + 1); // step aside
            setPosition(conn, playlistId, neighbor.movieId(), own.position());
            setPosition(conn, playlistId, movieId, neighbor.position());
        }
        return true;
    }

    private static void setPosition(Connection conn, long playlistId, long movieId, int position) throws SQLException {
        Jdbc.update(conn,
            "UPDATE playlist_movies SET position = ? WHERE playlist_id = ? AND movie_id = ?",
            position, playlistId, movieId);
    }

    private record MoviePosition(long movieId, int position) {
    }

    private static void requireOwned(Connection conn, long userId, long playlistId) throws SQLException {
        var owned = Jdbc.queryOne(conn, rs -> true,
            "SELECT 1 FROM playlists WHERE id = ? AND user_id = ? FOR UPDATE", playlistId, userId)
            .isPresent();
        if (!owned) {
            throw new NotFoundException("Playlist not found: " + playlistId);
        }
    }

    private static int maxPosition(Connection conn, long playlistId) throws SQLException {
        return Jdbc.queryOne(conn, rs -> rs.getInt(1),
            "SELECT COALESCE(MAX(position), 0) FROM playlist_movies WHERE playlist_id = ?", playlistId)
            .orElse(0);
    }

    private static void touch(Connection conn, long playlistId) throws SQLException {
        Jdbc.update(conn,
            "UPDATE playlists SET updated_at = now() WHERE id = ?", playlistId);
    }
}
