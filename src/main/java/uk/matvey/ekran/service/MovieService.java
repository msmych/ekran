package uk.matvey.ekran.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.matvey.ekran.domain.Movie;
import uk.matvey.ekran.domain.NotFoundException;
import uk.matvey.ekran.repository.MovieRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class MovieService {

    private static final Logger log = LoggerFactory.getLogger(MovieService.class);

    private final MovieRepository repository;

    public MovieService(MovieRepository repository) {
        this.repository = repository;
    }

    public Optional<Movie> findById(long tmdbId) {
        return repository.findById(tmdbId);
    }

    public List<Movie> findByIds(List<Long> tmdbIds) {
        var movies = new ArrayList<Movie>();
        for (var id : tmdbIds) {
            try {
                repository.findById(id).ifPresent(movies::add);
            } catch (NotFoundException e) {
                log.warn("Skipping unavailable movie {} in list: {}", id, e.getMessage());
            }
        }
        return movies;
    }
}