package uk.matvey.ekran.service;

import java.util.Optional;
import uk.matvey.ekran.domain.Person;
import uk.matvey.ekran.repository.PersonRepository;

public class PersonService {

    private final PersonRepository repository;

    public PersonService(PersonRepository repository) {
        this.repository = repository;
    }

    public Optional<Person> findById(long tmdbId) {
        return repository.findById(tmdbId);
    }
}
