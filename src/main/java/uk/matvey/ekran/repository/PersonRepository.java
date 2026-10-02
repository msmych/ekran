package uk.matvey.ekran.repository;

import java.util.Optional;
import uk.matvey.ekran.domain.Person;

public interface PersonRepository {

    Optional<Person> findById(long tmdbId);
}
