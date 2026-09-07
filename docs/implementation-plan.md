# Implementation Plan

Ordered sequence; each step ends in a runnable/testable state. Smallest coherent version first; boring explicit code; no speculative abstraction.

## Project layout

```
ekran/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/ + gradlew
├── README.md
├── docs/                        ← these specs
├── quick_movie_search_agent_brief.docx
└── src/
    ├── main/
    │   ├── java/uk/matvey/ekran/
    │   │   ├── Main.java
    │   │   ├── config/AppConfig.java
    │   │   ├── web/        (routes, viewmodels, errors)
    │   │   ├── service/
    │   │   ├── domain/
    │   │   ├── repository/
    │   │   └── tmdb/       (client, dto, mapper, repo impls)
    │   └── resources/
    │       ├── templates/  (Thymeleaf: head, searchbar, home, results, movie, person, error, error-fragment, footer)
    │       ├── static/     (css/app.css, js/htmx.min.js, js/search.js, img/tmdb-logo.svg)
    │       └── logback.xml
    └── test/java/uk/matvey/ekran/…
```

Dependencies (all locked decisions): `javalin`, `javalin-rendering` (Thymeleaf module), `jackson-databind`, `logback-classic` (SLF4J binding included); test: `junit-jupiter`, `assertj`, `mockwebserver`. Java 25 toolchain.

## Steps

1. **Scaffold.** Gradle init, deps, `Main` starting Javalin serving a static "hello" `/` page; `AppConfig` reading env with fail-fast token check + `PORT`; Logback config. Done when `./gradlew run` serves the page.
2. **TMDB client + DTOs + mapper.** `TmdbClient` (HttpClient, Bearer auth, timeouts, typed errors), DTOs for the three responses (movie search, movie detail+credits, person+movie_credits), `TmdbMapper` pure functions. Unit tests with fixture JSON + MockWebServer tests. Done when mapping suite is green.
3. **Repositories + services.** `Search/Movie/Person` repository interfaces, TMDB implementations, the three services (search, movie assembly, filmography grouping/sorting/department filter). Unit tests with stub repositories. Done when service tests green, no route wired yet.
4. **Homepage + search.** Thymeleaf templates (`index`, `search-results` fragment), `/` and `/search` dual-mode route (fragment vs full page), HTMX input wiring, vendored `htmx.min.js`, minimal CSS. Done when typing shows live movie results in a browser.
5. **Movie page.** `/movies/{id}` route, `movie.html` template, view model formatting (runtime, rating, genres, links). Done when a movie page renders with clickable crew/cast.
6. **Person pages.** `/persons/{id}` (+`/{department}`), filmography sections, department tabs. Done when navigation movie → person → movie round-trips and unknown department 404s.
7. **Hardening.** Error pages (404/503/500), fragment-mode errors, request logging via SLF4J, `Cache-Control: no-store` on fragments, `GET /healthz`.
8. **Testing completion.** Route/integration suite, rendered-template tests, architecture guard (ArchUnit, optional), full `./gradlew check` green.
9. **Docs.** README: setup, `TMDB_API_TOKEN`, run/test commands, architecture summary, link to `docs/`; vendor the exact HTMX version into `search-interaction.md`; update `overview.md` definition-of-done checkboxes.

Out of scope for this pass (follow-up steps): persons search, keyboard result navigation, UI polish.

## Review gates

- After step 3: verify no TMDB DTO escapes `tmdb/` (the key architectural rule).
- After step 6: full user journey walkthrough (blank tab → search → open movie → person → filmography tab → movie) on desktop and narrow viewport.
- After step 8: check DoD list in `overview.md`.