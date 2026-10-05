# Testing

Principles: fast tests, no network, no real API key, boring tools (JUnit 5 + AssertJ).
All external TMDB calls are behind interfaces and stubbed.

**Never:** hit real TMDB, require `TMDB_API_TOKEN` to exist, or assert on TMDB DTO shapes
outside `tmdb/` tests.

- **`TmdbMapperTest`** (highest-value unit target) — full DTO → domain mapping, missing
  fields → nulls, malformed dates, department/crew/cast filters and ordering, filmography
  release-date sorting, search mappings for both types, video filtering.
- **`TmdbClientTest`** — MockWebServer: Bearer header, params (`include_adult`,
  `append_to_response`, `language`), 200/404/401/5xx/timeout/garbage → typed exceptions,
  no token in messages.
- **Services** — `SearchService` query validation/trim/cap + failure surfacing;
  `MovieService` assembly and not-found; `AppConfig` env parsing and fail-fast.
- **`RoutesTest`** — Javalin test tools against stubbed repositories; renders through
  Thymeleaf and asserts visible strings (catches model-vs-template mismatches): home and
  search (fragment vs full page, both search types, the toggle wiring), movie page
  (links, OG tags), person pages (known-for default, `/all`, department 404s, overlay
  defaulting to people), `/list` param normalization and the 100-movie cap, 404/503
  friendly errors.
- **`AuthRoutesTest`** — in-memory repository fakes + session-cookie signing helper +
  no-redirects OkHttp: auth gating (303 for pages, 401 for fragment endpoints), mark
  sync (idempotent bulk, ids round-trip), playlist CRUD + validation + ownership scoping
  (foreign id → 404), the pick dialog fragments, the playlist index quick actions,
  magic-link flow (token consumption, session semantics).
- **`Pg*RepositoryTest`** (Testcontainers, Docker required) — auth (token hash/TTL/
  single-use, sessions, **migration idempotency**: re-running the migration set is a
  no-op; update the expected migration count when adding one), marks (idempotency,
  per-user isolation), playlists (CRUD, insert-order positions, cascade delete, recency
  ordering, index rows with description + ordered movie ids).