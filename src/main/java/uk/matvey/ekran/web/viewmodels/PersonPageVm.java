package uk.matvey.ekran.web.viewmodels;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

import uk.matvey.ekran.domain.Department;
import uk.matvey.ekran.domain.FilmographyItem;
import uk.matvey.ekran.domain.Person;

public record PersonPageVm(
    long tmdbId,
    String name,
    String knownFor,
    String lifeDates,
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
            lifeDates(person.born(), person.died()),
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
        return new FilmographySectionVm(label, items.stream().map(MovieCardVm::of).toList());
    }

    private static String lifeDates(LocalDate born, LocalDate died) {
        if (born == null && died == null) {
            return null;
        }
        if (died == null) {
            return "Born " + fullDate(born);
        }
        if (born == null) {
            return "Died " + fullDate(died);
        }
        return fullDate(born) + " – " + fullDate(died);
    }

    private static String fullDate(LocalDate date) {
        return date.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + date.getDayOfMonth() + ", " + date.getYear();
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