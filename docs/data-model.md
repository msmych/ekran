# Data Model

Domain models carry only what the UI needs — not TMDB's full schema. All types live in
`domain/`; templates bind to view models only, never domain records directly.

## Domain types

```java
enum Department { DIRECTING, ACTING, WRITING, OTHER }   // known_for_department buckets

record SearchResult(long tmdbId, SearchType type /* MOVIE | PERSON */, String title,
                    String originalTitle, Integer year, String subtitle, URI thumbUrl) {}
                    // subtitle: movie = rating "8.2"; person = known-for dept

record Movie(long tmdbId, String title, String originalTitle, LocalDate releaseDate,
             Integer runtimeMinutes, List<String> genres, String overview,
             URI posterUrl, URI backdropUrl,
             List<PersonLink> directors, List<PersonLink> writers, List<PersonLink> cast,
             String originalLanguage, List<MovieVideo> videos) {}

record MovieVideo(String key, String name, String type, boolean official, String language, String publishedAt) {}
record PersonLink(long tmdbId, String name, String role, Department department) {}

record FilmographyItem(long movieTmdbId, String title, LocalDate releaseDate, URI posterUrl) {}
record Filmography(List<FilmographyItem> directing, List<FilmographyItem> writing,
                   List<FilmographyItem> acting) {}   // each sorted release date desc, undated last

record Person(long tmdbId, String name, Department knownFor, LocalDate born, LocalDate died,
              URI profileUrl, Filmography filmography) {}
```

Principles:

- No TMDB field names survive into domain (`poster_path` → `posterUrl`); nulls mean
  "unknown/absent" — the UI omits, never shows placeholders.
- Display formatting happens in view models (runtime `112` → `"1h 52m"`, rating `7.813` →
  `"7.8"`, dates → years, joined genres, computed `href`s), keeping templates dumb and
  formatting testable.
- `MovieCardVm` is the one shared card shape for `/list`, filmography grids, playlists;
  filmography cards carry no duration (the person-credits payload has no `runtime`, and
  fetching it per credit would break the one-call-per-page rule).

## PostgreSQL schema

```sql
users           -- id, email (unique), created_at
login_tokens    -- user_id, token_hash (SHA-256) unique, expires_at, used_at   (single-use, TTL)
sessions        -- user_id, session-hash, expires_at   (cookie value is hashed, not stored)
marked_movies   -- user_id, movie_id, created_at;      PK (user_id, movie_id)
movie_notes     -- user_id, movie_id, note (≤500), updated_at;   PK (user_id, movie_id)
playlists       -- id, user_id, name (≤60, not unique), description (≤1000, nullable),
                -- created_at, updated_at
playlist_movies -- playlist_id, movie_id, note (≤500, nullable), position (MAX+1 on insert), created_at;
                -- PK (playlist_id, movie_id), UNIQUE (playlist_id, position)
```

Rules:

- Movie references are raw TMDB ids (`BIGINT`) — no local movies table; ids are validated
  by format only (`[1-9][0-9]{0,9}`), resolved through TMDB at render time.
- All queries are scoped by `user_id` from the session; a foreign playlist id is a 404,
  never a leak.
- **Notes are detached from marks**: a note needs no mark; unmarking or moving marks into
  playlists never touches notes. Notes are signed-in-only, trimmed on write, blank →
  deleted, private — they never appear in shared `/list?…` URLs.
- Re-adding an existing membership never overwrites its note or position
  (`ON CONFLICT DO NOTHING`); composing a playlist from marks creates bare memberships.
- Adjacent position swaps step through a temporary `MAX+1` position inside a transaction
  (positions are unique per playlist but may have gaps).