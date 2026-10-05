# TMDB Integration

Everything TMDB-specific lives in the `tmdb` package; nothing outside it knows TMDB
exists. When an endpoint or response shape is uncertain, consult current TMDB docs —
API-specific assumptions stay inside the adapter.

## Auth and endpoints

- v4 Bearer token via `Authorization` header; env `TMDB_API_TOKEN` (never committed,
  never logged; fail-fast at startup when missing).
- **One TMDB call per page view**, kept at one via `append_to_response`:

| Purpose | Endpoint |
|---|---|
| Movie search | `GET /search/movie?query={q}&include_adult=false&page=1` |
| Person search | `GET /search/person?query={q}&include_adult=false&page=1` |
| Movie detail | `GET /movie/{id}?append_to_response=credits,videos&language=en-US` |
| Person + credits | `GET /person/{id}?append_to_response=movie_credits&language=en-US` |

- `language=en-US` fixed. HTTP via one shared `java.net.http.HttpClient`: connect timeout
  2 s, request timeout 3 s (search) / 5 s (detail), no retries — failures surface as 503.
- Non-2xx → typed errors: 404 `NotFoundException`, 401/403 `TmdbAuthException` (logged
  without token), 5xx/timeout/garbage `TmdbUnavailableException`.

## Caching

TMDB has no batch-details endpoint, so list surfaces would pay one detail call per card
(up to 100). `MovieService` wraps the repository with a Caffeine cache keyed by TMDB id
(24 h expiry — movie metadata is effectively immutable; 10 000 entries). A missing movie
still surfaces as `NotFoundException` → 404.

## Images

- Relative paths resolved to absolute URLs at mapping time:
  `{TMDB_IMAGE_BASE_URL}/{size}{path}` (base is config).
- Sizes: `w92` search thumbs, `w185` cards, `w342` movie poster, `h632` person profile,
  `w780` backdrop (OG image). Null paths → `null` in domain → CSS placeholder.

## DTOs and mapping (`tmdb.dto`, `TmdbMapper`)

- Jackson DTOs, `@JsonIgnoreProperties(ignoreUnknown = true)` — TMDB adds fields; the
  adapter must not break.
- All TMDB knowledge ends in `TmdbMapper` (pure functions, no IO — the core unit-test
  target): date parsing (`"1957-10-25"` → `LocalDate`, unparsable → null), crew filters
  (`job == "Director"` → directors, `department == "Writing"` → writers, cast by `order`
  top 8), `known_for_department` → `Department`, filmography by crew department + cast,
  videos filtered to YouTube-only (`official` null → false).
- Search results: movie items → title, year, rating subtitle; person items → name,
  known-for subtitle. TMDB relevance order preserved.