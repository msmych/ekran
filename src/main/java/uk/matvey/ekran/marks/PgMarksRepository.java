package uk.matvey.ekran.marks;

import java.util.List;
import javax.sql.DataSource;
import uk.matvey.ekran.db.Jdbc;
import uk.matvey.ekran.domain.MovieNote;

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
    public List<MovieNote> markedMovies(long userId) {
        return Jdbc.queryList(dataSource, "load marked movies",
            rs -> new MovieNote(rs.getLong(1), rs.getString(2)), """
                SELECT movie_id, note FROM marked_movies
                WHERE user_id = ?
                ORDER BY created_at, movie_id""", userId);
    }

    @Override
    public String markNote(long userId, long movieId) {
        return Jdbc.queryOne(dataSource, "load mark note", rs -> rs.getString(1),
            "SELECT note FROM marked_movies WHERE user_id = ? AND movie_id = ?", userId, movieId)
            .orElse(null);
    }

    @Override
    public boolean setMarkNote(long userId, long movieId, String note) {
        return Jdbc.update(dataSource, "set mark note",
            "UPDATE marked_movies SET note = ? WHERE user_id = ? AND movie_id = ?", note, userId, movieId) == 1;
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

    @Override
    public void mergeMarks(long userId, List<MovieNote> movies) {
        // ON CONFLICT DO NOTHING keeps the existing mark AND its note — the
        // local (browser) note never overwrites a note already saved server-side
        Jdbc.batch(dataSource, "merge marked movies", """
            INSERT INTO marked_movies (user_id, movie_id, note)
            VALUES (?, ?, ?)
            ON CONFLICT (user_id, movie_id) DO NOTHING""",
            movies.stream().map(m -> new Object[]{userId, m.movieId(), m.note()}).toList());
    }
}
