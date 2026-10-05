package uk.matvey.ekran.playlists;

import java.util.List;

/** A playlist as shown in the index: name, description, movie count and the
 *  movie ids in position order (capped — they feed the share URL). */
public record Playlist(long id, String name, String description, int movieCount, List<Long> movieIds) {
}
