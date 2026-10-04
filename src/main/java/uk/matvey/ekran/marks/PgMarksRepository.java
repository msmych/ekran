package uk.matvey.ekran.marks;

import java.util.List;
import javax.sql.DataSource;
import uk.matvey.ekran.db.Jdbc;

public class PgMarksRepository implements MarksRepository {

    private final DataSource dataSource;

    public PgMarksRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<Long> markedMovieIds(long userId) {
        return Jdbc.queryList(dataSource, "load marked movies", rs -> rs.getLong(1), """
            SELECT movie_id FROM marked_movies
            WHERE user_id = ?
            ORDER BY created_at, movie_id""", userId);
    }

    @Override
    public void mark(long userId, long movieId) {
        Jdbc.update(dataSource, "mark movie",
            "INSERT INTO marked_movies (user_id, movie_id) VALUES (?, ?) ON CONFLICT DO NOTHING", userId, movieId);
    }

    @Override
    public void unmark(long userId, long movieId) {
        Jdbc.update(dataSource, "unmark movie",
            "DELETE FROM marked_movies WHERE user_id = ? AND movie_id = ?", userId, movieId);
    }

    @Override
    public void markAll(long userId, List<Long> movieIds) {
        Jdbc.batch(dataSource, "mark movies",
            "INSERT INTO marked_movies (user_id, movie_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            movieIds.stream().map(id -> new Object[]{userId, id}).toList());
    }

    @Override
    public void unmarkAll(long userId, List<Long> movieIds) {
        Jdbc.batch(dataSource, "unmark movies",
            "DELETE FROM marked_movies WHERE user_id = ? AND movie_id = ?",
            movieIds.stream().map(id -> new Object[]{userId, id}).toList());
    }
}
