# Data Model

Domain models contain only what the UI needs today — not TMDB's full schema. All types live in `domain/`.

## domain types (MVP)

```java
enum Department { DIRECTING, ACTING, WRITING, OTHER }   // known_for_department buckets

record SearchResult(
    long tmdbId,
    SearchType type,            // MOVIE | PERSON — search type from the home toggle / `type` param
    String title,               // movie title or person name
    String originalTitle,       // raw from TMDB; display layer hides it when same as title
    Integer year,               // release year, nullable
    String subtitle,            // movie: rating "8.2"; person: known-for dept (later step)
    URI thumbUrl                // absolute, nullable
) {}

record SearchResultPage(List<SearchResult> results) {}

record PersonLink(long tmdbId, String name, String role, Department department) {}

record Movie(
    long tmdbId,
    String title,
    String originalTitle,       // null when same as title
    LocalDate releaseDate,      // nullable
    Integer runtimeMinutes,     // nullable
    List<String> genres,
    Double rating,              // vote average, nullable
    String overview,
    URI posterUrl,              // nullable
    URI backdropUrl,            // nullable
    List<PersonLink> directors,
    List<PersonLink> writers,
    List<PersonLink> cast,      // principal, billing order
    String originalLanguage,    // drives video ranking
    List<MovieVideo> videos     // YouTube-only, filtered in the adapter
) {}

record MovieVideo(String key, String name, String type, boolean official, String language, String publishedAt) {}

record FilmographyItem(long movieTmdbId, String title, Integer year, URI posterUrl) {}

record Filmography(
    List<FilmographyItem> directing,   // sorted year desc
    List<FilmographyItem> writing,
    List<FilmographyItem> acting
) {}

record Person(
    long tmdbId,
    String name,
    Department knownFor,
    LocalDate born,             // nullable — birthday
    LocalDate died,            // nullable — deathday
    URI profileUrl,            // nullable
    Filmography filmography
) {}
```

Principles:

- No TMDB field names survive into domain (`poster_path` → `posterUrl`, `vote_average` → `rating`).
- No IDs other than TMDB ID in MVP (Step 2 adds local canonical IDs; keeping domain lean now makes that a repository concern).
- Nulls mean "unknown/absent" — UI omits, never shows placeholders like "N/A".

## View models (`web/viewmodels`)

Derived from domain inside route handlers / small assemblers; preformatted for display:

- runtime `112` → `"1h 52m"` (omit if null)
- rating `7.813` → `"7.8"`
- date → year only where the view shows a year
- joined genre list, absolute URLs already resolved
- `href` values (`/movies/{id}`, `/persons/{id}/{department}`) computed once

Templates bind to view models only, never domain records directly, so display formatting stays in code (testable) and templates stay dumb.

## Step 2: PostgreSQL / local knowledge base (design target, not MVP work)

**Landed first (auth + user data):** `users`, `login_tokens` (SHA-256-hashed, TTL, single-use), `sessions` (the session id column stores a hash of the cookie value — see `configuration-and-ops.md`), plus the marked/playlist tables:

```sql
marked_movies   -- user_id, movie_id (tmdb), note (≤500, nullable), created_at;
                  PK (user_id, movie_id)
playlists       -- id, user_id, name (≤60, not unique), description (≤1000, nullable),
                  created_at, updated_at; indexed by user_id
playlist_movies -- playlist_id, movie_id (tmdb), note (≤500, nullable),
                  position (MAX+1 on insert), created_at;
                  PK (playlist_id, movie_id), UNIQUE (playlist_id, position)
```

Movie references are TMDB IDs stored directly (`BIGINT`) — no local movies table yet, so marks/playlists are verified only by format (`[1-9][0-9]{0,9}`), resolved through TMDB at render time. All queries are scoped by `user_id` from the session; a foreign playlist id is a 404, not a leak.

Notes (mark notes, playlist descriptions, per-membership notes) are trimmed on write, blank → NULL, and are private per-user data — they never appear in shared `/list?…` URLs (the share URL carries only movie ids + the list name). Re-adding an existing membership never overwrites its note or position (`ON CONFLICT (playlist_id, movie_id) DO NOTHING`). Composing a playlist from marks copies each card's mark note into the new membership; the marks themselves (and their notes) stay put.

The repository boundary above is designed so the knowledge base lands later without touching `service/` or `web/`. Intended schema:

```sql
movies          -- canonical movie metadata + tmdb_id (unique)
people          -- canonical person + tmdb_id (unique)
movie_credits   -- movie_id, person_id, department, job, character, ordering
-- optional fetch metadata
movies.fetched_at / movies.updated_at, people.fetched_at / people.updated_at
```

Evolution:

- Repositories swap from TMDB-backed to PostgreSQL-backed (with TMDB refresh/enrichment for missing or stale rows).
- Local search (full-text / trigram) eventually answers common queries without a TMDB round trip; TMDB remains upstream truth for unknown/stale metadata.
- Domain models may grow a local `id` alongside `tmdbId` — one field, no structural change.

Non-goal reminder: do **not** implement any of this in step one. The only MVP obligation is the clean repository boundary specified in `architecture.md`.