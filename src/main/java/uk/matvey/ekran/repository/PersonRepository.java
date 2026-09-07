package uk.matvey.ekran.repository;

import uk.matvey.ekran.domain.Person;

import java.util.Optional;

public interface PersonRepository {

    Optional<Person> findById(long tmdbId);
}