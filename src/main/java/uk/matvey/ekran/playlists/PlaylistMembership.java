package uk.matvey.ekran.playlists;

/** The user's playlist with its membership state for one movie (movie-page selector). */
public record PlaylistMembership(long id, String name, boolean member) {
}