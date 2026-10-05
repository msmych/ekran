# Configuration and Operations

## Environment variables

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `TMDB_API_TOKEN` | yes | — | v4 Bearer token |
| `DATABASE_URL` | yes | — | `postgres://user:pass@host:5432/db` (percent-encode special chars) |
| `RESEND_API_KEY` | yes | — | magic-link emails |
| `PORT` | no | `7070` | listen port |
| `TMDB_BASE_URL` | no | `https://api.themoviedb.org/3` | API base (tests point it at a stub) |
| `TMDB_IMAGE_BASE_URL` | no | `https://image.tmdb.org/t/p` | image base |
| `TMDB_CONNECT_TIMEOUT_MS` / `TMDB_SEARCH_TIMEOUT_MS` / `TMDB_DETAIL_TIMEOUT_MS` | no | `2000` / `3000` / `5000` | outbound timeouts |
| `RESEND_BASE_URL` | no | `https://api.resend.com` | email API base |
| `AUTH_FROM_EMAIL` | no | `ekran <no-reply@ekran.uk>` | magic-link sender (Resend-verified domain) |
| `PUBLIC_BASE_URL` | no | `https://ekran.uk` | magic-link base; `https://` also sets the `Secure` cookie flag |
| `AUTH_TOKEN_TTL_MINUTES` / `AUTH_SESSION_DAYS` | no | `15` / `30` | token / session lifetime |

`AppConfig` is a pure function of the env — fail-fast with actionable messages, no
secrets in `toString()`.

## Logging and errors

- SLF4J + Logback, single-line request entries. Never log the Authorization header,
  tokens, or query strings (magic-link tokens travel in URLs). TMDB failures log endpoint
  + status + duration only.
- Friendly errors, never raw TMDB/internal exceptions: 404 not-found / bad department,
  503 TMDB unavailable (page or fragment, matching request mode; auth failure logs a loud
  config entry), 500 generic with detail only in logs.

## Running

```
./gradlew run          # loads .env from the repo root; DATABASE_URL/PUBLIC_BASE_URL
                        # default to the dev compose setup (Postgres on :5433)
./gradlew test          # Pg* tests need Docker (Testcontainers)
./gradlew check
```

Migrations (`db/migration/V<n>.sql`, tracked in `schema_migrations`) run at startup before
the server accepts traffic; a failed migration aborts the boot.

## Health and posture

- `GET /health` — readiness: no TMDB calls, pings the DB (`SELECT 1`); used by the Docker
  `HEALTHCHECK`, compose, and the deploy pipeline. `GET /healthz` — liveness, touches
  nothing.
- Single process; Hikari pool ≤8; in-memory magic-link rate limiting is per-instance
  (fine while single-VPS). nginx (443 blocks) sets HSTS, `nosniff`,
  `Referrer-Policy: strict-origin-when-cross-origin`, CSP `frame-ancestors 'self'`.
- Session cookie is `__Host-`-prefixed (Secure, path=/, no domain) in production; plain
  name without Secure on local http.

## Deployment

- Multi-stage `Dockerfile` (JDK 25 build → JRE 25 runtime, non-root, healthcheck).
- `compose.yml` (production): `app` (image `APP_IMAGE`, loopback-only host port) +
  `db` (`postgres:18-alpine`, internal network only, `db-data` volume) + `nginx` (TLS,
  HTTP→HTTPS redirect, ACME webroot, proxy to `app:8080`).
- `compose.dev.yml` (local): app from source on `http://localhost:7070`, Postgres on
  `localhost:5433` (`ekran:ekran/ekran`).
- CI (`ci.yml`): PR checks + image build + container smoke test. Never deploys.
- Deploy (`deploy.yml`, push to `main`): tests → GHCR push with immutable `:<commit-sha>`
  tag → SSH `docker compose pull`/`up -d app` → health check → rollback to the tag in
  `.deployed-image` on failure. Server-side `.env` (`TMDB_API_TOKEN`, `RESEND_API_KEY`,
  `POSTGRES_PASSWORD`) is untracked and required before the first deploy.

## Domains and TLS

- Primary domain **ekran.uk**; the legacy `ekran.matvey.uk` 301s permanently (keep its
  server block + cert until the migration settles). Caveat: localStorage marks are
  origin-scoped and don't follow the redirect — re-marking is the accepted cost.
- Let's Encrypt via certbot webroot (`./certbot/www` mounted at `/var/www/certbot`).
  Issue a certificate **before** deploying a config that references it — nginx refuses a
  443 block whose cert files don't exist:

  ```
  docker run --rm \
    -v "$HOME/ekran/certbot/conf:/etc/letsencrypt" \
    -v "$HOME/ekran/certbot/www:/var/www/certbot" \
    certbot/certbot certonly --webroot -w /var/www/certbot \
    -d ekran.uk --email <email> --agree-tos --no-eff-email
  ```

- Renewal: `certbot renew` with the same mounts, then `docker compose exec -T nginx nginx
  -s reload` (deploys reload nginx automatically after the health check).