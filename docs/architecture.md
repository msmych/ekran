# Architecture

A fast, no-bloat movie discovery web app: open the site, search immediately, results appear
as you type, open a movie, jump to a person's filmography. No social layer, reviews,
recommendations, or ads. Success criterion: empty browser tab → relevant movie/person info
with near-zero waiting or interaction.

## Layering

```
web        Javalin routes, view models, Thymeleaf templates, auth session middleware
  ↓
service    search / movie / person logic            marks / playlists / auth services
  ↓
repository interfaces (domain types)                marks / playlists / auth repos (JDBC)
  ↓
tmdb       TMDB client, DTOs, mapping               db: migrations + Hikari pool
```

Dependencies point strictly downward. **Nothing outside the `tmdb` package may import a TMDB
DTO** — repositories speak domain types (`Movie`, `Person`, `Filmography`, `SearchResult`),
TMDB concepts (`poster_path`, `append_to_response`, `known_for`) are translated at the
boundary, and image URLs are resolved to absolute URLs in the adapter. This is what keeps a
future PostgreSQL-backed store a drop-in replacement. The repository interfaces are
deliberately minimal: `search(query, page, type)`, `findById(tmdbId)`.

## Packages

```
uk.matvey.ekran
├── Main          config load, wiring, Javalin bootstrap (migrations run at startup)
├── config/       AppConfig — pure function of env, fail-fast validation, no secrets in toString
├── web/          *Routes, EkranApp (error handlers), viewmodels/
├── auth/         magic-link tokens (SHA-256, TTL, single-use), sessions, rate limiting
├── marks/        MarksService → PgMarksRepository (marked_movies)
├── playlists/    PlaylistsService → PgPlaylistsRepository (playlists, playlist_movies)
├── email/        EmailService → ResendEmailService
├── service/      SearchService, MovieService (+ Caffeine cache), PersonService
├── domain/       Movie, Person, Filmography, SearchResult, Department, MovieNote, …
├── repository/   interfaces only
├── db/           DbMigrations (V<n>.sql runner), DataSources (Hikari)
└── tmdb/         TmdbClient, dto/, TmdbMapper, repository impls
```

No DI framework — `Main` constructs the graph manually; it is small enough to be explicit.

## Technology

| Concern | Choice |
|---|---|
| JVM / build | Java 25, Gradle `application` plugin |
| Server | Javalin 6.x — no Spring |
| Rendering | Thymeleaf (`javalin-rendering`) |
| Frontend | Server-rendered HTML + vendored HTMX 2.0.4 + `search.js` / `marked.js`; no framework, no bundler |
| JSON | Jackson |
| HTTP out | `java.net.http.HttpClient` (shared, timeouts, HTTP/2) |
| Persistence | PostgreSQL + Flyway-style `V<n>.sql` migrations run at startup |
| Logging | SLF4J + Logback; no secrets, no query strings in logs |
| Tests | JUnit 5, AssertJ, MockWebServer, Testcontainers |

## Performance rules

- One TMDB call per page view (search, movie, person); list surfaces go through the
  `MovieService` Caffeine cache (24 h, 10k entries) — see `tmdb-integration.md`.
- Search fragment responses carry `Cache-Control: no-store`; everything else is
  `no-cache` (always revalidate — see `routes-and-views.md`, static assets).
- No blocking third-party assets (sole exception: the trailers-dialog YouTube iframe,
  fetched only while the dialog is open).
- Pages render and stay navigable with JS failed to load — progressive enhancement baseline.

## Errors

The `tmdb` package translates HTTP failures into typed exceptions:
404 → `NotFoundException`, 401/403 → `TmdbAuthException` (logged without token content),
5xx/timeout/malformed JSON → `TmdbUnavailableException`. Routes map them to friendly
pages/fragments (404/503/500); stack traces and TMDB payloads never reach the browser.