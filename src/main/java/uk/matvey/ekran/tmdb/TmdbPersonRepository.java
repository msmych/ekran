package uk.matvey.ekran.tmdb;

import uk.matvey.ekran.domain.Person;
import uk.matvey.ekran.repository.PersonRepository;

import java.util.Optional;

public class TmdbPersonRepository implements PersonRepository {

    private final TmdbClient client;
    private final TmdbMapper mapper;

    public TmdbPersonRepository(TmdbClient client, TmdbMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public Optional<Person> findById(long tmdbId) {
        return Optional.of(mapper.toPerson(client.personWithMovieCredits(tmdbId)));
    }
}