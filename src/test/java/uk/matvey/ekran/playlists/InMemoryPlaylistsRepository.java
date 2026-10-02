package uk.matvey.ekran.playlists;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory PlaylistsRepository for route/service tests — no database needed. */
public class InMemoryPlaylistsRepository implements PlaylistsRepository {

    private record StoredPlaylist(long id, long userId, String name, long updatedAt) {
    }

    private final Map<Long, StoredPlaylist> playlists = new ConcurrentHashMap<>();
    private final Map<Long, Map<Long, Integer>> moviesByPlaylistId = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private long clock = 0;

    @Override
    public List<Playlist> playlists(long userId) {
        return playlists.values().stream()
            .filter(p -> p.userId() == userId)
            .sorted((a, b) -> Long.compare(b.updatedAt(), a.updatedAt()))
            .map(p -> new Playlist(p.id(), p.name(), moviesOf(p.id()).size()))
            .toList();
    }

    @Override
    public long playlistsCount(long userId) {
        return playlists.values().stream()
            .filter(p -> p.userId() == userId)
            .count();
    }

    @Override
    public Optional<PlaylistDetail> playlist(long userId, long playlistId) {
        var playlist = playlists.get(playlistId);
        if (playlist == null || playlist.userId() != userId) {
            return Optional.empty();
        }
        return Optional.of(new PlaylistDetail(playlistId, playlist.name(), movieIdsOf(playlistId)));
    }

    @Override
    public List<PlaylistMembership> playlistsWithMovie(long userId, long movieId) {
        return playlists(userId).stream()
            .map(p -> new PlaylistMembership(p.id(), p.name(), moviesOf(p.id()).containsKey(movieId)))
            .toList();
    }

    @Override
    public long createPlaylist(long userId, String name) {
        var id = ids.incrementAndGet();
        playlists.put(id, new StoredPlaylist(id, userId, name, ++clock));
        moviesByPlaylistId.put(id, new LinkedHashMap<>());
        return id;
    }

    @Override
    public boolean renamePlaylist(long userId, long playlistId, String name) {
        var playlist = playlists.get(playlistId);
        if (playlist == null || playlist.userId() != userId) {
            return false;
        }
        playlists.put(playlistId, new StoredPlaylist(playlistId, userId, name, ++clock));
        return true;
    }

    @Override
    public boolean deletePlaylist(long userId, long playlistId) {
        var playlist = playlists.get(playlistId);
        if (playlist == null || playlist.userId() != userId) {
            return false;
        }
        playlists.remove(playlistId);
        moviesByPlaylistId.remove(playlistId);
        return true;
    }

    @Override
    public void addMovie(long userId, long playlistId, long movieId) {
        addMovies(userId, playlistId, List.of(movieId));
    }

    @Override
    public void addMovies(long userId, long playlistId, List<Long> movieIds) {
        var playlist = playlists.get(playlistId);
        if (playlist == null || playlist.userId() != userId) {
            throw new uk.matvey.ekran.domain.NotFoundException("Playlist not found: " + playlistId);
        }
        var movies = moviesOf(playlistId);
        var next = movies.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        for (var movieId : movieIds) {
            movies.putIfAbsent(movieId, ++next);
        }
        playlists.put(playlistId, new StoredPlaylist(playlistId, userId, playlist.name(), ++clock));
    }

    @Override
public void removeMovie(long userId, long playlistId, long movieId) {
    var playlist = playlists.get(playlistId);
    if (playlist == null || playlist.userId() != userId) {
        throw new uk.matvey.ekran.domain.NotFoundException("Playlist not found: " + playlistId);
    }
    moviesOf(playlistId).remove(movieId);
    playlists.put(playlistId, new StoredPlaylist(playlistId, userId, playlist.name(), ++clock));
}

@Override
public boolean moveMovie(long userId, long playlistId, long movieId, boolean up) {
    var playlist = playlists.get(playlistId);
    if (playlist == null || playlist.userId() != userId) {
        throw new uk.matvey.ekran.domain.NotFoundException("Playlist not found: " + playlistId);
    }
    var order = movieIdsOf(playlistId);
    var index = order.indexOf(movieId);
    if (index < 0 || order.size() < 2) {
        return false;
    }
    // wrap-around at the edges, like the Pg repository
    var target = up ? (index == 0 ? order.size() - 1 : index - 1)
        : (index == order.size() - 1 ? 0 : index + 1);
    var reordered = new ArrayList<>(order);
    reordered.remove(index);
    reordered.add(target, movieId);
    var movies = new LinkedHashMap<Long, Integer>();
    for (var i = 0; i < reordered.size(); i++) {
        movies.put(reordered.get(i), i + 1);
    }
    moviesByPlaylistId.put(playlistId, movies);
    playlists.put(playlistId, new StoredPlaylist(playlistId, userId, playlist.name(), ++clock));
    return true;
}

    private Map<Long, Integer> moviesOf(long playlistId) {
        return moviesByPlaylistId.computeIfAbsent(playlistId, id -> new LinkedHashMap<>());
    }

    public List<Long> movieIdsOf(long playlistId) {
        var movies = moviesByPlaylistId.get(playlistId);
        if (movies == null) {
            return new ArrayList<>();
        }
        synchronized (movies) {
            return movies.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .toList();
        }
    }
}