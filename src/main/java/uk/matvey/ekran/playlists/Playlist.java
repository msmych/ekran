package uk.matvey.ekran.playlists;

/** A playlist as shown in the index: name plus current movie count. */
public record Playlist(long id, String name, int movieCount) {
}
