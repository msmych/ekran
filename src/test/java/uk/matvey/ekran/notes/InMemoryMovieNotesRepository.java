package uk.matvey.ekran.notes;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import uk.matvey.ekran.domain.MovieNote;

/** In-memory MovieNotesRepository for route/service tests — no database needed. */
public class InMemoryMovieNotesRepository implements MovieNotesRepository {

    private final Map<Long, Map<Long, String>> notesByUserId = new ConcurrentHashMap<>();

    private Map<Long, String> notes(long userId) {
        return notesByUserId.computeIfAbsent(userId, id -> new LinkedHashMap<>());
    }

    @Override
    public String note(long userId, long movieId) {
        return notes(userId).get(movieId);
    }

    @Override
    public Map<Long, String> notes(long userId, Collection<Long> movieIds) {
        var result = new HashMap<Long, String>();
        for (var movieId : movieIds) {
            var note = notes(userId).get(movieId);
            if (note != null) {
                result.put(movieId, note);
            }
        }
        return result;
    }

    @Override
    public List<MovieNote> notedMovies(long userId) {
        return notes(userId).entrySet().stream()
            .map(e -> new MovieNote(e.getKey(), e.getValue()))
            .toList();
    }

    @Override
    public long notesCount(long userId) {
        return notes(userId).size();
    }

    @Override
    public void setNote(long userId, long movieId, String note) {
        if (note == null) {
            notes(userId).remove(movieId);
        } else {
            notes(userId).put(movieId, note);
        }
    }

    @Override
    public void mergeNotes(long userId, List<MovieNote> movies) {
        movies.stream()
            .filter(m -> m.note() != null)
            .forEach(m -> notes(userId).putIfAbsent(m.movieId(), m.note()));
    }
}
