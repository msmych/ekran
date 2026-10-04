package uk.matvey.ekran.notes;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import uk.matvey.ekran.domain.MovieNote;

/**
 * The user's notes on movies — independent of marks (the quick inbox) and of
 * playlist membership notes (deliberate playlist annotation). The user id
 * comes from the server-side session, never from the browser.
 */
public interface MovieNotesRepository {

    /** The user's note on the movie, or null when there is none. */
    String note(long userId, long movieId);

    /** The user's notes on the given movies; only present notes are in the map. */
    Map<Long, String> notes(long userId, Collection<Long> movieIds);

    /** The user's noted movies, most recently edited first. */
    List<MovieNote> notedMovies(long userId);

    /** For the account menu count. */
    long notesCount(long userId);

    /** Upsert; an empty note deletes the row — absent data stays absent. */
    void setNote(long userId, long movieId, String note);

    /**
     * Bulk, idempotent: the anonymous → authenticated migration.
     * An existing server-side note always wins over the local (browser) one.
     */
    void mergeNotes(long userId, List<MovieNote> movies);
}