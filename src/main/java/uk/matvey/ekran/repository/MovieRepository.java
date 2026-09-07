package uk.matvey.ekran.repository;

import uk.matvey.ekran.domain.Movie;

import java.util.Optional;

public interface MovieRepository {

    Optional<Movie> findById(long tmdbId);
}