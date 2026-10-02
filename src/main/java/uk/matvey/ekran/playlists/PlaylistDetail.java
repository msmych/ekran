package uk.matvey.ekran.playlists;

import java.util.List;
import uk.matvey.ekran.domain.MovieNote;

/** A playlist the caller owns: its movies with membership notes, in stable position order. */
public record PlaylistDetail(long id, String name, String description, List<MovieNote> movies) {

    public PlaylistDetail {
        movies = List.copyOf(movies);
    }

    public List<Long> movieIds() {
        return movies.stream().map(MovieNote::movieId).toList();
    }
}
