# Routes and Views

## URL surface (canonical, deep-linkable)

| Route | Purpose | Response |
|---|---|---|
| `GET /` | Search-first homepage; with `?q=` renders home pre-filled with results | Full page |
| `GET /search?q={query}` | Movie search results | Full page **or** HTMX fragment (see below) |
| `GET /movies/{tmdbId}` | Movie detail page | Full page |
| `GET /persons/{tmdbId}` | Person overview + filmography (all sections) | Full page |
| `GET /persons/{tmdbId}/directing` | Director filmography | Full page |
| `GET /persons/{tmdbId}/acting` | Acting filmography | Full page |
| `GET /persons/{tmdbId}/writing` | Writing credits | Full page |

All pages are server-rendered by Thymeleaf. No client-side routing.

Naming rationale: resource routes are plural (`/movies/{id}`, `/persons/{id}`); `/search` stays a single generic path so blending in persons results later (follow-up step) does not break URLs.

**UI direction:** deliberately basic in Step 1 — clean typography, minimal CSS, no design system, no polish. We'll figure out the visual direction later; do not invest in it now.

## `/` — homepage

- Autofocused `<input type="search" id="search">` in a prominent hero position; nothing else competes for attention.
- Below the input: a small hint line ("Search movies…") and nothing else until a query exists.
- Optional: persist nothing. No recent searches, no history (MVP).
- The same Thymeleaf fragment that renders results on `/search` is embedded empty on `/` and filled by HTMX.
- With `?q=`, the homepage renders the query pre-filled plus results (deep-linkable `/?q=alien`). Always a full page — even for HTMX requests — because it is the swap target for boosted navigation.

## `/search?q={query}` — dual-mode response

The route inspects the `HX-Request` header:

- **HTMX request** (`HX-Request: true`): return only the `results :: results-fragment` fragment (the result list), HTTP 200. This is the search-as-you-type path and must stay lightweight — it renders only the result list HTML, nothing else.
- **Regular request** (direct navigation, bookmark, refresh): return a full page containing the query pre-filled in the search box plus the same results fragment. Copying `/search?q=alien` must work as a bookmark.

Edge cases:

- Blank/whitespace-only `q` → full-page mode: homepage state; fragment mode: **truly empty 200 body** (not a whitespace-only fragment — whitespace text nodes would defeat the client-side `:not(:empty)` overlay-visibility rule). HTMX debounce should not even fire in most cases — see `search-interaction.md`.
- Query length: trim; treat queries longer than ~100 chars as invalid → empty result state, not an error.
- Pagination is **not** in the MVP: return the first result page only (TMDB default page size is 20 movies). Design note: do not add "load more" now, but keep `SearchResultPage` shaped so a page token could be added later.

### Result item (movies only in Step 1)

Compact row, in TMDB relevance order:

- **Movie row:** poster thumbnail (w92), title, release year, rating as subtitle (the movie search response carries no cast/crew or genre names — `vote_average` is the free high-value field). Links to `/movies/{id}`.

Persons will join the result list in a follow-up step; the `SearchType` marker in the view model exists so that addition does not reshape `SearchResultsVm`.

Keyboard result navigation is deliberately out of Step 1 scope (to be designed separately); native behavior applies (Tab through links, Enter fires an immediate HTMX search via the `search`-event trigger).

## `/movies/{tmdbId}` — movie detail

One TMDB call (`/movie/{id}` with `append_to_response=credits` — see `tmdb-integration.md`). Layout, top to bottom:

- Backdrop (optional, subtle) or poster (w342) on the side; title + original title (if different) as heading.
- Meta line: year · runtime (`1h 52m` format) · genres (comma-joined) · rating (TMDB average, one decimal, e.g. `7.8 ★`).
- Overview paragraph.
- **Top crew:**
  - Director(s) — each name is a link to `/persons/{id}/directing`.
  - Writer(s) — job(s) shown in parentheses where useful (`Screenplay`, `Story`); links to `/persons/{id}/writing`.
- **Principal cast:** top ~8 billing order; character name under the actor name; each links to `/persons/{id}/acting`.

Empty-value rules: omit a row entirely if there is no value (no "Runtime: —" noise). Missing poster/backdrop → CSS placeholder, never a broken image.

Crew headings are pluralized by count: "Director"/"Directors", "Writer"/"Writers" (template-side ternary on list size).

Each detail page links to the upstream source: a "View on TMDB ↗" link (`https://www.themoviedb.org/movie/{id}` / `.../person/{id}`, `target="_blank" rel="noopener"`) near the header.

Back-link to the originating search (`?back=/search?q=…` or simpler: browser back is fine in MVP; keep it simple — browser back only).

## `/persons/{tmdbId}` and department variants

One TMDB call (`/person/{id}` with `append_to_response=movie_credits` — see `tmdb-integration.md`).

Layout:

- Name, known-for department (e.g. "Known for Directing").
- Optional short biography if already in the response — trimmed to ~2 lines (no truncation JS in MVP; CSS `line-clamp` is acceptable).
- Filmography — grouped sections by department, each a simple list of movies with year + role:
  - **Directing:** title, year, job (`Director`).
  - **Writing:** title, year, job (`Screenplay` / `Writer` / `Story`).
  - **Acting:** title, year, character name.
- Filmography lists are sorted by release year descending; undated items go last. Duplicate movies across departments stay in each relevant section.
- Every movie title links to `/movies/{id}`.

### Department pages `/persons/{tmdbId}/{department}`

- `{department}` ∈ {`directing`, `acting`, `writing`}.
- Same header (name, known-for), then **only** the requested section, full-length.
- Navigation tabs/links between `All | Directing | Acting | Writing` with the current one marked — these are plain links (server-rendered), deep-linkable.
- Unknown department → 404.
- Rationale for dedicated URLs: brief §7 requires them, and they give Google-friendly canonical pages per craft. Implementation may share one template with a section selector.

## View models

Templates never receive domain models with TMDB-shaped leftovers. `web/viewmodels` provides:

- `HomePageVm` — nothing but autofocus flag (MVP).
- `SearchResultsVm` — `query`, `List<ResultItemVm>` (`type: MOVIE|PERSON`, title, subtitle, year, imageUrl, href); only `MOVIE` is produced in Step 1.
- `MovieDetailVm` — preformatted fields (runtime as `1h 52m`, rating as string, joined genres, resolved poster/backdrop URLs, `List<PersonLinkVm> directors/writers/cast` with `name, role, href`).
- `PersonPageVm` — name, knownFor, bio, `Map<Department, List<FilmographyItemVm>>` or fixed fields per department page, current department for tab highlighting.

All image URLs are absolute (resolved in the tmdb adapter) — templates contain no TMDB URL-construction logic.

## Footer — TMDB attribution

Every full page (home, movie, person, error) includes a shared footer fragment (`templates/footer.html`):

> Ekran uses the TMDB API but is not endorsed or certified by TMDB. **[TMDB logo →](https://www.themoviedb.org/)**

The logo is the official TMDB mark, vendored locally at `static/img/tmdb-logo.svg` (fetched from themoviedb.org brand assets — no third-party request at runtime), and links to https://www.themoviedb.org/. Required by TMDB's attribution terms.

## Error pages

- `404` — movie/person not found in TMDB, or bad department path. Simple page with the search box front and center.
- `503` — TMDB unavailable/timeout: "Search is temporarily unavailable" with a retry hint. Fragment mode returns a fragment with the same message.
- `500` — unexpected: generic message, server logs the detail. Never expose TMDB JSON or stack traces to the client.

## Static assets

Served from `src/main/resources/static/`:

- `/css/app.css` — single lightweight local stylesheet, no framework, basic system font stack. No design polish in Step 1.
- `/js/htmx.min.js` — vendored HTMX (see `search-interaction.md` for version pinning).
- `/js/search.js` — the only first-party JS (~40 lines: hotkeys, Escape, overlay close).
- `/img/tmdb-logo.svg` — vendored TMDB logo for the attribution footer.

No CDN references anywhere. App JS is minimal: vendored `htmx.min.js` plus one small first-party file, `/js/search.js` (~40 lines: `/` and Cmd/Ctrl+K hotkeys, Escape semantics, click-away overlay close). Pages must render and remain navigable (search box visible, links clickable) even if JS fails to load — progressive enhancement baseline: without JS the search input simply does nothing dynamic, and all links work as plain links.