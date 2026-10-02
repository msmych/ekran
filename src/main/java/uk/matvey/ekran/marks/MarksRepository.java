package uk.matvey.ekran.marks;

import java.util.List;
import uk.matvey.ekran.domain.MovieNote;

public interface MarksRepository {

    List<Long> markedMovieIds(long userId);

    /** Marked movies with their notes, in mark order. */
    List<MovieNote> markedMovies(long userId);

    /** The note of the user's mark, or null when the movie is not marked or has no note. */
    String markNote(long userId, long movieId);

    /** False if the movie is not marked — the note belongs to the mark. */
    boolean setMarkNote(long userId, long movieId, String note);

    /** Idempotent. */
    void mark(long userId, long movieId);

    /** Idempotent. */
    void unmark(long userId, long movieId);

    /** Bulk, transactional, idempotent. */
    void markAll(long userId, List<Long> movieIds);

    /** Bulk, transactional, idempotent. */
    void unmarkAll(long userId, List<Long> movieIds);

    /**
     * Bulk, transactional, idempotent: the anonymous → authenticated migration.
     * A movie already marked in PostgreSQL keeps its existing mark and note —
     * the local note is only written for newly created marks.
     */
    void mergeMarks(long userId, List<MovieNote> movies);
}
