package uk.matvey.ekran.web;

import java.util.Map;

import io.javalin.Javalin;
import io.javalin.http.Context;

import uk.matvey.ekran.domain.Department;
import uk.matvey.ekran.domain.NotFoundException;
import uk.matvey.ekran.service.PersonService;
import uk.matvey.ekran.web.viewmodels.PersonPageVm;

public class PersonRoutes extends Routes {

    private final PersonService personService;

    public PersonRoutes(PersonService personService) {
        this.personService = personService;
    }

    public void register(Javalin app) {
        app.get("/persons/{id}", ctx -> person(ctx, null));
        app.get("/persons/{id}/{department}", ctx -> person(ctx, ctx.pathParam("department")));
    }

    private void person(Context ctx, String departmentKey) {
        var id = parseId(ctx);
        var person = personService.findById(id)
            .orElseThrow(() -> new NotFoundException("Person not found: " + id));
        var department = departmentKey == null ? null : parseDepartment(departmentKey);
        ctx.render("person", Map.of("vm", PersonPageVm.of(person, department)));
    }

    private Department parseDepartment(String key) {
        return Department.fromPathKey(key)
            .orElseThrow(() -> new NotFoundException("Unknown department: " + key));
    }
}