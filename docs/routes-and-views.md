# Routes and Views

All pages are server-rendered by Thymeleaf; no client-side routing. Routes are plural
resources (`/movies/{id}`, `/persons/{id}`); `/search` is one path — the `type` param
carries the movie/person distinction.

| Route | Purpose | Response |
|---|---|---|
| `GET /` | Search-first homepage; `?q=` renders it pre-filled with results | Full page |
| `GET /search?q={query}&type={movie\|person}` | Search — fragment for HTMX, full page for direct navigation | Fragment / full page |
| `GET /movies/{tmdbId}` | Movie detail | Full page |
| `GET /persons/{tmdbId}` | Person filmography — defaults to the known-for department | Full page |
| `GET /persons/{tmdbId}/all` / `/{directing\|acting\|writing}` | Full filmography / one department | Full page |
| `GET /about` | About page | Full page |
| `GET /list?movie={id}…&name={title}` | Shared list rendered from the URL | Full page |
| `GET /list/card?movie={id}` | Single-card fragment (marked.js overlay-mark on `/list`) | Fragment |
| `GET /marked` · `GET /notes` | Signed-in marks / notes (anonymous → `/signin`) | Full page |
| `POST`/`DELETE /marked/{movieId}` · `POST`/`DELETE /marked?movie=…` · `GET /marked/ids` · `POST /marked/migrate` | Mark sync (authed; bulk POST = idempotent merge; migrate = JSON sign-in migration, idempotent) | 204 / text |
| `GET`/`POST /movies/{movieId}/note[/edit]` | Movie note fragments (≤500 chars) | Fragment |
| `GET`/`POST /playlists` · `GET /playlists/{id}` · `POST …/rename\|/description\|/delete\|/movies` · `POST`/`DELETE …/movies/{movieId}` · `GET`/`POST …/movies/{movieId}/note[/edit]` · `POST …/movies/{movieId}/move` · `GET /playlists/select` | Playlist CRUD + membership (authed, ownership-scoped; `/select` = the Add-to-playlist dialog fragment) | Page / 303 / fragment |
| `GET /signin` · `/signin/sent` · `GET /auth/link?token=…` · `POST /signout` · `GET /account` | Magic-link auth | Page / 303 |
| `GET /videos/{key}` | YouTube player fragment (trailers dialog) — key validated `[A-Za-z0-9_-]{6,}` → 404 otherwise (never passes unvalidated input into an iframe URL) | Fragment |
| `GET /health` · `/healthz` | Readiness (pings DB) / liveness | JSON / text |

## Search (`/`, `/search`)

- Fragment mode (`HX-Request: true`) renders only the result list; full-page mode renders
  home with the query pre-filled (deep-linkable). Blank query in fragment mode → truly
  empty 200 body (whitespace text nodes would keep the overlay panel open).
- Query trimmed server-side; >~100 chars → empty state, not an error. First result page
  only (TMDB default 20).
- Movie row: thumb (w92), title, original title when distinct, year, rating subtitle, mark
  toggle. Person row: thumb, name, known-for subtitle, no mark toggle.
- Movies/People toggle on every page (home: pills under the input; overlay: pills at the
  panel top, outside the swapped `#overlay-results`). Person pages default the overlay to
  people. Deep links: `/?q=…&type=person`.
- See `search-interaction.md` for the client contract.

## Movie page (`/movies/{tmdbId}`)

- Quiet head: title with the mark toggle at the row's right edge; meta line year · runtime
  · genres (no rating); poster (w342) opens the original-size version in a `<dialog>`.
- Crew links: directors → `/persons/{id}/directing`, writers (with jobs) → `…/writing`,
  top-8 cast (with character) → `…/acting`. Empty values omit rows entirely.
- **Trailers** chip → native `<dialog>`: best-ranked video first (Trailer > Teaser >
  official > original language > newest), lazy iframe without autoplay; picking a video
  swaps the player fragment (`/videos/{key}`) with autoplay; closing the dialog pauses
  via YouTube post-message. Each item links to the real watch page (JS-off fallback).
- **Playlists row** (signed-in): chips are plain links into the playlist — membership
  changes happen only in the dialog opened by the trailing **Edit**/**+ Add** button
  (no accidental one-click removals).
- **Movie note** block under the actions (signed-in only, marked or not); `n` hotkey edits.
- Open Graph tags (Telegram previews are the target; fetcher runs no JS, the page is fully
  SSR): `og:title` "Title (Year)", overview capped at 300, `og:image` = w780 backdrop
  (falls back to poster), canonical URL. Only pages with an `og` model render tags.
- "TMDB ↗" link at the bottom, after the credits.

## Person pages (`/persons/{tmdbId}[/all|/{department}]`)

- Bare URL shows the known-for department's section (actor → Acting); unmapped known-for
  or an empty section falls back to the all view at `/all`. Tabs: `Directing | Acting |
  Writing | All`, current one marked; unknown department → 404.
- Header: name, "Known for X", life dates ("Born November 30, 1937" / en-dash range),
  TMDB link. No biography — deliberately dropped.
- Filmography: shared movie-card grid per department, sorted by release date desc
  (undated last), no role line, no duration (see `data-model.md`). Every title links to
  `/movies/{id}`.

## Shared list (`/list`), `/marked`, `/notes`

- `/list` renders purely from the URL: repeated `movie` params, invalid values dropped,
  dupes collapsed, order preserved, ≤100 honored; missing movies skip their card without
  failing the page. Optional `name=` renders as the title (inline pencil edit).
- Title/bulk actions (`marked.js`, from comparing URL ids to local marks): the list is
  "yours" only when the card set is your marked set → "Marked movies" + **Clear all**;
  otherwise "Shared list" + **Add all to marked** (explicit opt-in — visiting a URL never
  imports silently). Unmarking a card on `/list` removes it and rewrites the address bar.
- **Share** chip → QR dialog of the current URL (client-side QR by design: the URL is
  browser-owned state). **Print** chip → print stylesheet lays movies out as rows with
  original title and directors as print-only sub-lines.
- `/marked`: the signed-in marked set (capped at 100, one TMDB call per card via the
  cache), same share/print machinery, plus **Move all to playlist** (see below) and
  server-side Clear all.
- `/notes`: every noted movie, rows view server-defaulted; unmarked movies with notes
  appear here (notes are detached from marks).
- **Grid ⇄ Rows** toggle on all four card lists (`localStorage` preference; a
  server-rendered view wins). Person filmography and search stay grid-only.
- **Movie notes** (signed-in, ≤500 chars): inline ✎ editor on every card and the movie
  page; htmx swaps the whole note area; Escape cancels. Never in shared URLs.

## Playlists (`/playlists`)

- Ownership-scoped SQL everywhere; names ≤60 trimmed (duplicates allowed); descriptions
  ≤1000, blank → none.
- **Index**: New button reveals a name+description row; rows show the description under
  the name plus quick actions — **Share** (row-level opener carrying its own
  `data-share-url` `/list?movie=…&name=…` snapshot, ids capped at 100 in position order,
  hidden when empty; `marked.js` copies it onto the `#share` dialog before rendering the
  QR) and **Delete** (`hx-confirm`-guarded, lands back on the index).
- **Detail**: description + rename pencils (in-place forms), cards with per-membership
  notes, **Reorder** mode (arrows post `move` with `dir=up|down`, wrapping at the edges,
  server swaps positions, client mirrors the DOM move; the share URL's movie params follow
  the live order), Share/Print/Rows/Delete chips.
- **Add-to-playlist dialog** (`GET /playlists/select?movie=…`): single movie →
  membership-toggle rows (hairline rows like the index: name left, accent check for
  members, muted `+` for addable; the membership note rides inside the row, editable);
  multiple movies → bulk-add picker (hidden movie set rides along via `hx-include`, so
  several adds work without re-opening). Bulk adds and bulk-mode creates keep the dialog
  open with a confirmation + Open playlist link. Creating from the dialog includes the
  movie(s) immediately; new memberships start bare — notes are never inherited.
- **Compose workflow** — from `/marked`, adding/creating is a **move**: the marks clear
  (marks are the staging inbox, playlists the destination); movie notes stay on the movie
  pages. From shared lists it stays a copy.
- Membership is independent of marks everywhere else: unmarking never removes from a
  playlist.
- Navigation mutations: `HX-Redirect` for fetch/dialog flows, 303 for plain/boosted forms;
  bulk dialog add/create re-renders the fragment in place instead.

## Footer, errors, static assets

- Every page carries the TMDB attribution footer (vendored logo, link to themoviedb.org) —
  required by TMDB's terms.
- Errors: 404 (not found / bad department) with the search box front and center; 503
  ("temporarily unavailable") page or fragment; 500 generic. Never raw TMDB/internal
  errors.
- Static assets served with `Cache-Control: no-cache` (always revalidate, ETag 304s) —
  explicitly required: Javalin's default static headers let browsers apply heuristic
  caching and serve stale JS against fresh markup, which breaks the dialogs and overlay
  taps.
- JS is vendored `htmx.min.js` (2.0.4) + `search.js` + `marked.js`, loaded deferred;
  `qrcode.js` (vendored qrcode-generator) only on pages with a Share dialog. No CDN
  references (sole exception: the trailers-dialog YouTube iframe).