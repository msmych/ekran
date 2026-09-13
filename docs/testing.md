# Testing

Principles: fast tests, no network, no real API key, boring tools (JUnit 5 + AssertJ). External TMDB calls always behind an interface and stubbed.

## Unit tests

### TMDB mapping (`TmdbMapper`) — highest-value target
- Full valid DTO → domain: every field mapped correctly (poster path → absolute URL, release date parse, crew job filtering, cast ordering, top-8 cut).
- Missing/null fields → nulls in domain, no exceptions (absent overview, no poster, null runtime).
- Malformed date, unknown genre set, empty credits.
- `known_for_department` → `Department` enum mapping incl. "Production" → OTHER.
- Search DTOs → `SearchResult` list (`MOVIE` type only in Step 1), TMDB relevance order preserved.

### Services
- `SearchService`: blank/whitespace query → empty; trimming; oversized query → empty; repository failure → typed error surfaced; success path passes results through with correct VM shape.
- `MovieService`: repository hit → assembled VM (runtime formatting `1h 52m`, rating rounding, writers with jobs, cast links); repository miss → not-found.
- `PersonService`: filmography grouping by department, year-desc sorting, undated last; department filtering for `/persons/{id}/{department}`; bad department → not-found.

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

## Route/integration tests with real template rendering

At least one end-to-end-per-page test renders through Thymeleaf with stubbed repository data and asserts visible strings (title, person link hrefs) — this catches model-vs-template attribute mismatches that mock-free handler tests miss.

## What tests must never do

- Hit real TMDB (no network in CI).
- Require `TMDB_API_TOKEN` to exist.
- Assert on TMDB DTO shapes outside `tmdb/` tests.

## Optional architecture guard

An ArchUnit rule asserting nothing outside `..tmdb..` depends on `..tmdb.dto..` — cheap insurance for the key architectural rule.