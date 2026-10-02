package uk.matvey.ekran.repository;

import java.util.Optional;
import uk.matvey.ekran.domain.Movie;

public interface MovieRepository {

    Optional<Movie> findById(long tmdbId);
}
