package uk.matvey.ekran.marks;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

public class PgMarksRepository implements MarksRepository {

    private final DataSource dataSource;

    public PgMarksRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<Long> markedMovieIds(long userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                 SELECT movie_id FROM marked_movies
                 WHERE user_id = ?
                 ORDER BY created_at, movie_id""")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                var ids = new ArrayList<Long>();
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
                return ids;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot load marked movies: " + e.getMessage(), e);
        }
    }

    @Override
    public void mark(long userId, long movieId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO marked_movies (user_id, movie_id) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            ps.setLong(1, userId);
            ps.setLong(2, movieId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot mark movie: " + e.getMessage(), e);
        }
    }

    @Override
    public void unmark(long userId, long movieId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM marked_movies WHERE user_id = ? AND movie_id = ?")) {
            ps.setLong(1, userId);
            ps.setLong(2, movieId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot unmark movie: " + e.getMessage(), e);
        }
    }

    @Override
    public void markAll(long userId, List<Long> movieIds) {
        mutateAll("INSERT INTO marked_movies (user_id, movie_id) VALUES (?, ?) ON CONFLICT DO NOTHING", userId, movieIds);
    }

    @Override
    public void unmarkAll(long userId, List<Long> movieIds) {
        mutateAll("DELETE FROM marked_movies WHERE user_id = ? AND movie_id = ?", userId, movieIds);
    }

    private void mutateAll(String sql, long userId, List<Long> movieIds) {
        if (movieIds.isEmpty()) {
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (var movieId : movieIds) {
                    ps.setLong(1, userId);
                    ps.setLong(2, movieId);
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot update marked movies: " + e.getMessage(), e);
        }
    }
}