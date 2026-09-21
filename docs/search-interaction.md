# Search Interaction

The search box is the product. Spec of the exact interaction contract between browser, HTMX, and server.

## Markup skeleton

The input lives in a shared fragment (`searchbar.html`, `searchbar(live)`) rendered on **every page**:

```html
<div th:class="${live} ? 'home-search' : 'site-search'">
    <input type="search"
           id="search-input"
           name="q"
           th:placeholder="${live and personSearch} ? 'Search people…' : 'Search movies…'"
           autocomplete="off"
           spellcheck="false"
           hx-get="/search"
           hx-trigger="input changed delay:100ms, search"
           hx-sync="this: replace"
           hx-indicator="#search-indicator"
           th:attr="hx-target=${live} ? '#results' : '#search-overlay',
                    hx-include=${live} ? '#search-type',
                    autofocus=${live} ? 'autofocus' : null">
    <span id="search-indicator" class="search-indicator" aria-hidden="true"></span>
    <!-- Cmd+K tip only on the compact overlay search — the home input is
         already focused and prominent, there is nothing to advertise -->
    <span class="search-kbd" id="search-kbd" aria-hidden="true" th:if="${!live}">⌘K</span>
    <input type="hidden" id="search-type" name="type" th:if="${live}"
           th:value="${personSearch} ? 'person' : ''">
    <div class="search-toggle" th:if="${live}">
        <button type="button" data-search-type="" th:classappend="${!personSearch} ? 'active'">Movies</button>
        <button type="button" data-search-type="person" th:classappend="${personSearch} ? 'active'">People</button>
    </div>
    <div id="search-overlay" class="search-overlay" th:if="${!live}"></div>
</div>
```

(The input also carries `th:value="${q}"` for the homepage deep-link `/?q=…`.)

**Movies/People toggle (home only):** `search.js` handles clicks on `[data-search-type]` buttons — it flips the hidden `#search-type` value, re-applies the active pill state, switches the placeholder, and re-fires the search by dispatching the input's bare `search` trigger (which has no `changed` gate, so it runs even though the query itself didn't change). Only fires when the query box is non-empty. The server maps `type=person` to TMDB `/search/person`; anything else (or absent) is a movie search. The header overlay search has no toggle — movie-only.

Two modes, one fragment — both are live search into a target; only the target and layout differ:

- **Live** (`searchbar(true)`, homepage): full-width input in the page flow; results swap into `#results` below and push the page downward.
- **Overlay** (`searchbar(false)`, all other pages incl. error pages): a compact input tucked into the top-right of the header row. It expands on focus, and results drop into `#search-overlay` — an absolutely-positioned panel under the input, Wikipedia-style. The page behind never moves; **changing your mind (Escape or click-away) leaves you exactly where you were**. Clicking a result navigates normally (boosted, with the topbar spinner).

Overlay visibility is pure CSS: the panel shows only while the search box area has focus (`:focus-within`) **and** it has content (`:not(:empty)`). The blank-query response is a truly empty body — not a whitespace-only fragment, since whitespace text nodes count as content and would render a blank bordered pane. Escape and click-away blur the input (handled in `search.js`, see below), which collapses the panel via the same CSS.

Every attribute has a job:

| Attribute | Purpose |
|---|---|
| `autofocus` | Search-first homepage; ready to type immediately, no JS needed (live mode only) |
| `hx-trigger="input changed delay:100ms, search"` | Debounce (~100 ms, within the brief's 100–200 ms window); each keystroke restarts the timer, so rapid intermediate states are intentionally collapsed — only the value after a pause is queried (the final typed state always fires). `search` event fires on Enter so an explicit submit also works and is instant |
| `hx-sync="this: replace"` | If a request is in flight, a new keystroke **aborts it** and replaces it with the new request — stale responses cannot overwrite newer results, and the newest typed state is always eventually requested |
| `hx-get="/search"` | Both modes hit the same fragment endpoint (`HX-Request: true` → results-only fragment) |
| `hx-target` | Live: `#results` in the page flow. Overlay: `#search-overlay` panel (default `innerHTML` swap) |
| `hx-include="#search-type"` (live only) | Carries the Movies/People toggle's hidden `type` value along with every search request |

Because `hx-sync="this: replace"` cancels stale requests at the client, the server needs no request-tagging/sequence machinery in MVP. (Server-side in-flight dedup and caching is a Step 2 concern at the repository layer.)

Note the strategy choice: htmx's `abort` is **not** "new request aborts the old one" — its documented semantics are "drop (ignore) this request if an existing request is in flight", so with a debounced trigger the final typed state could be silently dropped whenever a previous request was still in flight (observed with TMDB latency > debounce gap). `replace` is the intended supersede semantics ("abort the current request, if any, and replace it with this request") and matches htmx's own active-search example.

## Hotkeys and overlay close: `search.js`

Hotkeys and overlay dismissal live in a single small file, `static/js/search.js` (~280 lines), loaded (deferred) on every page. A second first-party file, `static/js/marked.js` (~450 lines), handles the marking/share feature — see the marking section in `routes-and-views.md`. A plain `document`-level keydown listener (not an inline `hx-on` handler):

- `/` — vim/Google-style quick focus, matched via `event.code === 'Slash'` **and** `event.key === '/'` so it works on non-US keyboard layouts (`event.key` alone breaks on e.g. Cyrillic layouts, which is why the first inline-handler attempt "didn't work" in real use). Skipped while already typing in an input/textarea/select, so a `/` inside the search box is just a character.
- `Cmd+K` (macOS) / `Ctrl+K` (Windows/Linux), matched via `event.code === 'KeyK'` — the modern standard (GitHub, Slack, Notion); works even from within the input, and selects the existing query for quick replacement.
- `Escape` — overlay pages: close the panel (blur; query preserved, still on the same page). Home: clear the query and the results (`input` event dispatched so htmx refreshes the list).
- Click outside the search area — blurs the input, closing the overlay even in browsers that don't move focus on clicks into non-focusable areas (Safari).

The search bar shows a small `⌘K` / `Ctrl K` tip badge at its right edge (clickable — focuses the input) on **non-home pages only** — the home input is already focused and prominent, so there is nothing to advertise there. The label is platform-corrected by `search.js` and re-applied on every `htmx:afterSwap`, since boosted navigation re-inserts the raw template label and would otherwise reset it. The badge is hidden on mobile (`@media (pointer: coarse), (max-width: 640px)`) — there is no keyboard to advertise; the search spinner moves to the right edge in its place.

Focus restore after boosted swaps needs no JS: htmx focuses `[autofocus]` content it swaps in, so landing back on the homepage re-focuses the input.

## Mobile specifics

- Mobile Safari/Chrome auto-zoom into inputs with a font-size below 16px, so `app.css` forces `#search-input` to 16px under `@media (pointer: coarse), (max-width: 640px)` (the rule is last in the file so it wins over the compact 14px site-search sizing).
- Tapping a result in the overlay is broken twice over on touch: (1) the tap blurs the input *between* `pointerdown` and the synthesized `click`, and the resulting `:focus-within` loss hides the panel — removing the link before the click lands; (2) iOS Safari with the keyboard up swallows the first tap entirely — it only dismisses the keyboard and **never synthesizes the click**, so no panel grace window can save it. `search.js` therefore completes the tap manually: `pointerdown` arms a `.tap-through` class (keeps the panel visible past the blur, extended to 500 ms) and records the tap position; `pointerup` — which is always delivered, unlike the click — fires `target.click()` on the tapped result/button directly (unless the pointer moved >8px, which is a scroll gesture, not a tap). The click the browser may still synthesize afterwards is then swallowed by a capture-phase document listener (`preventDefault` + `stopPropagation`, `isTrusted` only, 500 ms window), so the htmx-boosted navigation and its history entry fire exactly once. `pointercancel` (long-press callout, gesture takeover) disarms the pending tap. With a mouse, `preventDefault()` on `pointerdown` (results panel and the `⌘K` badge) still keeps the focus on the input, and mouse clicks fire regardless.

## Input rules

- Queries are trimmed server-side; empty/whitespace-only → a **truly empty 200 body** (a whitespace-only fragment would defeat the CSS `:not(:empty)` panel rule). HTMX mostly won't fire for whitespace-only changes anyway (`input changed` ignores them at the same length) — but never rely on client behavior alone.
- Server additionally ignores queries < 1 char after trim and caps query length (~100 chars) → empty state, not an error.
- `type="search"` gives the right semantics and (in WebKit) native clear affordances; Escape behavior is normalized by `search.js` cross-browser (clear on home, close overlay elsewhere).

## Visual stability

- The input, its position, and everything above the fold **must not shift** when results appear: the results container sits below the input and grows downward; no layout reflow above it.
- No full-page reloads during typing: only `#results` innerHTML swaps.
- A subtle loading state (small spinner or `htmx-indicator` next to input) is allowed; it must not change layout. The dedicated search spinner (`.search-indicator`) is absolutely positioned inside the input (left of the kbd tip) — appearing as an inline sibling would widen the compact `.site-search` container and shift the input mid-typing.
- Empty results render a single calm line: "No results for 'xyz'".

## Spinner on navigation (movie/person pages)

Detail pages hit TMDB and can take a moment, so all in-app navigation shows a progress bar:

- `hx-boost="true" hx-indicator="#topbar"` on `<main>` of every full-page template and on the shared `<footer>` — HTMX converts regular links/tabs into AJAX swaps (history, back/forward, and title handled by HTMX) instead of full browser navigation.
- The search results fragment (`results.html`) carries the same attributes on its `<ul>` — **required**: after a swap, HTMX only initializes anchors that have an `[hx-boost]` ancestor *within the swapped node*. Body-level `hx-boost` covers only the initial page load, so without fragment-local attributes the result links would fall back to full page reloads with no spinner (body-level inheritance is deliberately not used to avoid this trap).
- `#topbar` is a fixed 3 px animated bar at the top of the viewport with class `htmx-indicator` — HTMX's injected styles keep it hidden until a boosted request is in flight. The search input keeps its own dedicated spinner via its own `hx-indicator`.
- HTMX does not swap 4xx/5xx responses by default, which would leave a dead link silent. A `<meta name="htmx-config">` on every page configures `responseHandling` so 404 and 503 **do swap** (the friendly error pages render in place); other error statuses keep the default no-swap behavior.
- Focus after a boosted swap back to the homepage is restored by htmx itself: it focuses `[autofocus]` elements in swapped-in content.

## Keyboard result navigation

While the search input has focus, `search.js` handles:

- `↓` / `↑` — move the highlight through results (`Ctrl N` / `Ctrl P` are aliases, readline-style). Hijacked only when results exist — with an empty list the arrows move the text caret normally. The selection clamps at the ends (no wrap) and scrolls into view.
- `Enter` — opens the highlighted result (its anchor is clicked, so boosted navigation + topbar spinner apply). With no highlight, Enter keeps its native behavior: the `search` event fires an immediate search.
- Typing clears the highlight; the fresh results arrive via the swap anyway.

The highlight is a `.selected` class on the result anchor (`background` + accent inset outline, defined in `app.css`). Result anchors stay real `<a href>` elements — middle-click, open-in-new-tab, and copy-link keep working.

## HTMX vendoring

- **Vendored: htmx 2.0.4** — committed at `src/main/resources/static/js/htmx.min.js`.
- Load with `<script defer src="/js/htmx.min.js"></script>` — defer, not async-blocking; search still works via the `search`-trigger fallback if it hasn't loaded when the user starts typing.
- No HTMX extensions (no `hyperscript`, no `htmx-ext-*`). The `input changed delay` trigger is built in.
- Other scripts: our own `static/js/search.js` (~280 lines: hotkeys, Escape, click-away, keyboard nav, dialog + video-list selection — see the hotkeys section above) and `static/js/marked.js` (mark storage/toggle, share/QR). No bundler, no framework.

## Rate and behavior notes

- One keystroke burst = one request (debounce + replace guarantee this).
- Server performs **one** TMDB call per search (movie or person, per the toggle). Failure → 503-friendly empty/error fragment; no degraded-mode machinery needed.
- No client-side caching in MVP (browser default HTTP caching only; fragment responses are sent with `Cache-Control: no-store` to avoid stale-list confusion).