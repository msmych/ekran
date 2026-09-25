# Configuration and Operations

## Environment variables

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `TMDB_API_TOKEN` | yes | — | v4 Bearer token; app fails fast at startup with a clear message if missing |
| `DATABASE_URL` | yes | — | `postgres://user:pass@host:5432/db` (or `postgresql://…`; percent-encode special characters in the password) |
| `RESEND_API_KEY` | yes | — | Resend API key for magic-link emails |
| `PORT` | no | `7070` | Javalin listen port |
| `TMDB_BASE_URL` | no | `https://api.themoviedb.org/3` | API base (allows pointing at a proxy/stub in tests) |
| `TMDB_IMAGE_BASE_URL` | no | `https://image.tmdb.org/t/p` | Image base for poster/profile/backdrop paths |
| `TMDB_CONNECT_TIMEOUT_MS` | no | `2000` | Outbound connect timeout |
| `TMDB_SEARCH_TIMEOUT_MS` | no | `3000` | Per-request timeout for search calls |
| `TMDB_DETAIL_TIMEOUT_MS` | no | `5000` | Per-request timeout for detail/credits calls |
| `RESEND_BASE_URL` | no | `https://api.resend.com` | Resend API base (stub in tests) |
| `AUTH_FROM_EMAIL` | no | `ekran <no-reply@ekran.uk>` | Magic-link sender identity (must be a Resend-verified domain) |
| `PUBLIC_BASE_URL` | no | `https://ekran.uk` | Base for magic-link URLs; `https://` prefixes also set the `Secure` cookie flag |
| `AUTH_TOKEN_TTL_MINUTES` | no | `15` | Magic-link token lifetime |
| `AUTH_SESSION_DAYS` | no | `30` | Session lifetime (cookie max-age + DB expiry) |

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
./gradlew run          # loads .env from the repo root; DATABASE_URL/PUBLIC_BASE_URL default to the dev compose setup
./gradlew test
./gradlew check
```

Packaging via the Gradle `application` plugin (`./gradlew installDist` / `build`) and a multi-stage Dockerfile (see `Deployment` and the repo root `Dockerfile`).

## Operational posture

- Single process; PostgreSQL via a small Hikari pool (max 8 connections). In-memory rate
  limiting (magic-link requests) is per-instance — fine while deployment stays single-VPS.
- Health: `GET /health` returning `{"status":"UP"}` (readiness: no TMDB calls, but does ping
  the database with `SELECT 1` — `{"status":"DOWN"}` + 503 when it is unreachable) — used by
  the Docker `HEALTHCHECK`, Compose, and the deploy pipeline. `GET /healthz` returns
  `200 "ok"` without touching the DB (liveness).
- Migrations (`db/migration/V<n>.sql`, tracked in `schema_migrations`) run at startup before
  the server accepts traffic; a failed migration aborts the boot.
- Request logging prints the URI without the query string — magic-link tokens must never
  land in logs. Resend failures log status/response server-side only; users see a generic
  retryable error. Secrets never appear in `AppConfig.toString()`.
- Security headers are set by nginx on the 443 blocks: HSTS, `X-Content-Type-Options: nosniff`,
  `Referrer-Policy: strict-origin-when-cross-origin`, CSP `frame-ancestors 'self'`, plus
  `server_tokens off`. The session cookie is `__Host-`-prefixed (Secure + path=/ + no domain)
  in production; local http runs use the plain name without the Secure flag.
- No metrics/analytics in MVP.

## Deployment (implemented)

- Multi-stage `Dockerfile` (JDK 25 build → JRE 25 runtime, non-root, HEALTHCHECK on `/health`).
- `compose.yml` — production: `app` (image from `APP_IMAGE`, loopback-only host port for health
  checks, JSON-file logs with rotation) + `db` (`postgres:18-alpine`, internal network only,
  `db-data` volume, `pg_isready` healthcheck; the app waits for it) + `nginx` (TLS via Let's
  Encrypt, HTTP→HTTPS redirect, ACME challenge path, proxy to `app:8080`).
- `compose.dev.yml` — local dev: app built from source on `http://localhost:7070`, Postgres
  published on `localhost:5433` (`ekran:ekran/ekran`) for the IDE DB explorer.
- `.github/workflows/ci.yml` — PR checks + image build + container smoke test (app + Postgres
  containers on a shared Docker network; verifies `/health` and the sign-in page).
- `.github/workflows/deploy.yml` — main-branch deploy: tests → GHCR push with immutable
  `:<commit-sha>` tag → SSH `docker compose pull`/`up -d app` (starts `db` first) → health
  check → rollback to the previous tag on failure (`.deployed-image` on the server).
- Production `.env` (with `TMDB_API_TOKEN`, `RESEND_API_KEY`, `POSTGRES_PASSWORD`) exists only
  on the server, untracked.

## Domains and TLS

- Primary domain: **ekran.uk**. The legacy `ekran.matvey.uk` permanently redirects
  (301, path-preserving) — its server block and certificate stay in place so the
  redirect keeps working; drop both (plus the DNS record) once the migration settles.
- Certificates: Let's Encrypt via the certbot webroot (`./certbot/www`, mounted into
  nginx at `/var/www/certbot`); the port-80 block answers ACME challenges for both names.
  Issue a new certificate **before** deploying a config that references it — nginx refuses
  to load a 443 block whose cert files don't exist:

  ```
  docker run --rm \
    -v "$HOME/ekran/certbot/conf:/etc/letsencrypt" \
    -v "$HOME/ekran/certbot/www:/var/www/certbot" \
    certbot/certbot certonly --webroot -w /var/www/certbot \
    -d ekran.uk --email <email> --agree-tos --no-eff-email
  ```

- Renewal covers every lineage under `certbot/conf` (`certbot renew` with the same mounts),
  followed by `docker compose exec -T nginx nginx -s reload`. Deploys reload nginx
  automatically after the health check, so config-only changes roll out with any deploy.
- Caveat: localStorage marks are origin-scoped — marks made on `ekran.matvey.uk` do not
  follow visitors to `ekran.uk` (a 301 means the old origin never runs client code again).
  Re-marking is the accepted cost of the move.