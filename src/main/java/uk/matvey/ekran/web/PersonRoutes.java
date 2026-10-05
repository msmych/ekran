package uk.matvey.ekran.web;

import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.Map;
import uk.matvey.ekran.domain.Department;
import uk.matvey.ekran.domain.NotFoundException;
import uk.matvey.ekran.domain.Person;
import uk.matvey.ekran.service.PersonService;
import uk.matvey.ekran.web.viewmodels.PersonPageVm;

public class PersonRoutes extends Routes {

    private final PersonService personService;

    public PersonRoutes(PersonService personService) {
        this.personService = personService;
    }

    public void register(Javalin app) {
        app.get("/persons/{id}", ctx -> person(ctx, null));
        app.get("/persons/{id}/all", ctx -> person(ctx, "all"));
        app.get("/persons/{id}/{department}", ctx -> person(ctx, ctx.pathParam("department")));
    }

    private void person(Context ctx, String departmentKey) {
        var id = parseId(ctx);
        var person = personService.findById(id)
            .orElseThrow(() -> new NotFoundException("Person not found: " + id));
        var current = currentDepartment(person, departmentKey);
        // person pages default the header overlay search to people
        ctx.render("person", Map.of("vm", PersonPageVm.of(person, current), "personSearch", true));
    }

    private Department currentDepartment(Person person, String departmentKey) {
        if (departmentKey == null) {
            return defaultDepartment(person);
        }
        if (departmentKey.equalsIgnoreCase("all")) {
            return null;
        }
        return parseDepartment(departmentKey);
    }

    // the bare person page filters by the known-for department; unknown
    // known-for (or an empty section) falls back to the all view
    private Department defaultDepartment(Person person) {
        var knownFor = person.knownFor();
        var filmography = person.filmography();
        return switch (knownFor) {
            case DIRECTING -> filmography.directing().isEmpty() ? null : knownFor;
            case ACTING -> filmography.acting().isEmpty() ? null : knownFor;
            case WRITING -> filmography.writing().isEmpty() ? null : knownFor;
            default -> null;
        };
    }

    private Department parseDepartment(String key) {
        return Department.fromPathKey(key)
            .orElseThrow(() -> new NotFoundException("Unknown department: " + key));
    }
}
