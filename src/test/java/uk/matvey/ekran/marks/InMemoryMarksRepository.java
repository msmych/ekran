package uk.matvey.ekran.marks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import uk.matvey.ekran.domain.MovieNote;

/** In-memory MarksRepository for route/service tests — no database needed. */
public class InMemoryMarksRepository implements MarksRepository {

    private final Map<Long, Set<Long>> marksByUserId = new ConcurrentHashMap<>();
    private final Map<Long, Map<Long, String>> notesByUserId = new ConcurrentHashMap<>();

    private Set<Long> marks(long userId) {
        return marksByUserId.computeIfAbsent(userId, id -> new CopyOnWriteArraySet<>());
    }

    private Map<Long, String> notes(long userId) {
        return notesByUserId.computeIfAbsent(userId, id -> new LinkedHashMap<>());
    }

    @Override
    public List<Long> markedMovieIds(long userId) {
        return new ArrayList<>(marks(userId));
    }

    @Override
    public List<MovieNote> markedMovies(long userId) {
        var marked = new ArrayList<MovieNote>();
        for (var movieId : marks(userId)) {
            marked.add(new MovieNote(movieId, notes(userId).get(movieId)));
        }
        return marked;
    }

    @Override
    public String markNote(long userId, long movieId) {
        return marks(userId).contains(movieId) ? notes(userId).get(movieId) : null;
    }

    @Override
    public boolean setMarkNote(long userId, long movieId, String note) {
        if (!marks(userId).contains(movieId)) {
            return false;
        }
        if (note == null) {
            notes(userId).remove(movieId);
        } else {
            notes(userId).put(movieId, note);
        }
        return true;
    }

    @Override
    public void mark(long userId, long movieId) {
        marks(userId).add(movieId);
    }

    @Override
    public void unmark(long userId, long movieId) {
        marks(userId).remove(movieId);
        notes(userId).remove(movieId);
    }

    @Override
    public void markAll(long userId, List<Long> movieIds) {
        marks(userId).addAll(movieIds);
    }

    @Override
    public void unmarkAll(long userId, List<Long> movieIds) {
        marks(userId).removeAll(new LinkedHashSet<>(movieIds));
    }

    @Override
    public void mergeMarks(long userId, List<MovieNote> movies) {
        for (var movie : movies) {
            if (!marks(userId).contains(movie.movieId())) {
                marks(userId).add(movie.movieId());
                if (movie.note() != null) {
                    notes(userId).put(movie.movieId(), movie.note());
                }
            }
        }
    }
}
