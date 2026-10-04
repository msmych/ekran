package uk.matvey.ekran.notes;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import uk.matvey.ekran.domain.MovieNote;

public class MovieNotesService {

    public static final int MAX_NOTE = 500;

    private final MovieNotesRepository repository;

    public MovieNotesService(MovieNotesRepository repository) {
        this.repository = repository;
    }

    public String note(long userId, long movieId) {
        return repository.note(userId, movieId);
    }

    public Map<Long, String> notes(long userId, Collection<Long> movieIds) {
        return repository.notes(userId, movieIds);
    }

    public List<MovieNote> notedMovies(long userId) {
        return repository.notedMovies(userId);
    }

    public long notesCount(long userId) {
        return repository.notesCount(userId);
    }

    public void setNote(long userId, long movieId, String note) {
        repository.setNote(userId, movieId, MovieNote.normalize(note));
    }

    public void mergeNotes(long userId, List<MovieNote> movies) {
        repository.mergeNotes(userId, movies);
    }

    /** A note that is too long is not normalized away — the caller rejects it with 400. */
    public static boolean validNote(String note) {
        return note == null || note.length() <= MAX_NOTE;
    }
}