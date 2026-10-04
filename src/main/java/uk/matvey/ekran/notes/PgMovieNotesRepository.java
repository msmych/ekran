package uk.matvey.ekran.notes;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import uk.matvey.ekran.db.Jdbc;
import uk.matvey.ekran.domain.MovieNote;

public class PgMovieNotesRepository implements MovieNotesRepository {

    private final DataSource dataSource;

    public PgMovieNotesRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String note(long userId, long movieId) {
        return Jdbc.queryOne(dataSource, "load movie note", rs -> rs.getString(1),
            "SELECT note FROM movie_notes WHERE user_id = ? AND movie_id = ?", userId, movieId)
            .orElse(null);
    }

    @Override
    public Map<Long, String> notes(long userId, Collection<Long> movieIds) {
        if (movieIds.isEmpty()) {
            return Map.of();
        }
        // the ids come from the server's own mark set — plain longs, never
        // browser input, so the interpolated IN list is safe
        var ids = String.join(",", movieIds.stream().map(String::valueOf).toList());
        var notes = new HashMap<Long, String>();
        Jdbc.queryList(dataSource, "load movie notes", rs -> new MovieNote(rs.getLong(1), rs.getString(2)), """
            SELECT movie_id, note FROM movie_notes
            WHERE user_id = ? AND movie_id IN (%s)""".formatted(ids), userId)
            .forEach(m -> notes.put(m.movieId(), m.note()));
        return notes;
    }

    @Override
    public List<MovieNote> notedMovies(long userId) {
        return Jdbc.queryList(dataSource, "load noted movies",
            rs -> new MovieNote(rs.getLong(1), rs.getString(2)), """
                SELECT movie_id, note FROM movie_notes
                WHERE user_id = ?
                ORDER BY updated_at DESC, movie_id""", userId);
    }

    @Override
    public long notesCount(long userId) {
        return Jdbc.queryOne(dataSource, "count movie notes", rs -> rs.getLong(1),
            "SELECT count(*) FROM movie_notes WHERE user_id = ?", userId)
            .orElse(0L);
    }

    @Override
    public void setNote(long userId, long movieId, String note) {
        if (note == null) {
            Jdbc.update(dataSource, "delete movie note",
                "DELETE FROM movie_notes WHERE user_id = ? AND movie_id = ?", userId, movieId);
            return;
        }
        Jdbc.update(dataSource, "set movie note", """
            INSERT INTO movie_notes (user_id, movie_id, note)
            VALUES (?, ?, ?)
            ON CONFLICT (user_id, movie_id)
            DO UPDATE SET note = ?, updated_at = now()""",
            userId, movieId, note, note);
    }

    @Override
    public void mergeNotes(long userId, List<MovieNote> movies) {
        var withNotes = movies.stream().filter(m -> m.note() != null).toList();
        if (withNotes.isEmpty()) {
            return;
        }
        // ON CONFLICT DO NOTHING keeps the existing note — the local
        // (browser) note never overwrites one already saved server-side
        Jdbc.batch(dataSource, "merge movie notes", """
            INSERT INTO movie_notes (user_id, movie_id, note)
            VALUES (?, ?, ?)
            ON CONFLICT (user_id, movie_id) DO NOTHING""",
            withNotes.stream().map(m -> new Object[]{userId, m.movieId(), m.note()}).toList());
    }
}