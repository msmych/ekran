package uk.matvey.ekran.playlists;

import java.util.List;

/** A playlist the caller owns, with its movie ids in stable add order. */
public record PlaylistDetail(long id, String name, List<Long> movieIds) {

    public List<Long> movieIds() {
        return List.copyOf(movieIds);
    }
}