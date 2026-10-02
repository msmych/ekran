package uk.matvey.ekran.playlists;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import uk.matvey.ekran.domain.MovieNote;
import uk.matvey.ekran.domain.NotFoundException;

/** In-memory PlaylistsRepository for route/service tests — no database needed. */
public class InMemoryPlaylistsRepository implements PlaylistsRepository {

    private record StoredPlaylist(long id, long userId, String name, String description, long updatedAt) {
    }

    private final Map<Long, StoredPlaylist> playlists = new ConcurrentHashMap<>();
    private final Map<Long, Map<Long, Integer>> positionsByPlaylistId = new ConcurrentHashMap<>();
    private final Map<Long, Map<Long, String>> notesByPlaylistId = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private long clock = 0;

    @Override
    public List<Playlist> playlists(long userId) {
        return playlists.values().stream()
            .filter(p -> p.userId() == userId)
            .sorted((a, b) -> Long.compare(b.updatedAt(), a.updatedAt()))
            .map(p -> new Playlist(p.id(), p.name(), positionsOf(p.id()).size()))
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
        return Optional.of(new PlaylistDetail(playlistId, playlist.name(), playlist.description(), moviesOf(playlistId)));
    }

    private List<MovieNote> moviesOf(long playlistId) {
        return movieIdsOf(playlistId).stream()
            .map(id -> new MovieNote(id, notesOf(playlistId).get(id)))
            .toList();
    }

    @Override
    public List<PlaylistMembership> playlistsWithMovie(long userId, long movieId) {
        return playlists(userId).stream()
            .map(p -> {
                var member = positionsOf(p.id()).containsKey(movieId);
                return new PlaylistMembership(p.id(), p.name(), member, member ? notesOf(p.id()).get(movieId) : null);
            })
            .toList();
    }

    @Override
    public long createPlaylist(long userId, String name, String description) {
        var id = ids.incrementAndGet();
        playlists.put(id, new StoredPlaylist(id, userId, name, description, ++clock));
        positionsByPlaylistId.put(id, new LinkedHashMap<>());
        notesByPlaylistId.put(id, new LinkedHashMap<>());
        return id;
    }

    @Override
    public boolean renamePlaylist(long userId, long playlistId, String name) {
        return mutatePlaylist(userId, playlistId, p -> new StoredPlaylist(p.id(), p.userId(), name, p.description(), ++clock));
    }

    @Override
    public boolean updateDescription(long userId, long playlistId, String description) {
        return mutatePlaylist(userId, playlistId, p -> new StoredPlaylist(p.id(), p.userId(), p.name(), description, ++clock));
    }

    @Override
    public boolean deletePlaylist(long userId, long playlistId) {
        var playlist = playlists.get(playlistId);
        if (playlist == null || playlist.userId() != userId) {
            return false;
        }
        playlists.remove(playlistId);
        positionsByPlaylistId.remove(playlistId);
        notesByPlaylistId.remove(playlistId);
        return true;
    }

    @Override
    public void addMovie(long userId, long playlistId, long movieId) {
        addMovies(userId, playlistId, List.of(new MovieNote(movieId, null)));
    }

    @Override
    public void addMovies(long userId, long playlistId, List<MovieNote> movies) {
        var playlist = owned(userId, playlistId);
        var positions = positionsOf(playlistId);
        var notes = notesOf(playlistId);
        var next = positions.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        for (var movie : movies) {
            if (positions.putIfAbsent(movie.movieId(), ++next) == null && movie.note() != null) {
                notes.put(movie.movieId(), movie.note());
            }
        }
        touch(playlist);
    }

    @Override
    public void removeMovie(long userId, long playlistId, long movieId) {
        var playlist = owned(userId, playlistId);
        positionsOf(playlistId).remove(movieId);
        notesOf(playlistId).remove(movieId);
        touch(playlist);
    }

    @Override
    public boolean setMovieNote(long userId, long playlistId, long movieId, String note) {
        var playlist = owned(userId, playlistId);
        if (!positionsOf(playlistId).containsKey(movieId)) {
            return false;
        }
        var notes = notesOf(playlistId);
        if (note == null) {
            notes.remove(movieId);
        } else {
            notes.put(movieId, note);
        }
        touch(playlist);
        return true;
    }

    @Override
    public boolean moveMovie(long userId, long playlistId, long movieId, boolean up) {
        var playlist = owned(userId, playlistId);
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
        var positions = new LinkedHashMap<Long, Integer>();
        for (var i = 0; i < reordered.size(); i++) {
            positions.put(reordered.get(i), i + 1);
        }
        positionsByPlaylistId.put(playlistId, positions);
        touch(playlist);
        return true;
    }

    private StoredPlaylist owned(long userId, long playlistId) {
        var playlist = playlists.get(playlistId);
        if (playlist == null || playlist.userId() != userId) {
            throw new NotFoundException("Playlist not found: " + playlistId);
        }
        return playlist;
    }

    private boolean mutatePlaylist(long userId, long playlistId, java.util.function.UnaryOperator<StoredPlaylist> mutation) {
        var playlist = playlists.get(playlistId);
        if (playlist == null || playlist.userId() != userId) {
            return false;
        }
        playlists.put(playlistId, mutation.apply(playlist));
        return true;
    }

    private void touch(StoredPlaylist playlist) {
        playlists.put(playlist.id(), new StoredPlaylist(playlist.id(), playlist.userId(), playlist.name(), playlist.description(), ++clock));
    }

    private Map<Long, Integer> positionsOf(long playlistId) {
        return positionsByPlaylistId.computeIfAbsent(playlistId, id -> new LinkedHashMap<>());
    }

    private Map<Long, String> notesOf(long playlistId) {
        return notesByPlaylistId.computeIfAbsent(playlistId, id -> new LinkedHashMap<>());
    }

    public List<Long> movieIdsOf(long playlistId) {
        var positions = positionsByPlaylistId.get(playlistId);
        if (positions == null) {
            return new ArrayList<>();
        }
        synchronized (positions) {
            return positions.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .toList();
        }
    }
}
