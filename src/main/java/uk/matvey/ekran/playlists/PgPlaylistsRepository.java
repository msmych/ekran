package uk.matvey.ekran.playlists;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import uk.matvey.ekran.domain.NotFoundException;

public class PgPlaylistsRepository implements PlaylistsRepository {

    private final DataSource dataSource;

    public PgPlaylistsRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<Playlist> playlists(long userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                 SELECT p.id, p.name, count(pm.movie_id) AS movie_count
                 FROM playlists p
                 LEFT JOIN playlist_movies pm ON pm.playlist_id = p.id
                 WHERE p.user_id = ?
                 GROUP BY p.id, p.name, p.updated_at
                 ORDER BY p.updated_at DESC, p.id""")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                var playlists = new ArrayList<Playlist>();
                while (rs.next()) {
                    playlists.add(new Playlist(rs.getLong(1), rs.getString(2), rs.getInt(3)));
                }
                return playlists;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot load playlists: " + e.getMessage(), e);
        }
    }

    @Override
    public long playlistsCount(long userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM playlists WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot count playlists: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<PlaylistDetail> playlist(long userId, long playlistId) {
        String name;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT name FROM playlists WHERE id = ? AND user_id = ?")) {
            ps.setLong(1, playlistId);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                name = rs.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot load playlist: " + e.getMessage(), e);
        }
        return Optional.of(new PlaylistDetail(playlistId, name, movieIds(playlistId)));
    }

    private List<Long> movieIds(long playlistId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT movie_id FROM playlist_movies WHERE playlist_id = ? ORDER BY position")) {
            ps.setLong(1, playlistId);
            try (ResultSet rs = ps.executeQuery()) {
                var ids = new ArrayList<Long>();
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
                return ids;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot load playlist movies: " + e.getMessage(), e);
        }
    }

    @Override
    public List<PlaylistMembership> playlistsWithMovie(long userId, long movieId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                 SELECT p.id, p.name, EXISTS (
                     SELECT 1 FROM playlist_movies pm
                     WHERE pm.playlist_id = p.id AND pm.movie_id = ?
                 ) AS member
                 FROM playlists p
                 WHERE p.user_id = ?
                 ORDER BY p.updated_at DESC, p.id""")) {
            ps.setLong(1, movieId);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                var memberships = new ArrayList<PlaylistMembership>();
                while (rs.next()) {
                    memberships.add(new PlaylistMembership(rs.getLong(1), rs.getString(2), rs.getBoolean(3)));
                }
                return memberships;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot load playlist memberships: " + e.getMessage(), e);
        }
    }

    @Override
    public long createPlaylist(long userId, String name) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO playlists (user_id, name) VALUES (?, ?) RETURNING id")) {
            ps.setLong(1, userId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create playlist: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean renamePlaylist(long userId, long playlistId, String name) {
        return update(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE playlists SET name = ?, updated_at = now() WHERE id = ? AND user_id = ?")) {
                ps.setString(1, name);
                ps.setLong(2, playlistId);
                ps.setLong(3, userId);
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public boolean deletePlaylist(long userId, long playlistId) {
        // playlist_movies rows go via ON DELETE CASCADE
        return update(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM playlists WHERE id = ? AND user_id = ?")) {
                ps.setLong(1, playlistId);
                ps.setLong(2, userId);
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public void addMovie(long userId, long playlistId, long movieId) {
        addMovies(userId, playlistId, List.of(movieId));
    }

    @Override
    public void addMovies(long userId, long playlistId, List<Long> movieIds) {
        if (movieIds.isEmpty()) {
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!owned(conn, userId, playlistId)) {
                    throw new NotFoundException("Playlist not found: " + playlistId);
                }
                var next = maxPosition(conn, playlistId);
                try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO playlist_movies (playlist_id, movie_id, position)
                    VALUES (?, ?, ?)
                    ON CONFLICT (playlist_id, movie_id) DO NOTHING""")) {
                    for (var movieId : movieIds) {
                        ps.setLong(1, playlistId);
                        ps.setLong(2, movieId);
                        ps.setInt(3, ++next);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                touch(conn, userId, playlistId);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot add movies to playlist: " + e.getMessage(), e);
        }
    }

    @Override
    public void removeMovie(long userId, long playlistId, long movieId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!owned(conn, userId, playlistId)) {
                    throw new NotFoundException("Playlist not found: " + playlistId);
                }
                try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM playlist_movies WHERE playlist_id = ? AND movie_id = ?")) {
                    ps.setLong(1, playlistId);
                    ps.setLong(2, movieId);
                    ps.executeUpdate();
                }
                touch(conn, userId, playlistId);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot remove movie from playlist: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean moveMovie(long userId, long playlistId, long movieId, boolean up) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!owned(conn, userId, playlistId)) {
                    throw new NotFoundException("Playlist not found: " + playlistId);
                }
                var moved = movePosition(conn, playlistId, movieId, up);
                if (moved) {
                    touch(conn, userId, playlistId);
                }
                conn.commit();
                return moved;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot move movie in playlist: " + e.getMessage(), e);
        }
    }

    // positions are unique per playlist but may have gaps (MAX+1 inserts,
    // deletions), so a move is a swap of the two rows' positions — except at
    // the edges, where wrap-around is a single reposition beyond the end
    private static boolean movePosition(Connection conn, long playlistId, long movieId, boolean up)
            throws SQLException {
        List<MoviePosition> rows;
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT movie_id, position FROM playlist_movies WHERE playlist_id = ? ORDER BY position")) {
            ps.setLong(1, playlistId);
            try (ResultSet rs = ps.executeQuery()) {
                rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new MoviePosition(rs.getLong(1), rs.getInt(2)));
                }
            }
        }
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
        try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE playlist_movies SET position = ? WHERE playlist_id = ? AND movie_id = ?")) {
            ps.setInt(1, position);
            ps.setLong(2, playlistId);
            ps.setLong(3, movieId);
            ps.executeUpdate();
        }
    }

    private record MoviePosition(long movieId, int position) {
    }

    private static boolean owned(Connection conn, long userId, long playlistId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT 1 FROM playlists WHERE id = ? AND user_id = ?")) {
            ps.setLong(1, playlistId);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static int maxPosition(Connection conn, long playlistId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT COALESCE(MAX(position), 0) FROM playlist_movies WHERE playlist_id = ?")) {
            ps.setLong(1, playlistId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void touch(Connection conn, long userId, long playlistId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE playlists SET updated_at = now() WHERE id = ? AND user_id = ?")) {
            ps.setLong(1, playlistId);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    private boolean update(SqlOperation operation) {
        try (Connection conn = dataSource.getConnection()) {
            return operation.apply(conn);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot update playlist: " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface SqlOperation {
        boolean apply(Connection conn) throws SQLException;
    }
}