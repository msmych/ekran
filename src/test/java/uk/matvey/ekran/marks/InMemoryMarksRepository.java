package uk.matvey.ekran.marks;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/** In-memory MarksRepository for route/service tests — no database needed. */
public class InMemoryMarksRepository implements MarksRepository {

    private final Map<Long, Set<Long>> marksByUserId = new ConcurrentHashMap<>();

    private Set<Long> marks(long userId) {
        return marksByUserId.computeIfAbsent(userId, id -> new CopyOnWriteArraySet<>());
    }

    @Override
    public List<Long> markedMovieIds(long userId) {
        return new ArrayList<>(marks(userId));
    }

    @Override
    public void mark(long userId, long movieId) {
        marks(userId).add(movieId);
    }

    @Override
    public void unmark(long userId, long movieId) {
        marks(userId).remove(movieId);
    }

    @Override
    public void markAll(long userId, List<Long> movieIds) {
        marks(userId).addAll(movieIds);
    }

    @Override
    public void unmarkAll(long userId, List<Long> movieIds) {
        marks(userId).removeAll(new LinkedHashSet<>(movieIds));
    }
}