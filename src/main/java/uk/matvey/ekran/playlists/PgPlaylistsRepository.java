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