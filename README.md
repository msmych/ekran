# ekran

Quick movie search — a fast, no-bloat movie discovery web app. Open the site, start typing, results
appear as you type, open a movie, jump to a director's/actor's/writer's filmography.

Step 1 is TMDB-backed: Javalin (no Spring), server-rendered Thymeleaf + HTMX, no frontend framework,
no persistence. See [`docs/`](docs/) for the full spec and
[`quick_movie_search_deployment_strategy.md`](quick_movie_search_deployment_strategy.md) for the
deployment plan (implemented below, minus PostgreSQL which is deferred).

## Requirements

- JDK 25 (any JDK 25 works; Gradle auto-provisions it via toolchains if missing)
- A TMDB API **v4 read access token** — <https://www.themoviedb.org/settings/api>

## Setup

```bash
export TMDB_API_TOKEN="your-v4-read-access-token"
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

The token is never committed or logged; the app fails fast at startup if it is missing.

## Run

```bash
./gradlew run
```

Then open <http://localhost:7070>.

## Test

```bash
./gradlew test      # unit + route/integration tests (no network, no API key needed)
./gradlew check
```

## URLs

| Route | Purpose |
|---|---|
| `/` | Search-first homepage |
| `/search?q={query}` | Movie search — full page, or results-only fragment for HTMX |
| `/movies/{tmdbId}` | Movie detail (director, writers, principal cast are clickable) |
| `/persons/{tmdbId}` | Person overview + filmography |
| `/persons/{tmdbId}/{directing\|acting\|writing}` | Department filmography |
| `/health` `/healthz` | Health/readiness checks (no TMDB calls) |

## Deployment

Single-VPS production: nginx (TLS, Let's Encrypt) → app container, no database yet.
Full plan in [`quick_movie_search_deployment_strategy.md`](quick_movie_search_deployment_strategy.md).

**Local development (Docker):**

```bash
export TMDB_API_TOKEN="eyJhbGci..."
docker compose -f compose.dev.yml up   # app on http://localhost:7070
```

**Production image:** multi-stage `Dockerfile` — Temurin JDK 25 build stage, Temurin JRE 25 runtime,
non-root user, built-in `HEALTHCHECK` on `/health`, pinned base versions.

**CI/CD (GitHub Actions):**
- `ci.yml` — PRs: build + full test suite + production image build + container smoke test. Never deploys.
- `deploy.yml` — push to `main`: tests → image build → push to GHCR as `ghcr.io/<owner>/ekran:<commit-sha>`
  (immutable tags, never `:latest`) → SSH deploy to the VPS (`docker compose pull`/`up -d`) → health check →
  automatic rollback to the previously deployed tag on failure.

**Server-side setup (once):**
1. VPS with Docker + Compose; firewall: 22/80/443 only; non-root deploy user (SSH keys).
2. Config-only checkout of this repo at `/opt/ekran` (no JDK/Gradle needed — the server never builds).
3. `/opt/ekran/.env` with `TMDB_API_TOKEN` (never committed; add it to `.gitignore` conventions).
4. DNS for the domain → VPS IP; edit `nginx/conf.d/ekran.conf` (replace `movies.example.com`);
   issue certs with certbot into `./certbot/conf` (`docker run --rm -v ./certbot/conf:/etc/letsencrypt ... certonly`).
5. Required repo secrets: `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`.

**Rollback:** `.deployed-image` on the server keeps the current tag; deploys restore it on health-check
failure, or manually: `APP_IMAGE=ghcr.io/<owner>/ekran:<sha> docker compose up -d app`.

When PostgreSQL lands (step 2), it joins as a third compose service on the internal network only —
the repository boundary makes that an additive change.

## TMDB attribution

Ekran uses the TMDB API but is not endorsed or certified by TMDB.

Every page carries this attribution in the footer with a link to
[themoviedb.org](https://www.themoviedb.org/) and the vendored TMDB logo
(`src/main/resources/static/img/tmdb-logo.svg`), per TMDB's attribution terms.
Movie and person pages also link to the corresponding `themoviedb.org` page
("View on TMDB").

## Architecture

```
web       Javalin routes, view models, Thymeleaf templates
  ↓
service   search / movie / person application logic
  ↓
repository  interfaces for movie/person/search retrieval
  ↓
tmdb      TMDB client, API DTOs, mapping into domain models
```

Key rule: **TMDB DTOs never leave the `tmdb` package.** Everything above the repositories speaks
small domain models (`Movie`, `Person`, `Filmography`, `SearchResult`), which is what makes the
planned PostgreSQL-backed local store (step 2) a drop-in replacement — see
[`docs/architecture.md`](docs/architecture.md).

One TMDB call per page view: search = `/search/movie`, movie page = `/movie/{id}` with
`append_to_response=credits`, person page = `/person/{id}` with `append_to_response=movie_credits`.

Search-as-you-type is HTMX with a 100 ms debounce and `hx-sync="this: replace"` (aborts any
in-flight request and replaces it, so stale responses can't overwrite newer results and the
final typed state always fires).

The search bar is on every page (shared `searchbar.html` fragment). On the homepage it searches
live in place; on every other page it's a compact input tucked top-right that drops a results
overlay below it (Wikipedia-style) — Escape or click-away closes it and you stay where you
were. `/` or Cmd/Ctrl+K focuses it from anywhere. App JavaScript is just vendored
`htmx.min.js` (2.0.4) plus a ~40-line `search.js` (hotkeys, Escape, click-away).

## Project layout

```
src/main/java/uk/matvey/ekran/
├── Main.java        wiring + bootstrap
├── config/          AppConfig (env parsing, validation)
├── web/             routes, viewmodels, error handling
├── service/         SearchService, MovieService, PersonService
├── domain/          Movie, Person, Filmography, SearchResult, …
├── repository/      interfaces
└── tmdb/            TmdbClient, DTOs, TmdbMapper, repository impls
```

## Roadmap (not in step 1)

- Persons in search results (search currently returns movies only)
- Keyboard result navigation
- UI polish
- PostgreSQL-backed local knowledge base with TMDB refresh (see `docs/data-model.md`)