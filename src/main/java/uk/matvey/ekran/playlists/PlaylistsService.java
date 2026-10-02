package uk.matvey.ekran.playlists;

import java.util.List;
import java.util.Optional;

public class PlaylistsService {

    public static final int MAX_NAME = 60;

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

    public long createPlaylist(long userId, String name) {
        return repository.createPlaylist(userId, validName(name).orElseThrow());
    }

    public boolean renamePlaylist(long userId, long playlistId, String name) {
        return repository.renamePlaylist(userId, playlistId, validName(name).orElseThrow());
    }

    public boolean deletePlaylist(long userId, long playlistId) {
        return repository.deletePlaylist(userId, playlistId);
    }

    public void addMovie(long userId, long playlistId, long movieId) {
        repository.addMovie(userId, playlistId, movieId);
    }

    public void addMovies(long userId, long playlistId, List<Long> movieIds) {
        repository.addMovies(userId, playlistId, movieIds);
    }

    public void removeMovie(long userId, long playlistId, long movieId) {
        repository.removeMovie(userId, playlistId, movieId);
    }

    public boolean moveMovie(long userId, long playlistId, long movieId, boolean up) {
        return repository.moveMovie(userId, playlistId, movieId, up);
    }
}