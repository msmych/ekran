# Search Interaction

The search box is the product. One shared fragment (`searchbar.html`, `searchbar(live)`)
renders on every page; the markup there is the source of truth — this file is the contract.

## Modes

- **Live** (`searchbar(true)`, homepage): full-width input, results swap into `#results`.
- **Overlay** (`searchbar(false)`, every other page): compact input in the header, results
  drop into `#overlay-results` inside `#search-overlay` — an absolutely-positioned panel
  with the Movies/People pills at its top. The panel opens on `:focus-within` (CSS only),
  so the pills are visible before any results; a blank query leaves just the pills.
  Escape/click-away blurs the input and collapses it; the page never moves.
- Toggle clicks (`search.js` on `[data-search-type]`): flip the hidden `#search-type`,
  re-apply the active pill, switch the placeholder, re-fire the search through the input's
  bare `search` trigger (no `changed` gate). Server maps `type=person` → TMDB
  `/search/person`; anything else is a movie search. Person pages default to people
  (`PersonRoutes` sets `personSearch` for the page).

## Request contract

- `hx-trigger="input changed delay:100ms, search"` — debounce; `search` fires on Enter
  for an instant explicit submit.
- `hx-sync="this: replace"` — a new keystroke aborts the in-flight request and replaces
  it: stale responses can't overwrite newer results and the final typed state always
  fires. (`abort` means "drop the new request", which can silently lose the final state —
  don't switch back.)
- `hx-include="#search-type"` carries the toggle's type on every request.
- Blank/whitespace query → **truly empty 200 body** (whitespace would count as panel
  content). Queries are trimmed, capped ~100 chars server-side; empty state renders a
  calm "No results for 'xyz'" line.
- No layout shift: the results container grows downward; the spinner
  (`.search-indicator`) is absolutely positioned inside the input.

## Hotkeys (`search.js`, document-level listeners)

- `/` — focus the input (matched via `event.code` **and** `event.key`, so non-US layouts
  work); skipped while typing in a field.
- `Cmd/Ctrl+K` — focus + select the query.
- `Escape` — overlay: close the panel, keep the query; home: clear query + results.
- `↑`/`↓` (or `Ctrl N`/`Ctrl P`) — move the `.selected` highlight through results (only
  when results exist); `Enter` opens the highlighted result, otherwise fires a native
  search. Typing clears the highlight.
- `m` / `n` on the movie page — mark / edit note (physical keys, ignored while editing
  text or with modifiers).
- Result anchors stay real `<a href>` — middle-click and open-in-new-tab keep working.

## Boosted navigation

- `hx-boost="true" hx-indicator="#topbar"` on `<main>` and the footer; `#topbar` is the
  fixed 3px progress bar.
- The results fragment carries its own `hx-boost` attributes — **required**: after a
  swap, htmx only initializes anchors with an `[hx-boost]` ancestor *within the swapped
  node*, so without them result links fall back to full reloads.
- `<meta name="htmx-config">` sets `responseHandling` so 404/503 responses swap (friendly
  error pages render in place) instead of dying silently.
- Focus restore back to home is free: htmx focuses `[autofocus]` content it swaps in.

## Mobile

- `#search-input` is forced to 16px on coarse pointers — sub-16px inputs trigger
  auto-zoom in mobile Safari/Chrome.
- Overlay taps are completed manually by `search.js`: `pointerdown` arms a `.tap-through`
  grace window (the blur would otherwise hide the panel before the click lands; iOS may
  never synthesize the click at all), `pointerup` fires the click directly unless the
  pointer moved >8px, and a capture-phase listener swallows the browser's duplicate
  click so boosted navigation fires exactly once. Mouse `pointerdown` on the panel does
  `preventDefault()` (keeps focus; clicks still fire).
- The `/`-tip badge is hidden on touch; the header wraps the search to its own full-width
  row at ≤640px.