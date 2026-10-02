# ekran

Quick movie search — a fast, no-bloat movie discovery web app. Open the site, start typing, results
appear as you type, open a movie, jump to a director's/actor's/writer's filmography.

Step 1 is TMDB-backed: Javalin (no Spring), server-rendered Thymeleaf + HTMX, no frontend framework.
Persistence is PostgreSQL: auth state (users, magic-link tokens, sessions), and — once signed in —
marks and playlists (anonymous marks stay `localStorage`-only). See [`docs/`](docs/) for the
full spec; the deployment plan is documented in the Deployment section below and in [`docs/configuration-and-ops.md`](docs/configuration-and-ops.md).

## Requirements

- JDK 25 (any JDK 25 works; Gradle auto-provisions it via toolchains if missing)
- Docker — for PostgreSQL (dev compose, integration tests via Testcontainers) and deployment
- A TMDB API **v4 read access token** — <https://www.themoviedb.org/settings/api>
- A Resend API key (magic-link emails) — <https://resend.com/api-keys>

## Setup

```bash
export TMDB_API_TOKEN="your-v4-read-access-token"
export RESEND_API_KEY="re_..."
export DATABASE_URL="postgres://user:pass@localhost:5432/ekran"
```

Optional configuration (all via environment variables):

| Variable | Default |
|---|---|
| `PORT` | `7070` |
| `TMDB_BASE_URL` | `https://api.themoviedb.org/3` |
| `TMDB_IMAGE_BASE_URL` | `https://image.tmdb.org/t/p` |
| `TMDB_CONNECT_TIMEOUT_MS` | `2000` |
| `TMDB_SEARCH_TIMEOUT_MS` | `3000` |
| `TMDB_DETAIL_TIMEOUT_MS` | `5000` |
| `RESEND_BASE_URL` | `https://api.resend.com` |
| `AUTH_FROM_EMAIL` | `ekran <no-reply@ekran.uk>` |
| `PUBLIC_BASE_URL` | `https://ekran.uk` — base for magic-link URLs; `https://` also turns on the `Secure` session cookie |
| `AUTH_TOKEN_TTL_MINUTES` | `15` |
| `AUTH_SESSION_DAYS` | `30` |

The secrets are never committed or logged; the app fails fast at startup if any of
`TMDB_API_TOKEN`, `DATABASE_URL` or `RESEND_API_KEY` is missing.

## Run

```bash
docker compose -f compose.dev.yml up -d db   # PostgreSQL on localhost:5433 (ekran:ekran/ekran)
./gradlew run
```

Then open <http://localhost:7070>. (Or run the whole stack: `docker compose -f compose.dev.yml up`.)

`./gradlew run` loads `.env` from the repo root into the process environment (TMDB_API_TOKEN,
RESEND_API_KEY, …). `DATABASE_URL` defaults to `postgres://ekran:ekran@localhost:5433/ekran`
(the dev compose Postgres) and `PUBLIC_BASE_URL` to `http://localhost:7070`, so local magic
links point at localhost.

## Test

```bash
./gradlew test      # unit + route/integration tests; the Pg*RepositoryTest cases need Docker (Testcontainers)
./gradlew check
```

## Authentication

Email magic link only — no passwords, no separate registration (a first sign-in creates
the account). The sign-in page says it upfront: search, marking and sharing work without
an account; signing in adds playlists and server-side marks. `Sign in` in the header is
deliberately small (header layout: `ekran · [Marked, Sign in/Account] · search`, clustered
right). After signing in the header shows an `Account` popup with the email, `Marked · N`
(hidden at zero marks), `Playlists · N` and `Sign out`; marks move to
PostgreSQL (local marks are migrated on first load and only cleared after the server
confirms; the merge is idempotent, so magic links opened elsewhere are safe).

```
POST /signin            email → one-time token (SHA-256-hashed in Postgres, 15 min TTL,
                        single-use, rate-limited per email and per IP) → email via Resend
GET  /auth/link?token=  consumes the token → opaque server-side session →
                        `__Host-`-prefixed HttpOnly+Secure+SameSite=Lax cookie (plain name on
                        local http) → redirect back to the page you came from (same-site paths
                        only, never arbitrary URLs)
```

Post-login redirects are allowlisted (`/…` paths only); the generic "check your email"
response never reveals whether an email has an account. The account page (`/account`)
shows the email and a sign-out button (logout deletes the server-side session).

## URLs

| Route | Purpose |
|---|---|
| `/` | Search-first homepage |
| `/search?q={query}&type={movie\|person}` | Movie/people search — full page, or results-only fragment for HTMX; `type=person` (home toggle) searches people instead |
| `/movies/{tmdbId}` | Movie detail (director, writers, principal cast are clickable; Trailers link opens a dialog with all videos; Open Graph tags power rich link previews — e.g. in Telegram) |
| `/persons/{tmdbId}` | Person overview + filmography |
| `/persons/{tmdbId}/{directing\|acting\|writing}` | Department filmography |
| `/list?movie={id}&movie={id}…` | Shared movie list — rendered from the URL, no accounts; the Share chip opens a QR dialog with a copyable link |
| `/list/card?movie={id}` | HTMX-only single-card fragment (used when marking from the search overlay while viewing `/list`) |
| `/signin` · `/signin/sent` · `/auth/link?token=…` | Passwordless email sign-in (magic link) |
| `/marked` | Signed-in: your marked movies (server-persisted). The header `Marked · N` points here instead of `/list` |
| `/marked/ids` · `POST`/`DELETE /marked/{movieId}` · `POST`/`DELETE /marked?movie=…` · `POST /marked/migrate` | Mark sync endpoints (authed; bulk POST is the idempotent local→server merge, migrate is the JSON sign-in migration with notes) |
| `/marked/{movieId}/note[/edit]` · `/playlists/{id}/movies/{movieId}/note[/edit]` | Mark/membership note display + editor fragments (htmx, ≤500 chars) |
| `/playlists` · `/playlists/{id}` | Signed-in playlist index and detail (description, rename/delete/Share/Print) |
| `/playlists/select?movie={id}…` · `/playlists/{id}/movies…` | HTMX fragment for the "Add to playlist" dialog + membership mutations |
| `/account` | Minimal account page (email + sign out) |
| `/about` | About page |
| `/videos/{key}` | HTMX-only YouTube player fragment (used by the movie page) |
| `/health` `/healthz` | Health/readiness checks (no TMDB calls) |

## Deployment

Single-VPS production: nginx (TLS, Let's Encrypt) → app container → PostgreSQL, all on the
internal compose network. Details in [`docs/configuration-and-ops.md`](docs/configuration-and-ops.md).

**Local development (Docker):**

```bash
export TMDB_API_TOKEN="eyJhbGci..."
docker compose -f compose.dev.yml up   # app on http://localhost:7070, Postgres on localhost:5433
```

**Production image:** multi-stage `Dockerfile` — Temurin JDK 25 build stage, Temurin JRE 25 runtime,
non-root user, built-in `HEALTHCHECK` on `/health`, pinned base versions.

**CI/CD (GitHub Actions):**
- `ci.yml` — PRs: build + full test suite + production image build + container smoke test. Never deploys.
- `deploy.yml` — push to `main`: tests → image build → push to GHCR as `ghcr.io/<owner>/ekran:<commit-sha>`
  (immutable tags, never `:latest`) → SSH deploy to the VPS (`docker compose pull`/`up -d`) → health check →
  automatic rollback to the previously deployed tag on failure. The server pulls from GHCR with the
  job's `GITHUB_TOKEN` (logged in/out within the deploy) — the package can stay private; no manual
  `docker login` on the VPS.

**Server-side setup (once):**
1. VPS with Docker + Compose + git; firewall: 22/80/443 only; non-root deploy user in the `docker` group (SSH keys).
2. `~/ekran/.env` with `TMDB_API_TOKEN`, `RESEND_API_KEY` and `POSTGRES_PASSWORD` (never committed) —
   the deploy fails fast with a clear message until it exists.
   The config-only checkout itself (`~/ekran`) is bootstrapped automatically on the first deploy
   (no JDK/Gradle needed — the server never builds; if the repo is private, clone it manually).
3. DNS for the domain → VPS IP; edit `nginx/conf.d/ekran.conf`;
   issue certs with certbot into `./certbot/conf` (`docker run --rm -v ./certbot/conf:/etc/letsencrypt ... certonly`).
4. Required repo secrets: `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`.

**Rollback:** `.deployed-image` on the server keeps the current tag; deploys restore it on health-check
failure, or manually: `APP_IMAGE=ghcr.io/<owner>/ekran:<sha> docker compose up -d app` (from `~/ekran`).
PostgreSQL joins as a third compose service (`db`, `postgres:18-alpine`, internal network only,
`db-data` volume); the app runs SQL migrations from `/db/migration/V<n>.sql` at startup.

## TMDB attribution

Ekran uses the TMDB API but is not endorsed or certified by TMDB.

Every page carries this attribution in the footer with a link to
[themoviedb.org](https://www.themoviedb.org/) and the vendored TMDB logo
(`src/main/resources/static/img/tmdb-logo.svg`), per TMDB's attribution terms.
Movie and person pages also link to the corresponding `themoviedb.org` page
("TMDB").

## Architecture

```
web       Javalin routes, view models, Thymeleaf templates (+ auth session middleware)
  ↓
service   search / movie / person application logic      marks / playlists services
auth      magic-link tokens, server-side sessions, rate limiting
  ↓
email     EmailService abstraction → ResendEmailService → Resend API
  ↓
repository  interfaces for movie/person/search retrieval        marks / playlists / auth repositories (JDBC)
  ↓
tmdb      TMDB client, API DTOs, mapping into domain models    db: migrations + Hikari pool
```

Key rule: **TMDB DTOs never leave the `tmdb` package.** Everything above the repositories speaks
small domain models (`Movie`, `Person`, `Filmography`, `SearchResult`), which is what makes the
planned PostgreSQL-backed local store (step 2) a drop-in replacement — see
[`docs/architecture.md`](docs/architecture.md).

One TMDB call per page view: search = `/search/movie`, movie page = `/movie/{id}` with
`append_to_response=credits`, person page = `/person/{id}` with `append_to_response=movie_credits`.
List surfaces (`/list`, `/marked`, playlists) render up to 100 cards and go through a
Caffeine cache in `MovieService` (24 h TTL) — see `docs/tmdb-integration.md`.

Search-as-you-type is HTMX with a 100 ms debounce and `hx-sync="this: replace"` (aborts any
in-flight request and replaces it, so stale responses can't overwrite newer results and the
final typed state always fires).

The search bar is on every page (shared `searchbar.html` fragment) with a `⌘K` tip badge
(non-home pages only — the home input is already focused and prominent).
On the homepage it searches live in place; on every other page it's a compact input tucked
top-right that drops a results overlay below it (Wikipedia-style) — Escape or click-away
closes it and you stay where you were. `/` or Cmd/Ctrl+K focuses it from anywhere; results
are navigable with `↑`/`↓` (or Ctrl N/P) and Enter opens the highlighted one. An
[about page](/about) is linked from the footer. App JavaScript is just vendored
`htmx.min.js` (2.0.4) plus two first-party files: a ~280-line `search.js` (hotkeys,
Escape, click-away, keyboard nav) and a ~660-line `marked.js` (marking + share/QR).

Movies can be **marked** via the bookmark toggle on the movie page, on any movie card, or
straight from search results (`m` works too). Anonymous marks live in `localStorage` only;
signed-in marks live in PostgreSQL (`marked_movies`, migrated from localStorage on first
authenticated load — additive and idempotent, mark notes included). A mark can carry an
optional personal note (≤500 chars): on cards it sits muted under the info row with an
inline ✎ editor; on the movie page, under the actions row. Anonymous notes stay in
`localStorage`; shared URLs never contain notes. The header shows `Marked · N` (hidden
until your first mark): for anonymous users it opens `/list?movie=…` (the URL *is* the
list — share it as-is or via the QR dialog, print it with original titles and directors,
clear it after a confirm); for signed-in users it opens `/marked` (Share/Print/Clear
there).

A shared URL opened elsewhere reads "Shared list" with an "Add all to marked" button — it
never imports silently (for signed-in users that button persists the bulk via the server).

Signed-in users can also group movies into **playlists** (`playlists` + `playlist_movies`
in PostgreSQL, ordered by insert position): the `Add to playlist` dialog on movie pages
and lists lets them create playlists, toggle membership, or bulk-add the whole shared
list. Playlists are ownership-scoped (another user's playlist behaves as 404); membership
is independent of marks — composing a playlist from your marks **copies** (the marks and
their notes stay; each new membership inherits the mark's note). A playlist can carry an
optional description (≤1000 chars), and each membership an optional per-playlist note —
both edited in place. A playlist's Share link is just `/list?movie=…&name=…`, so it
renders for anyone, no account needed.

## Project layout

```
src/main/java/uk/matvey/ekran/
├── Main.java        wiring + bootstrap (migrations run at startup)
├── config/          AppConfig (env parsing, validation)
├── web/             routes, viewmodels, error handling, auth middleware
├── auth/            AuthService, tokens/hashing, rate limiting, PgAuthRepository
├── marks/           MarksService, MarksRepository → PgMarksRepository
├── playlists/       PlaylistsService, PlaylistsRepository → PgPlaylistsRepository
├── email/           EmailService → ResendEmailService, magic-link email
├── db/              DbMigrations (V<n>.sql runner), DataSources (Hikari)
├── service/         SearchService, MovieService, PersonService
├── domain/          Movie, Person, Filmography, SearchResult, …
├── repository/      interfaces
└── tmdb/            TmdbClient, DTOs, TmdbMapper, repository impls
```

## Roadmap (not in step 1)

- Short-film flag / hide-shorts toggle — blocked on data: TMDB's search response carries no runtime (and no "Short" genre), so classifying search results would cost a detail call per result; revisit with the knowledge base or on `/list` (runtime already known there)
- UI polish
- PostgreSQL-backed local knowledge base with TMDB refresh (see `docs/data-model.md`)
