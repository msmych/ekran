package uk.matvey.ekran.playlists;

import java.util.List;
import java.util.Optional;

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
    long createPlaylist(long userId, String name);

    /** False if the playlist does not exist or is not owned by the user. */
    boolean renamePlaylist(long userId, long playlistId, String name);

    /** False if the playlist does not exist or is not owned by the user. */
    boolean deletePlaylist(long userId, long playlistId);

    /** Idempotent, ownership-checked. */
    void addMovie(long userId, long playlistId, long movieId);

    /** Bulk, transactional, idempotent, ownership-checked. */
    void addMovies(long userId, long playlistId, List<Long> movieIds);

    /** Idempotent, ownership-checked. */
    void removeMovie(long userId, long playlistId, long movieId);

    /**
     * Moves the movie one slot up or down, wrapping around at the edges
     * (first up → last, last down → first). False if the movie is not in
     * the playlist or is its only member — nothing to swap with.
     */
    boolean moveMovie(long userId, long playlistId, long movieId, boolean up);
}