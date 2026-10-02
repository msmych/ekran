package uk.matvey.ekran.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.matvey.ekran.domain.Movie;
import uk.matvey.ekran.domain.NotFoundException;
import uk.matvey.ekran.repository.MovieRepository;

/**
 * Movie metadata is immutable for all practical purposes, and TMDB has no
 * batch "details by ids" endpoint — list pages would otherwise pay one
 * sequential HTTP call per card (up to {@code MovieIds.MAX_SET = 100}).
 * A day-long cache makes repeat visits instant and keeps the cold path
 * bounded by cache misses alone.
 */
public class MovieService {

    private static final Logger log = LoggerFactory.getLogger(MovieService.class);

    private final MovieRepository repository;
    private final Cache<Long, Optional<Movie>> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofHours(24))
        .maximumSize(10_000)
        .build();

    public MovieService(MovieRepository repository) {
        this.repository = repository;
    }

    public Optional<Movie> findById(long tmdbId) {
        try {
            return cache.get(tmdbId, repository::findById);
        } catch (CompletionException e) {
            // a missing TMDB movie surfaces as NotFoundException from the
            // client — it must propagate as itself, not wrapped
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    public List<Movie> findByIds(List<Long> tmdbIds) {
        var movies = new ArrayList<Movie>();
        for (var id : tmdbIds) {
            try {
                findById(id).ifPresent(movies::add);
            } catch (NotFoundException e) {
                log.warn("Skipping unavailable movie {} in list: {}", id, e.getMessage());
            }
        }
        return movies;
    }
}
