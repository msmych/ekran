package uk.matvey.ekran.service;

import uk.matvey.ekran.domain.Person;
import uk.matvey.ekran.repository.PersonRepository;

import java.util.Optional;

public class PersonService {

    private final PersonRepository repository;

    public PersonService(PersonRepository repository) {
        this.repository = repository;
    }

    public Optional<Person> findById(long tmdbId) {
        return repository.findById(tmdbId);
    }
}