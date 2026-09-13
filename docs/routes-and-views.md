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
| `GET /about` | About the app (author link, shortcuts list) | Full page |
| `GET /list?movie={id}&movie={id}…` | Shared movie list — rendered from the URL, not from any server-side state; optional `name={title}` names the list | Full page |
| `GET /list/card?movie={id}` | Single movie-card fragment — used by `marked.js` when a movie is marked from the search overlay while viewing `/list` | HTMX-style fragment |
| `GET /videos/{key}` | YouTube player fragment for trailer/video playback | HTMX fragment |

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

- **Movie row:** poster thumbnail (w92), title, original title (shown under the title, muted — only when it differs from the title; useful when searching by a foreign-language title), release year, rating as subtitle (the movie search response carries no cast/crew or genre names — `vote_average` is the free high-value field). Links to `/movies/{id}`. Every movie row carries the card bookmark toggle (`data-card-mark`, `ResultItemVm.tmdbId` — null for future person results, which render no toggle), so movies can be marked straight from the results.

Persons will join the result list in a follow-up step; the `SearchType` marker in the view model exists so that addition does not reshape `SearchResultsVm`.

Keyboard result navigation: `↓`/`↑` (or `Ctrl N`/`Ctrl P`) move a highlight through results while the search input has focus; `Enter` opens the highlighted result, and without a highlight it fires an immediate HTMX search via the `search`-event trigger. Handled in `search.js` — see `search-interaction.md`.

## `/movies/{tmdbId}` — movie detail

One TMDB call (`/movie/{id}` with `append_to_response=credits,videos` — see `tmdb-integration.md`). Layout, top to bottom:

- Backdrop (optional, subtle) or poster (w342) on the side; title + original title (if different) as heading.
- Meta line: year · runtime (`1h 52m` format) · genres (comma-joined) · rating (TMDB average, one decimal, e.g. `7.8 ★`).
- **Videos (trailers)** — a chip-styled `Trailers (N)` link on its own row below the info bits (pill-shaped, muted; no chip when there are no videos), opening a native `<dialog id="trailers">` rendered server-side at the bottom of the page:
  - The dialog is opened by a tiny delegated handler in `search.js` (`data-dialog` attributes). Escape, ✕ and backdrop click close it; focus trapping comes free from the platform.
  - Layout: title bar (`Trailers`, ✕ at the top right), the best-ranked video's frame edge-to-edge at full dialog width, its caption close beneath it, then the full list of videos ranked best-first (ranking in `MovieDetailVm.rankVideos`): Trailer > Teaser > other types; official > non-official; original-language > English > other; newest `published_at` wins ties (null dates rank last). The currently playing video is highlighted in the list (accent color; the best-ranked one starts selected, the highlight follows picks).
  - The initial frame is a `loading="lazy"` iframe **without autoplay** — a closed dialog is `display: none`, so nothing is fetched until the dialog actually opens; opening loads the frame paused (the user has not asked to play yet).
  - Picking a video → HTMX `GET /videos/{key}?name=…` swaps the player fragment into the dialog (`hx-target="#player" hx-swap="outerHTML"`): the same frame with `autoplay=1` — the click is the explicit play intent, so autoplay is appropriate there. The embed markup lives in one shared fragment (`templates/video-embed.html`), parameterized by autoplay.
  - Closing the dialog (Escape/✕/backdrop) pauses the video: hiding a dialog does not stop its audio, so `search.js` listens for the dialog's `close` event (fires for every close path) and sends YouTube's `pauseVideo` post-message to the frame — enabled by `enablejsapi=1` in the embed URL.
  - Each list item also links to the real YouTube watch page (`https://www.youtube.com/watch?v={key}`) — progressive enhancement: works with JS off, nicer with it. The `Trailers` link itself falls back to the best-ranked video's watch page.
  - **CDN exception:** the `youtube-nocookie.com` iframe (fetched when the trailers dialog opens) is the sole third-party runtime request in the app — a deliberate trade-off, you can't proxy YouTube. Nothing loads while the dialog is closed.
- **Mark** — an icon-only toggle on its own row directly below the Trailers button: a bookmark icon (18px, outline muted → filled accent when marked; `aria-pressed` driven, constant size so nothing jumps). Toggles the movie in the visitor's `localStorage` (`ekran.markedMovies` — plain JSON array of TMDB IDs, insertion-ordered, deduped, corrupt or non-integer entries filtered on load, save failures ignored). No server round-trip — marking is browser state. The physical-key `m` shortcut does the same and is ignored while editing text or with modifier keys. The header (shared `templates/header.html` fragment on every page) shows `Marked · N` — on the homepage it shares the `ekran` logo row (search below); elsewhere it sits at the right, just left of the search bar. It is hidden at zero marks, but only from JS: the server always renders the link (progressive enhancement, so a stale or failed script can never hide the entry point); with working JS its `href` is kept in sync with the current marks, so the link is always directly shareable. The link carries `hx-boost="false"`: boosted anchors freeze their `href` at htmx process time, so a boosted Marked link navigated to a stale URL missing recently-marked movies (bug seen in production — the click must always read the live `href`). Other open tabs follow via the `storage` event, and a bfcache restore (browser back) re-syncs via the `pageshow` event — a restored page shows its stale snapshot and fires no scripts otherwise.
- Overview paragraph.
- **Top crew:**
  - Director(s) — each name is a link to `/persons/{id}/directing`.
  - Writer(s) — job(s) shown in parentheses where useful (`Screenplay`, `Story`); links to `/persons/{id}/writing`.
- **Principal cast:** top ~8 billing order; character name under the actor name; each links to `/persons/{id}/acting`.

Empty-value rules: omit a row entirely if there is no value (no "Runtime: —" noise). Missing poster/backdrop → CSS placeholder, never a broken image.

Crew headings are pluralized by count: "Director"/"Directors", "Writer"/"Writers" (template-side ternary on list size).

Each detail page links to the upstream source: a "TMDB ↗" link (`https://www.themoviedb.org/movie/{id}` / `.../person/{id}`, `target="_blank" rel="noopener"`) near the header.

Back-link to the originating search (`?back=/search?q=…` or simpler: browser back is fine in MVP; keep it simple — browser back only).

## `/videos/{key}` — video player fragment

HTMX-only route (registered in `EkranApp.create`, not a `*Routes` class — no service/IO involved):

- `{key}` is a YouTube video id, validated `[A-Za-z0-9_-]{6,}` — anything else is a 404 (never passes unvalidated input into an iframe URL). No TMDB call: everything needed (key, optional caption `?name=`) is in the request.
- Renders `templates/video-player.html`: `<div id="player">` + the shared 16:9 `video-embed.html` fragment (`youtube-nocookie.com/embed/{key}?enablejsapi=1&autoplay=1` — `enablejsapi` lets the dialog send `pauseVideo` on close) + caption below. The `id="player"` is kept on the fragment so repeated swaps (switching between videos in the dialog) keep retargeting the same slot.
- Only reached via HTMX from the trailers dialog on the movie page; deep-linking straight to it works but is pointless (a bare player with a caption).

## `/list?movie={id}…` — shared movie list

A public, read-only page rendered **from the URL** — no server-side state, cookies, or sessions (the visitor's own marks live in their browser's `localStorage`):

- Repeated `movie` params are TMDB IDs. Parsing rules (`ListRoutes`): non-numeric/zero/negative/over-10-digit values are silently dropped, duplicates collapse (first occurrence wins), requested order is preserved, at most 100 IDs are honored (protects the server and keeps URLs/QRs sane).
- IDs are resolved through `MovieService.findByIds` — TMDB has no batch endpoint, so it is one request per movie internally, but that stays out of the web layer; a 404/missing movie skips that card with a warning log and never fails the whole list.
- Cards (shared `movie-card.html` fragment — the same card as the person filmography grids): poster, title, year, duration (`1h 52m`, no rating); poster and info each link to `/movies/{id}`. Every card carries a bookmark toggle (`data-card-mark`) at the **title level** — a quiet right-aligned icon on the info row, no circle (cards stay uncluttered): on `/list` **unmarking removes the card** and rewrites the address bar (the card set *is* the URL), marking an unmarked card keeps it. The same toggle also appears on person filmography cards and search result rows (where cards never disappear — only the local mark flips). Marking from the search overlay *while viewing `/list`* fetches the `GET /list/card` fragment and appends the card right away.
- **Title + bulk actions** (`marked.js`, from comparing the URL's ids to the local marks): the list is *yours* only when the visible card set **is** your marked set (a subset of your own marks still counts as a shared view) — title "Marked movies" + a **Clear all** chip (guarded by the browser's `confirm()`; on confirm it unmarks everything shown, empties the view, drops the stored name, URL → `/list`); otherwise it reads as a shared list — title "Shared list" + an **Add all to marked** chip (explicit opt-in — visiting someone's URL never imports their list into your marks). A cleared/empty list keeps the "Marked movies" title and hides all its chips (Share, Print, rename, bulk actions) — a global `[hidden] { display: none !important }` rule guarantees the hiding, since `.chip-button`'s own `display` would otherwise override the attribute; the empty state ("No marked movies yet…") is always rendered and revealed client-side.
- **Custom name** — an optional `name={title}` query param, server-rendered (escaped, trimmed, capped at 60 chars) as the page title. A pencil button next to the title edits it inline (Enter/blur commits, Escape cancels, empty input removes it): renaming your own list persists it in `localStorage` and keeps it in the URL, so the marked link, the share dialog and the QR all carry it; renaming someone else's shared list changes only the URL (handy for re-sharing under your own label, never touches the visitor's stored name).
- **Share** — one chip opening a native `<dialog>` (same `data-dialog` machinery as trailers) holding a QR of the *current* URL plus the URL as a real link (opens the list in a new tab) with a **Copy** chip beside it. The copy confirmation happens in the button itself — it flips to "Copied" (accent-colored) for 1.5 s on a fixed `min-width`, so the dialog never resizes; `aria-live` announces the change. The QR is generated client-side on every dialog open by the vendored `qrcode.js`. Client-side by design: the URL is browser-owned state (removals rewrite it via `history.replaceState`), and a server-side QR endpoint would be an open QR-generator abuse vector.
- **Print** — a chip calling `window.print()`; the `@media print` stylesheet strips the chrome (header, search, buttons, footer, posters) and lays the movies out as clean rows (title left, year · duration right, hairline separators), page-break-safe, with print-only sub-lines per movie: original title (when distinct) and director(s) — carried by `MovieCardVm.originalTitle/directors`, populated from the movie detail (null for filmography cards).
- Client behavior (`marked.js`): unmarking a card rewrites the address bar, so a refresh does not resurrect removed cards; following someone's shared URL never imports their list into the visitor's own marks (that's what **Add all to marked** is for — explicit opt-in).

## `/persons/{tmdbId}` and department variants

One TMDB call (`/person/{id}` with `append_to_response=movie_credits` — see `tmdb-integration.md`).

Layout:

- Name, known-for department (e.g. "Known for Directing").
- Optional short biography if already in the response — trimmed to ~2 lines (no truncation JS in MVP; CSS `line-clamp` is acceptable).
- Filmography — grouped sections by department, each rendered as the shared movie-card grid (`movie-card.html`, same cards as `/list`, minus the remove action): poster, title, year — and no role line (the section heading already says Directing/Writing/Acting, and repeating `Director` on every card was noise). Duration is deliberately not shown: TMDB's person-credits payload has no `runtime`, and fetching it per credit would cost one detail call per movie (a busy person = 50–100+ calls per page, against the one-call-per-page rule).
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
- `SearchResultsVm` — `query`, `List<ResultItemVm>` (`type: MOVIE|PERSON`, title, `originalTitle` nulled by the view model when same-as/blank, subtitle, year, imageUrl, href); only `MOVIE` is produced in Step 1.
- `MovieDetailVm` — preformatted fields (runtime as `1h 52m`, rating as string, joined genres, resolved poster/backdrop URLs, `List<PersonLinkVm> directors/writers/cast` with `name, role, href`), plus `List<VideoVm> videos` (`key, name, type`) ranked best-first by `rankVideos` (see `/movies/{tmdbId}`), so templates stay logic-free.
- `PersonPageVm` — name, knownFor, bio, filmography sections of `MovieCardVm`s, current department for tab highlighting.
- `MovieCardVm` — the one shared card shape (`tmdbId, title, year, meta, posterUrl`) with two factories: `of(Movie)` for `/list` (meta = formatted duration) and `of(FilmographyItem)` for person filmography grids (meta = null — see the runtime note there); `MovieDetailVm` carries its own `tmdbId` for the mark button's `data-movie-id`.

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

- **Cache policy:** every response — rendered pages and static assets — is served with `Cache-Control: no-cache` (always revalidate, cheap 304s via ETag). Explicit revalidation is required: Javalin/Jetty's default static header is `max-age=0` paired with a fake 1980 `Last-Modified`, and browsers (notably Safari) then apply heuristic caching that serves stale JS long after a deploy — a stale `search.js` against fresh markup breaks the trailers dialog and the mobile overlay-tap fix (symptoms seen in production: the Trailers link navigating to YouTube, overlay taps not following links).
- `/css/app.css` — single lightweight local stylesheet, no framework, basic system font stack. No design polish in Step 1.
- `/js/htmx.min.js` — vendored HTMX (see `search-interaction.md` for version pinning).
- `/js/search.js` — first-party JS (~220 lines: hotkeys, Escape, overlay close, mobile tap-through grace, keyboard result navigation, native-dialog handling, video-list selection).
- `/js/marked.js` — marking/list client (~450 lines: localStorage store, mark toggles on the movie page/cards/search results, marked count/link, `/list` title + custom name + bulk actions, confirmed clear, share dialog, QR render, URL sync, print), loaded (deferred) on every page.
- `/js/qrcode.js` — vendored `qrcode-generator` 1.4.4 (kazuhikoarase, MIT; auto type, SVG output), loaded only by `/list`.
- `/favicons/` — vendored favicon set (ico + PNG sizes + webmanifest); linked from the shared `head` fragment.
- `/img/tmdb-logo.svg` — vendored TMDB logo for the attribution footer.

No CDN references anywhere (sole deliberate exception: the `youtube-nocookie.com` iframe inside the trailers dialog, fetched when the dialog opens — see `/movies/{tmdbId}`). App JS is minimal: vendored `htmx.min.js` plus two small first-party files, `/js/search.js` (~220 lines: hotkeys, Escape semantics, overlay close, mobile tap-through, keyboard result navigation, native-dialog open/close, video-list selection) and `/js/marked.js` (~450 lines: mark storage/toggle everywhere, marked-link sync, list title/custom name/bulk actions, confirmed clear, share dialog, print), plus the QR library on `/list` only. Pages must render and remain navigable (search box visible, links clickable) even if JS fails to load — progressive enhancement baseline: without JS the search input simply does nothing dynamic, and all links work as plain links.
