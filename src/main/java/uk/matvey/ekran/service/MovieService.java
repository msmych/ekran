package uk.matvey.ekran.service;

import uk.matvey.ekran.domain.Movie;
import uk.matvey.ekran.repository.MovieRepository;

import java.util.Optional;

public class MovieService {

    private final MovieRepository repository;

    public MovieService(MovieRepository repository) {
        this.repository = repository;
    }

    public Optional<Movie> findById(long tmdbId) {
        return repository.findById(tmdbId);
    }
}