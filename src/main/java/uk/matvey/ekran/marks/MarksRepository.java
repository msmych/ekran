package uk.matvey.ekran.marks;

import java.util.List;

public interface MarksRepository {

    List<Long> markedMovieIds(long userId);

    /** Idempotent. */
    void mark(long userId, long movieId);

    /** Idempotent. */
    void unmark(long userId, long movieId);

    /** Bulk, transactional, idempotent. */
    void markAll(long userId, List<Long> movieIds);

    /** Bulk, transactional, idempotent. */
    void unmarkAll(long userId, List<Long> movieIds);
}