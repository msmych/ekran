# Architecture

## Layering

```
Browser (HTML + HTMX)
  ↓ HTTP
web          — Javalin routes/controllers, request handling, view models, error pages
  ↓
service      — search / movie / person application logic
  ↓
repository   — interfaces for movie/person/credit retrieval
  ↓
tmdb         — TMDB client, API DTOs, mapping into domain models
               (MVP)  →  later: PostgreSQL + TMDB refresh (Step 2)
```

Dependencies point strictly downward. **Nothing outside the `tmdb` package may import a TMDB DTO.**

## Package boundaries

Root package: `uk.matvey.ekran`.

```
uk.matvey.ekran
├── Main                    — entry point: config load, wiring, Javalin bootstrap
├── config/                 — AppConfig (env parsing, validation)
├── web/                    — routes, per-request handling, view models
│   ├── SearchRoutes        — / and /search
│   ├── MovieRoutes         — /movies/{id}
│   ├── PersonRoutes        — /persons/{id}, /persons/{id}/{department}
│   ├── viewmodels/         — SearchResultsVm, MovieDetailVm, PersonVm, FilmographyVm, …
│   └── errors/             — exception handlers → friendly error fragments/pages
├── service/
│   ├── SearchService       — query validation, movie search
│   ├── MovieService        — movie detail assembly
│   └── PersonService       — person + filmography assembly, department grouping
├── domain/                 — application-level models
│   ├── Movie, Person, Credit, Department, Filmography, SearchResult
├── repository/             — interfaces only
│   ├── MovieRepository
│   ├── PersonRepository
│   └── SearchRepository
└── tmdb/
    ├── TmdbClient          — HTTP, auth, timeouts, retries-as-configured
    ├── TmdbMovieRepository — implements MovieRepository
    ├── TmdbPersonRepository— implements PersonRepository
    ├── TmdbSearchRepository— implements SearchRepository
    ├── dto/                — Jackson DTOs mirroring TMDB responses (package-private reach)
    └── TmdbMapper          — DTO → domain mapping
```

## Key architectural rule

> **Do not leak TMDB DTOs beyond the TMDB adapter.**

The application depends on its own small domain/view models. This prevents TMDB's API structure from becoming the application's architecture and makes PostgreSQL or another provider replaceable later (see `data-model.md`).

Consequences:

- `tmdb.dto` types are not referenced by `web`, `service`, or `domain` — enforced by code review and optionally by a ArchUnit rule (`noClasses().that().resideOutsideOfPackage("..tmdb..").should().dependOnClassesThat().resideInAPackage("..tmdb.dto..")`).
- Repository interfaces speak domain types (`Movie`, `Person`, `Filmography`, `SearchResult`).
- TMDB-specific concepts (e.g. `known_for`, `append_to_response`, `poster_path`) are translated at the boundary: `poster_path` becomes a resolved absolute image URL in the domain object, so the domain layer never knows TMDB image conventions.

## Repository interfaces

Deliberately minimal — the smallest surface the UI needs today:

```java
public interface SearchRepository {
    SearchResultPage search(String query, int page);   // movies only in Step 1
}

public interface MovieRepository {
    Optional<Movie> findById(long tmdbId);
}

public interface PersonRepository {
    Optional<Person> findById(long tmdbId);              // includes credits for filmography
}
```

`SearchResultPage` carries domain `SearchResult` items (see `data-model.md`). In Step 1 the search is **movies only** — one TMDB call per search. When persons search is added as the follow-up step, options are blending in the adapter (two parallel TMDB calls merged deterministically) or a later PostgreSQL-backed merge — `SearchService` and the routes should not need to change shape.

## Wiring

No DI framework. `Main` constructs the graph manually — it is small enough to be explicit:

```
AppConfig ← env
HttpClient (shared, time-limits configured)
TmdbClient(config, httpClient)
Tmdb{Search,Movie,Person}Repository(tmdbClient)
{Search,Movie,Person}Service(repositories)
Javalin with routes(services, thymeleaf)
```

## Technology choices

| Concern | Choice | Reason |
|---|---|---|
| JVM | Java 25 (LTS) | Locked decision |
| HTTP server | Javalin 6.x | Lightweight, no Spring, simple routing, good static-file support |
| Rendering | Thymeleaf 3.x via `javalin-rendering` | HTML-natural templates, mature, no client framework |
| JSON | Jackson (databind + jdk8/params-names as needed) | Required for TMDB responses |
| Logging | SLF4J + Logback | Locked decision; simple console/file config, no ceremony |
| Outbound HTTP | `java.net.http.HttpClient` | Zero extra dependency, supports connect timeout, connection reuse, and per-request timeouts |
| Frontend behavior | HTMX (vendored `htmx.min.js` in `static/`) + one ~40-line first-party `search.js` (hotkeys, Escape, overlay close) | No blocking third-party assets; keyboard result nav deferred to a follow-up step |
| Build | Gradle, `application` plugin | Locked decision |
| Tests | JUnit 5, AssertJ, MockWebServer (OkHttp) or similar stub HTTP server | Mockable TMDB, no real API key |

## Performance posture

- Homepage renders immediately; search input ready for typing (autofocus, zero JS required to show the page).
- No blocking third-party assets, advertising, analytics.
- Search response returns only the HTML fragment needed for the result list.
- Avoid duplicate TMDB calls within a single request (e.g. movie detail fetches movie + credits in one `append_to_response` call; person page fetches person + credits in one call).
- Outbound HTTP with connection reuse and explicit timeouts.
- Stale search responses must not replace current results (see `search-interaction.md`).
- Design so local caching/storage (Step 2) can later reduce external latency — the repository boundary is the seam.

## Error handling strategy

- TMDB errors (5xx, timeouts, malformed JSON) are caught in the `tmdb` package and translated into application-level exceptions (`TmdbUnavailableException`, `NotFoundException`).
- Routes map those to friendly pages/fragments (HTTP 500/503/404) — TMDB/internal stack traces never reach the browser.
- See `configuration-and-ops.md` for logging rules (no secrets in logs).