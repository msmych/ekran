package uk.matvey.ekran.web.viewmodels;

import java.util.List;

import uk.matvey.ekran.domain.Department;
import uk.matvey.ekran.domain.FilmographyItem;
import uk.matvey.ekran.domain.Person;

public record PersonPageVm(
    long tmdbId,
    String name,
    String knownFor,
    String biography,
    String profileUrl,
    List<DepartmentTabVm> departments,
    List<FilmographySectionVm> sections,
    String currentDepartment,
    String tmdbUrl
) {

    public static PersonPageVm of(Person person, Department current) {
        var sections = filmographySections(person, current);
        return new PersonPageVm(
            person.tmdbId(),
            person.name(),
            person.knownFor() == Department.OTHER ? null : person.knownFor().displayName(),
            person.biography(),
            person.profileUrl() == null ? null : person.profileUrl().toString(),
            List.of(
                new DepartmentTabVm(Department.DIRECTING),
                new DepartmentTabVm(Department.ACTING),
                new DepartmentTabVm(Department.WRITING)
            ),
            sections,
            current == null ? null : current.pathKey(),
            "https://www.themoviedb.org/person/" + person.tmdbId()
        );
    }

    private static List<FilmographySectionVm> filmographySections(Person person, Department current) {
        var filmography = person.filmography();
        return switch (current == null ? Department.OTHER : current) {
            case DIRECTING -> List.of(section("Directing", filmography.directing()));
            case ACTING -> List.of(section("Acting", filmography.acting()));
            case WRITING -> List.of(section("Writing", filmography.writing()));
            case OTHER -> List.of(
                section("Directing", filmography.directing()),
                section("Acting", filmography.acting()),
                section("Writing", filmography.writing())
            ).stream().filter(s -> !s.items().isEmpty()).toList();
        };
    }

    private static FilmographySectionVm section(String label, List<FilmographyItem> items) {
        return new FilmographySectionVm(label, items.stream().map(FilmographyItemVm::of).toList());
    }

    public record DepartmentTabVm(
        String key,
        String label
    ) {

        public DepartmentTabVm(Department department) {
            this(department.pathKey(), department.displayName());
        }
    }
}