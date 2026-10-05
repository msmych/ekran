# ekran

Quick movie search — a fast, no-bloat movie discovery web app. Open the site, start typing,
results appear as you type, open a movie, jump to a director's/actor's/writer's filmography.
TMDB-backed; Javalin (no Spring), server-rendered Thymeleaf + HTMX, no frontend framework.
PostgreSQL holds auth state and — once signed in — marks, notes and playlists (anonymous
marks stay `localStorage`-only). Full docs in [`docs/`](docs/).

## Setup

Requires JDK 25 (Gradle toolchains auto-provision it), Docker (Postgres, Testcontainers,
deployment), a TMDB **v4 read access token** (<https://www.themoviedb.org/settings/api>)
and a Resend API key (<https://resend.com/api-keys>).

```bash
export TMDB_API_TOKEN="your-v4-read-access-token"
export RESEND_API_KEY="re_..."
export DATABASE_URL="postgres://user:pass@localhost:5432/ekran"   # optional in dev

docker compose -f compose.dev.yml up -d db   # PostgreSQL on localhost:5433 (ekran:ekran/ekran)
./gradlew run                                # loads .env from the repo root
./gradlew test                               # Pg*RepositoryTest cases need Docker
```

Optional configuration (env vars): `PORT` (7070), `TMDB_BASE_URL`, `TMDB_IMAGE_BASE_URL`,
`TMDB_*_TIMEOUT_MS`, `RESEND_BASE_URL`, `AUTH_FROM_EMAIL`, `PUBLIC_BASE_URL` (https://
also enables the `Secure` cookie), `AUTH_TOKEN_TTL_MINUTES` (15), `AUTH_SESSION_DAYS`
(30). Secrets are never committed or logged; startup fails fast if a required one is
missing. Details: [`docs/configuration-and-ops.md`](docs/configuration-and-ops.md).

## URLs

| Route | Purpose |
|---|---|
| `/` | Search-first homepage; `/?q=…&type=person` deep-links a people search |
| `/search?q={query}&type={movie\|person}` | Search — results-only fragment for HTMX, full page otherwise |
| `/movies/{tmdbId}` | Movie detail: clickable crew/cast, trailers dialog, OG previews, mark toggle, note |
| `/persons/{tmdbId}` | Person filmography — defaults to the known-for department |
| `/persons/{tmdbId}/all` · `/{directing\|acting\|writing}` | Full filmography / one department |
| `/list?movie={id}…&name={title}` | Shared list rendered from the URL — share chip + QR, print, inline rename |
| `/marked` · `/notes` | Signed-in: your marks / your noted movies (rows view) |
| `/playlists` · `/playlists/{id}` | Signed-in playlists — index rows with description + Share/Delete quick actions |
| `/playlists/select?movie={id}…` | HTMX fragment for the Add-to-playlist dialog |
| `/signin` · `/auth/link?token=…` · `/account` | Passwordless email sign-in, account page |
| `/about` · `/health` · `/healthz` | About, readiness (DB ping), liveness |

Mark-sync endpoints (authed): `POST`/`DELETE /marked/{movieId}`, bulk
`POST`/`DELETE /marked?movie=…`, `GET /marked/ids`, `POST /marked/migrate`. Note
fragments: `/movies/{id}/note[/edit]`, `/playlists/{id}/movies/{movieId}/note[/edit]`.

## Authentication

Email magic link only — no passwords; a first sign-in creates the account. `POST
/signin` → one-time SHA-256-hashed token (15 min TTL, rate-limited per email and IP) →
email via Resend → `GET /auth/link?token=…` consumes it and sets an opaque server-side
session (`__Host-`-prefixed HttpOnly cookie in production). Post-login redirects are
same-site paths only; the "check your email" response never reveals whether an email has
an account. Search, marking and sharing work without an account; signing in adds
server-side marks, notes and playlists. Local marks migrate to the server on first
authenticated load (idempotent — magic links opened elsewhere are safe).

## Features in brief

- **Marking** — bookmark toggle on movie pages, cards and search results (`m` on the
  movie page). Anonymous: `localStorage`; signed-in: PostgreSQL. Marks are the quick
  inbox — a bare set of ids.
- **Notes** — per-user, ≤500 chars, detached from marks (unmarking or composing
  playlists never touches them), signed-in only, never in shared URLs. `n` on the movie
  page edits its note.
- **Playlists** — ordered, ownership-scoped (a foreign playlist is a 404), optional
  description and per-membership notes. **Move all to playlist** from `/marked` is a
  move (marks clear); from shared lists it's a copy. A playlist shares as a plain
  `/list?movie=…&name=…` URL — public, no account needed.
- **Search everywhere** — live on the homepage, overlay panel in the header on every
  other page; Movies/People toggle on both (person pages default to people); `/` and
  Cmd/Ctrl+K focus it, ↑/↓ + Enter navigate results.

## Architecture

```
web       Javalin routes, view models, Thymeleaf templates (+ auth session middleware)
  ↓
service   search / movie / person application logic      marks / playlists / auth services
  ↓
repository  interfaces for movie/person/search retrieval        marks / playlists / auth repos (JDBC)
  ↓
tmdb      TMDB client, API DTOs, mapping into domain models    db: migrations + Hikari pool
```

Key rule: **TMDB DTOs never leave the `tmdb` package** — everything above the repositories
speaks small domain models, which keeps a future local store a drop-in replacement. One
TMDB call per page view (`append_to_response`); list surfaces go through a Caffeine cache
in `MovieService` (24 h TTL). Client JS is vendored `htmx.min.js` (2.0.4) plus two small
first-party files (`search.js`, `marked.js`) — no CDN, no bundler. See
[`docs/architecture.md`](docs/architecture.md) and [`docs/`](docs/) for the rest.

## Deployment

Single-VPS production: nginx (TLS, Let's Encrypt) → app container → PostgreSQL, on the
internal compose network. CI builds the image and smoke-tests it; pushes to `main`
deploy the immutable `:<commit-sha>` GHCR image over SSH, health-check it, and roll back
to the previous tag on failure. Server-side setup and TLS details:
[`docs/configuration-and-ops.md`](docs/configuration-and-ops.md).

## TMDB attribution

Ekran uses the TMDB API but is not endorsed or certified by TMDB. Every page carries the
attribution footer with a link to [themoviedb.org](https://www.themoviedb.org/) and the
vendored logo, per TMDB's attribution terms.

## Roadmap

- TV shows (search toggle + `/tv/{id}`; needs a `media_type` discriminator in the marks/
  playlists/notes tables — TMDB movie and TV ids collide)
- PostgreSQL-backed local knowledge base with TMDB refresh
- UI polish