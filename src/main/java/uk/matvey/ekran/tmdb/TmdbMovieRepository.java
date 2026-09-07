package uk.matvey.ekran.tmdb;

import uk.matvey.ekran.domain.Movie;
import uk.matvey.ekran.repository.MovieRepository;

import java.util.Optional;

public class TmdbMovieRepository implements MovieRepository {

    private final TmdbClient client;
    private final TmdbMapper mapper;

    public TmdbMovieRepository(TmdbClient client, TmdbMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public Optional<Movie> findById(long tmdbId) {
        return Optional.of(mapper.toMovie(client.movieWithCredits(tmdbId)));
    }
}