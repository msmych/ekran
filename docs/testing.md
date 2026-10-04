# Testing

Principles: fast tests, no network, no real API key, boring tools (JUnit 5 + AssertJ). External TMDB calls always behind an interface and stubbed.

## Unit tests

### TMDB mapping (`TmdbMapper`) — highest-value target
- Full valid DTO → domain: every field mapped correctly (poster path → absolute URL, release date parse, crew job filtering, cast ordering, top-8 cut).
- Missing/null fields → nulls in domain, no exceptions (absent overview, no poster, null runtime).
- Malformed date, unknown genre set, empty credits.
- `known_for_department` → `Department` enum mapping incl. "Production" → OTHER.
- Search DTOs → `SearchResult` list (movie and person search types), TMDB relevance order preserved.

### Services
- `SearchService`: blank/whitespace query → empty; trimming; oversized query → empty; repository failure → typed error surfaced; success path passes results through with correct VM shape.
- `MovieService`: repository hit → assembled VM (runtime formatting `1h 52m`, writers with jobs, cast links); repository miss → not-found.
- Filmography grouping/sorting/filtering (department pages, year-desc, undated last, bad department → not-found) is covered via `TmdbMapperTest` mapping and `RoutesTest` route assertions — `PersonService` itself is a thin passthrough with no separate unit test.

### Config
- Env parsing, defaults, fail-fast on missing token, timeout validation; masked toString.

## TMDB stub tests (`TmdbClient`)

Use MockWebServer (OkHttp) or an equivalent stub HTTP server — verifies the class with a real HTTP layer but zero external dependency:

- Bearer header actually sent; base URL + query params correct (`query`, `include_adult=false`, `append_to_response`, `language`).
- 200 → parsed DTOs; 404 → `NotFoundException`; 401 → auth exception (no token in exception message); 500/timeout/garbage JSON → `TmdbUnavailableException`.
- Timeouts enforced (short-timeout stub that hangs → typed error, not a hang).

## Route/integration tests

Javalin test utilities (`app.get("/...")` against a started server with stubbed repositories — no HTTP socket needed for most cases):

- `/` → 200, contains autofocus input and `hx-get="/search"` wiring.
- `/search?q=alien` full page → 200, query echoed in input, results present.
- `/search?q=alien` with `HX-Request: true` header → 200 **fragment only** (assert no `<!DOCTYPE`/`<html>` — response is list-only), lightweight body.
- `/search` blank → empty state; movie `404`; person `404`; unknown department `404`.
- `/movies/{id}` → 200 with title, director link, cast links; `/persons/{id}` → 200 with filmography sections and tabs.
- `/list?movie=…` → order-preserving render, param normalization (whitespace, `+`-encoded values, dupes collapse, invalid dropped), 100-movie cap, unavailable movies skipped, empty state.
- TMDB unavailable → 503, friendly body, no stack trace, no TMDB payload leaked.

### Marks & playlists (auth-gated routes — `AuthRoutesTest`)

The test app is built against in-memory `MarksRepository`/`PlaylistsRepository` fakes and a session-cookie–signing helper; requests go through a no-redirects OkHttp client:

- Anonymous page access (`/marked`, `/playlists`, `/playlists/{id}`) → 303 to `/signin`; unauthenticated mutations and `GET /playlists/select` (an HTMX fragment endpoint) → **401** (no redirect — fetch-style callers must see the status).
- Mark sync: bad movie id → 400; toggle → 204; bulk `POST /marked?movie=…` is idempotent and `GET /marked/ids` round-trips the set; the authed page carries the `data-marked-ids` seed span.
- Playlist CRUD lifecycle: create → 303 + `Location`; rename (trimmed); delete → 303; detail page renders cards; dialog fragment single-movie (toggle mode) vs multi-movie (bulk mode, "Add N movies to…").
- Name validation: blank/whitespace and >60 chars → 400; trimming applied on create.
- Ownership scoping: bob hitting alice's playlist id → 404 on detail and every mutation; alice's data untouched afterwards (apostrophes assert the Thymeleaf-escaped form `&#39;`).

### PostgreSQL repositories (Testcontainers, need Docker)

- `PgAuthRepositoryTest`, `PgMarksRepositoryTest`, `PgPlaylistsRepositoryTest` — all Testcontainers-based (Docker required). Auth covers user upsert, token hash/TTL/single-use, sessions, **and migration idempotency**: re-running the migration set must be a no-op; update the expected migration count (`V<n>` latest) when adding one. Marks cover idempotency/per-user isolation; playlists cover CRUD, insert-order positions, cascade delete, ownership isolation, recency ordering.
- `PgMarksRepositoryTest` — mark/unmark, bulk, idempotency, per-user isolation.
- `PgPlaylistsRepositoryTest` — CRUD, insert-order positions (`MAX+1`), cascade delete of memberships, ownership isolation (foreign id → not-found), recency ordering.

### Auth routes

Magic-link flow tests (sign-in → token consumption → session cookie semantics) live in `AuthRoutesTest` alongside the marks/playlist route tests.

## Route/integration tests with real template rendering

At least one end-to-end-per-page test renders through Thymeleaf with stubbed repository data and asserts visible strings (title, person link hrefs) — this catches model-vs-template attribute mismatches that mock-free handler tests miss.

## What tests must never do

- Hit real TMDB (no network in CI).
- Require `TMDB_API_TOKEN` to exist.
- Assert on TMDB DTO shapes outside `tmdb/` tests.

## Optional architecture guard

An ArchUnit rule asserting nothing outside `..tmdb..` depends on `..tmdb.dto..` — cheap insurance for the key architectural rule.