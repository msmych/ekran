package uk.matvey.ekran.service;

import org.junit.jupiter.api.Test;

import uk.matvey.ekran.domain.Movie;
import uk.matvey.ekran.domain.NotFoundException;
import uk.matvey.ekran.repository.MovieRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;

class MovieServiceTest {

    @Test
    void findByIdsPreservesRequestedOrderAndSkipsMissing() {
        MovieRepository repository = id -> {
            if (id == 404) {
                throw new NotFoundException("gone");
            }
            return id == 348 || id == 9471 ? Optional.of(movie(id)) : Optional.empty();
        };
        var service = new MovieService(repository);

        var movies = service.findByIds(List.of(9471L, 404L, 348L, 999L));

        assertThat(movies).extracting(Movie::tmdbId).containsExactly(9471L, 348L);
    }

    private static Movie movie(long id) {
        return new Movie(
            id, "Movie", null, null, null, List.of(), null, null, null, null,
            List.of(), List.of(), List.of(), null, List.of()
        );
    }
}