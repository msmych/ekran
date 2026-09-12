# Configuration and Operations

## Environment variables

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `TMDB_API_TOKEN` | yes | — | v4 Bearer token; app fails fast at startup with a clear message if missing |
| `PORT` | no | `7070` | Javalin listen port |
| `TMDB_BASE_URL` | no | `https://api.themoviedb.org/3` | API base (allows pointing at a proxy/stub in tests) |
| `TMDB_IMAGE_BASE_URL` | no | `https://image.tmdb.org/t/p` | Image base for poster/profile/backdrop paths |
| `TMDB_CONNECT_TIMEOUT_MS` | no | `2000` | Outbound connect timeout |
| `TMDB_SEARCH_TIMEOUT_MS` | no | `3000` | Per-request timeout for search calls |
| `TMDB_DETAIL_TIMEOUT_MS` | no | `5000` | Per-request timeout for detail/credits calls |

`AppConfig` (in `config/`):

- Pure function of the environment (reads `System.getenv`) — no global state, constructed in `Main` and passed down.
- Validates: token present; timeouts positive; ports in range. Fail-fast with actionable messages ("TMDB_API_TOKEN is not set — get a v4 token from themoviedb.org").
- No secrets in `toString()` — token is masked/omitted.

## Logging

- **SLF4J + Logback** (`logback-classic`; Logback itself provides the SLF4J binding — exclude/avoid any other binding on the classpath). Simple `logback.xml`: console appender, sensible pattern, INFO default, no exotic config.
- Structured-ish single-line entries: timestamp, level, method+path, status, duration ms, and a short request id (MVP: thread name or random UUID suffix is fine).
- TMDB failures logged with: endpoint path (no query strings containing keys — Bearer auth keeps URLs clean anyway), status code, duration. **Never log the Authorization header or token.**
- Internal errors log stack traces at server side only; client sees a generic message.

## Error surfacing

| Situation | HTTP | Client sees |
|---|---|---|
| Movie/person not found in TMDB | 404 | Friendly 404 page with search box |
| Unknown department in path | 404 | Same 404 page |
| TMDB timeout/5xx/malformed | 503 | "Temporarily unavailable, try again" (page or fragment matching request mode) |
| TMDB auth failure (bad token) | 503 | Same as above; server log gets a loud config-problem entry |
| Unexpected exception | 500 | Generic message; detail only in logs |

Rule: friendly application-level errors, never raw TMDB/internal exceptions in responses.

## Running

```
./gradlew run          # requires TMDB_API_TOKEN in env
./gradlew test
./gradlew check
```

Packaging via the Gradle `application` plugin (`./gradlew installDist` / `build`); a Dockerfile is **not** MVP scope but nothing in the design precludes it.

## Operational posture

- Single process, in-memory everything, horizontal-scale-safe by construction (no user state).
- Health: `GET /health` returning `{"status":"UP"}` (readiness; no TMDB calls, no dependencies yet)
  and `GET /healthz` returning `200 "ok"` — used by the Docker `HEALTHCHECK`, Compose, and the
  deploy pipeline. When PostgreSQL joins (step 2), readiness gains a lightweight DB connectivity check.
- No metrics/analytics in MVP.

## Deployment (implemented, minus PostgreSQL)

- Multi-stage `Dockerfile` (JDK 25 build → JRE 25 runtime, non-root, HEALTHCHECK on `/health`).
- `compose.yml` — production: `app` (image from `APP_IMAGE`, loopback-only host port for health
  checks, JSON-file logs with rotation) + `nginx` (TLS via Let's Encrypt, HTTP→HTTPS redirect,
  ACME challenge path, proxy to `app:8080`). No postgres service yet — deferred.
- `compose.dev.yml` — local dev: app built from source on `http://localhost:7070`.
- `.github/workflows/ci.yml` — PR checks + image build + container smoke test.
- `.github/workflows/deploy.yml` — main-branch deploy: tests → GHCR push with immutable
  `:<commit-sha>` tag → SSH `docker compose pull`/`up -d` → health check → rollback to the
  previous tag on failure (`.deployed-image` on the server).
- Production `.env` (with `TMDB_API_TOKEN`) exists only on the server, untracked.