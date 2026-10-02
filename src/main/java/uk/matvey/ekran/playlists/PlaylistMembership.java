package uk.matvey.ekran.playlists;

/** The user's playlist with its membership state and note for one movie (movie-page selector). */
public record PlaylistMembership(long id, String name, boolean member, String note) {
}
