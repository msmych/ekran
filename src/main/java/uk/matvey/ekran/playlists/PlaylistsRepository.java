package uk.matvey.ekran.playlists;

import java.util.List;
import java.util.Optional;
import uk.matvey.ekran.domain.MovieNote;

/**
 * All operations are ownership-scoped: the user id comes from the server-side
 * session and is part of every query, so another user's playlist is
 * indistinguishable from a missing one (404).
 */
public interface PlaylistsRepository {

    List<Playlist> playlists(long userId);

    long playlistsCount(long userId);

    Optional<PlaylistDetail> playlist(long userId, long playlistId);

    List<PlaylistMembership> playlistsWithMovie(long userId, long movieId);

    /** Creates the playlist and returns its id. */
    long createPlaylist(long userId, String name, String description);

    /**
     * Creates the playlist with its movies in one step — no half-created
     * playlist can survive a failure between the create and the adds.
     */
    default long createPlaylist(long userId, String name, String description, List<MovieNote> movies) {
        var id = createPlaylist(userId, name, description);
        if (!movies.isEmpty()) {
            addMovies(userId, id, movies);
        }
        return id;
    }

    /** False if the playlist does not exist or is not owned by the user. */
    boolean renamePlaylist(long userId, long playlistId, String name);

    /** False if the playlist does not exist or is not owned by the user. */
    boolean updateDescription(long userId, long playlistId, String description);

    /** False if the playlist does not exist or is not owned by the user. */
    boolean deletePlaylist(long userId, long playlistId);

    /** Idempotent, ownership-checked. */
    void addMovie(long userId, long playlistId, long movieId);

    /**
     * Bulk, transactional, idempotent, ownership-checked. New memberships carry
     * their note; an existing membership keeps its note and position
     * (ON CONFLICT DO NOTHING).
     */
    void addMovies(long userId, long playlistId, List<MovieNote> movies);

    /** Idempotent, ownership-checked. */
    void removeMovie(long userId, long playlistId, long movieId);

    /**
     * False if the movie is not in the playlist (or the playlist is not owned).
     * Ownership-checked like the other membership operations.
     */
    boolean setMovieNote(long userId, long playlistId, long movieId, String note);

    /**
     * Moves the movie one slot up or down, wrapping around at the edges
     * (first up → last, last down → first). False if the movie is not in
     * the playlist or is its only member — nothing to swap with.
     */
    boolean moveMovie(long userId, long playlistId, long movieId, boolean up);
}
