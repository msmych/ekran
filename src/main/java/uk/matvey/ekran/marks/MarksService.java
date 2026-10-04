package uk.matvey.ekran.marks;

import java.util.List;

/**
 * Marks are the quick inbox — a bare set of movie ids, no notes attached
 * (movie notes live in their own domain; playlist notes on memberships).
 */
public class MarksService {

    private final MarksRepository repository;

    public MarksService(MarksRepository repository) {
        this.repository = repository;
    }

    public List<Long> markedMovieIds(long userId) {
        return repository.markedMovieIds(userId);
    }

    public void mark(long userId, long movieId) {
        repository.mark(userId, movieId);
    }

    public void unmark(long userId, long movieId) {
        repository.unmark(userId, movieId);
    }

    public void markAll(long userId, List<Long> movieIds) {
        repository.markAll(userId, movieIds);
    }

    public void unmarkAll(long userId, List<Long> movieIds) {
        repository.unmarkAll(userId, movieIds);
    }
}