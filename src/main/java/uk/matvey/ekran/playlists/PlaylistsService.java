package uk.matvey.ekran.playlists;

import java.util.List;
import java.util.Optional;
import uk.matvey.ekran.domain.MovieNote;

public class PlaylistsService {

    public static final int MAX_NAME = 60;
    public static final int MAX_DESCRIPTION = 1000;

    private final PlaylistsRepository repository;

    public PlaylistsService(PlaylistsRepository repository) {
        this.repository = repository;
    }

    /** Trims and validates a playlist name: non-empty, at most 60 characters. */
    public static Optional<String> validName(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        var name = raw.strip();
        return name.isEmpty() || name.length() > MAX_NAME ? Optional.empty() : Optional.of(name);
    }

    /** Trims a description: null/blank → null (no description). */
    public static String normalizedDescription(String raw) {
        if (raw == null) {
            return null;
        }
        var description = raw.strip();
        return description.isEmpty() ? null : description;
    }

    /** Whether the movie belongs to at least one playlist (drives the Edit/+ Add button label). */
    public static boolean hasMemberships(List<PlaylistMembership> memberships) {
        return memberships.stream().anyMatch(PlaylistMembership::member);
    }

    public List<Playlist> playlists(long userId) {
        return repository.playlists(userId);
    }

    public long playlistsCount(long userId) {
        return repository.playlistsCount(userId);
    }

    public Optional<PlaylistDetail> playlist(long userId, long playlistId) {
        return repository.playlist(userId, playlistId);
    }

    public List<PlaylistMembership> playlistsWithMovie(long userId, long movieId) {
        return repository.playlistsWithMovie(userId, movieId);
    }

    public long createPlaylist(long userId, String name, String description) {
        return createPlaylist(userId, name, description, List.of());
    }

    /** Creates the playlist and adds the movies atomically, carrying their notes. */
    public long createPlaylist(long userId, String name, String description, List<MovieNote> movies) {
        return repository.createPlaylist(userId, validName(name).orElseThrow(), normalizedDescription(description), movies);
    }

    public boolean renamePlaylist(long userId, long playlistId, String name) {
        return repository.renamePlaylist(userId, playlistId, validName(name).orElseThrow());
    }

    public boolean updateDescription(long userId, long playlistId, String description) {
        return repository.updateDescription(userId, playlistId, normalizedDescription(description));
    }

    public boolean deletePlaylist(long userId, long playlistId) {
        return repository.deletePlaylist(userId, playlistId);
    }

    public void addMovie(long userId, long playlistId, long movieId) {
        repository.addMovie(userId, playlistId, movieId);
    }

    public void addMovies(long userId, long playlistId, List<MovieNote> movies) {
        repository.addMovies(userId, playlistId, movies);
    }

    public void removeMovie(long userId, long playlistId, long movieId) {
        repository.removeMovie(userId, playlistId, movieId);
    }

    public boolean setMovieNote(long userId, long playlistId, long movieId, String note) {
        return repository.setMovieNote(userId, playlistId, movieId, MovieNote.normalize(note));
    }

    public boolean moveMovie(long userId, long playlistId, long movieId, boolean up) {
        return repository.moveMovie(userId, playlistId, movieId, up);
    }
}
