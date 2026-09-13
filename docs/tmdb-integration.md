# TMDB Integration

Everything TMDB-specific lives in the `tmdb` package. Nothing outside it knows TMDB exists.

## Authentication

- **v4 Bearer token**, passed as `Authorization: Bearer <token>` header (no `api_key` query param).
- Env var: `TMDB_API_TOKEN`. Never committed, never logged. App fails fast at startup with a clear message if unset.
- TMDB account/app settings stay out of the codebase entirely.

## Base endpoints used (TMDB API v3 REST surface)

| Purpose | Endpoint | Notes |
|---|---|---|
| Movie search | `GET /3/search/movie?query={q}&include_adult=false&page=1` | Only search endpoint in Step 1 |
| Movie detail + credits | `GET /3/movie/{id}?append_to_response=credits,videos&language=en-US` | **single** call per movie page |
| Person + film credits | `GET /3/person/{id}?append_to_response=movie_credits&language=en-US` | **single** call per person page |

- One request per page view, enforced: movie page = 1 TMDB call, person page = 1 TMDB call, search = 1 TMDB call. No duplicate TMDB calls within a single request.
- Persons search (`/3/search/person`) joins in the follow-up step — nothing in this adapter needs to change except adding the endpoint and its mapping.
- `language=en-US` fixed for MVP (no localization work).
- `append_to_response` is the tool that keeps call counts at 1 — this is a TMDB-adapter-internal detail; the repository interface stays `findById`.

## Images

- TMDB returns relative paths (`poster_path: "/abc.jpg"`); the adapter resolves them to absolute URLs at mapping time: `https://image.tmdb.org/t/p/{size}{path}`.
- Sizes used: `w92` (search thumbs), `w185` (filmography/list cards), `w342` (movie poster), `h632` (person profile), `w780` (backdrop). Base image URL is config (`TMDB_IMAGE_BASE_URL`, default `https://image.tmdb.org.tld/t/p` style default baked in; see `configuration-and-ops.md`).
- Null/absent paths map to `null` in domain; templates render a CSS placeholder.

## HTTP client

`java.net.http.HttpClient`, one shared instance:

- Connect timeout: configurable, default 2 s.
- Request timeout (per call): default 3 s for search, 5 s for detail pages (TMDB + credits responses are larger).
- HTTP/2 with connection reuse by default.
- No retries in MVP (beyond what a single manual retry could fix, retries add tail latency; error is surfaced as 503). Revisit in Step 2 with the local store.
- Treat non-2xx as typed errors:
  - 404 → `NotFoundException` (domain object genuinely absent)
  - 401/403 → `TmdbAuthException` (config problem — logged with **no token content**, surfaced as 503)
  - 5xx / timeout / IO / malformed JSON → `TmdbUnavailableException`

## DTO layer (`tmdb.dto`)

Jackson-mapped, package-reachable only from within `tmdb`:

- `MovieSearchResponse { page, results: List<MovieSearchItem> }`
- `MovieDetailResponse` (title, original_title, release_date, runtime, genres[], vote_average, overview, poster_path, backdrop_path, original_language, credits: `CreditsResponse{crew[], cast[]}`, videos: `VideosResponse{results[]}`)
- `VideoItem` (`key, name, site, type, official, iso_639_1, published_at`) — the video DTO backing trailers
- `PersonDetailResponse` (name, known_for_department, biography, profile_path, movie_credits: `PersonMovieCreditsResponse{crew[], cast[]}`)
- Crew/cast item DTOs: `id, name, job, character, order, department, release_date, title`
- DTOs tolerate missing fields (`@JsonIgnoreProperties(ignoreUnknown = true)`) — TMDB adds fields; the adapter must not break on unknown keys.

## Mapping (`TmdbMapper`) — DTO → domain

All TMDB knowledge ends here:

- `poster_path` → absolute URL (or null).
- `release_date` "1957-10-25" → domain `LocalDate` / display year; unparsable or missing → null (UI omits).
- `vote_average` → double, rounded for display in view models only.
- `genre[]` → `List<String>` names (genre IDs are dropped — MVP needs names only).
- Movie `credits.crew` where `job == "Director"` → directors; where `department == "Writing"` (job shown: Screenplay/Writer/Story) → writers.
- Movie `credits.cast` ordered by `order` → principal cast (top 8).
- Person `movie_credits.crew` where `department == "Directing"` → directing filmography (job "Director"); `department == "Writing"` → writing filmography; `movie_credits.cast` → acting filmography (character).
- Person detail `known_for_department` → `Department` enum (Directing/Acting/Writing/Other).
- Search result mapping: each movie search item → `SearchResult` (type `MOVIE`, id, title, subtitle = vote average formatted "8.2" — search responses carry no genre names, year from release_date, thumbUrl from poster_path), preserving TMDB relevance order. Persons search mapping joins in the follow-up step.
- `videos.results` → `List<MovieVideo>` (key, name, type, official, language, publishedAt): **YouTube-only** (`site == "YouTube"`, case-insensitive; Vimeo/other sites are dropped), `official` null → false, `iso_639_1` → `language`. Hero-pick ranking lives in the view model, not here — the adapter maps everything it is given.

Mapping is pure functions with no HTTP/IO — trivially unit-testable (the core test target in `testing.md`).

## TMDB failure containment

- Search: the single movie-search call failing → the request fails with a friendly 503 (no partial-result machinery needed while search is movies-only; revisit when persons search joins).
- Detail pages: any failure → 503 friendly page; TMDB's error body never rendered or logged verbatim in full.
- Timeouts enforced at the HTTP layer; total request wall-time bounded by connect + request timeouts.

## Uncertainty rule

When an endpoint or response shape is uncertain, consult current TMDB API documentation rather than guessing. Keep API-specific assumptions (field spellings, `append_to_response`, image path conventions) **inside the adapter** — if TMDB changes, only `tmdb/` changes.