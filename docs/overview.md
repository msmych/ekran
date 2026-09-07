# Overview

**Project:** Quick Movie Search (working repo: `ekran`)
**Status:** Step 1 (TMDB-backed MVP) — pre-implementation spec
**Source:** `quick_movie_search_agent_brief.docx` (executive brief) — this document expands it into an implementable spec.

## What we are building

A fast, no-bloat movie discovery web application. The primary interaction:

> open the site → search immediately → results appear as you type → open a movie → inspect essential details → jump directly to a director/actor/writer filmography.

Step 1 uses **TMDB** as the external data source and a lightweight **Java server (Javalin, no Spring)** with **server-rendered HTML + HTMX**. The architecture must leave room for a PostgreSQL-backed local data store and additional clients later (Step 2).

This is **not** a Letterboxd/IMDb replacement. No social layer, reviews, feeds, accounts, recommendations, or advertising in the MVP.

## Locked decisions

| Decision | Choice |
|---|---|
| Build tool | Gradle (application plugin) |
| JVM | Java 25 (LTS) |
| Root package | `uk.matvey.ekran` |
| Web framework | Javalin (no Spring) |
| Template engine | Thymeleaf (server-side rendering) |
| Frontend | Server-rendered HTML + HTMX (vendored locally) + one ~40-line first-party `search.js`, basic no-frills UI in Step 1 |
| Search from any page | Search bar on every page: live in-page on the homepage, compact overlay panel (Wikipedia-style) elsewhere; `/` and Cmd/Ctrl+K focus it from anywhere |
| TMDB auth | v4 Bearer token (`Authorization: Bearer …`), env var only |
| Search scope | **Movies only** in Step 1; persons search is a follow-up step |
| Route naming | Plural resources: `/movies/{id}`, `/persons/{id}` |
| Logging | SLF4J + Logback |
| Persistence | None in MVP (repository abstraction only) |

## Primary success criterion

The user can go from an empty browser tab to the relevant movie/person information with almost no waiting or interaction overhead.

## MVP scope

- Homepage is a search-first interface with the search input automatically focused.
- Search-as-you-type with ~100 ms debounce, returning an HTML fragment.
- Movie detail page: title, release date/year, runtime, genres, rating, overview, director, principal cast, writers, poster.
- Person links are navigable from movie pages.
- Person page with filmography, filterable by department (directing / acting / writing).
- Deep-linkable URLs for movies, people, and searches.
- Responsive layout for desktop and mobile.

## Explicit non-goals for MVP

- No Spring / Spring Boot.
- No React / Vue / Angular.
- No PostgreSQL dependency (only a clean repository boundary).
- No user ratings, reviews, watchlists, social features, recommendations, feeds.
- No streaming-provider aggregation unless effectively free from the core implementation.
- No attempt to mirror the complete TMDB data model.
- No user accounts or persistent user-specific state.

## Deferred to a follow-up step (explicitly out of Step 1)

- **Persons in search results** — the search box returns movies only; we'll design how to blend people into results (or a separate persons search) as the next step. Person pages/filmographies remain in scope and are reachable from movie pages.
- **Keyboard result navigation** — skipped for now; approach to be designed and discussed separately.
- **UI polish** — deliberately basic UI in Step 1; visual direction to be figured out later.

## Step 2 direction (not MVP work)

PostgreSQL-backed local movie/person/credit store, local full-text/trigram search for near-instant common searches, background TMDB refresh + freshness policy, caching of frequently viewed entities, potential public JSON API layer, potential additional metadata providers. The repository and domain boundaries in this spec are designed so Step 2 requires **no changes above the repository layer**.

## Definition of done (Step 1)

- [x] Application starts from a clean checkout with documented configuration.
- [x] Opening `/` presents a focused search input.
- [x] Typing a movie title produces useful results without submitting a form.
- [x] Movie detail pages are compact and readable.
- [x] Director/actor/writer names are clickable.
- [x] Person pages show useful filmographies with department sections.
- [x] Canonical URLs can be copied/bookmarked directly.
- [x] No Spring dependency.
- [x] No frontend framework dependency.
- [x] TMDB integration is isolated behind a dedicated adapter.
- [x] Automated tests cover the important application logic; no test requires a real API key.
- [x] README explains setup, TMDB credentials, run/test commands, and the architecture.