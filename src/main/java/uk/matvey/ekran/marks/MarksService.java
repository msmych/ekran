package uk.matvey.ekran.marks;

import java.util.List;
import uk.matvey.ekran.domain.MovieNote;

public class MarksService {

    public static final int MAX_NOTE = 500;

    private final MarksRepository repository;

    public MarksService(MarksRepository repository) {
        this.repository = repository;
    }

    public List<Long> markedMovieIds(long userId) {
        return repository.markedMovieIds(userId);
    }

    public List<MovieNote> markedMovies(long userId) {
        return repository.markedMovies(userId);
    }

    public String markNote(long userId, long movieId) {
        return repository.markNote(userId, movieId);
    }

    public boolean setMarkNote(long userId, long movieId, String note) {
        return repository.setMarkNote(userId, movieId, MovieNote.normalize(note));
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

    public void mergeMarks(long userId, List<MovieNote> movies) {
        repository.mergeMarks(userId, movies);
    }

    /** A note that is too long is not normalized away — the caller rejects it with 400. */
    public static boolean validNote(String note) {
        return note == null || note.length() <= MAX_NOTE;
    }
}
