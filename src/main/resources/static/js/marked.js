(function () {
    'use strict';
    var KEY = 'ekran.markedMovies';
    var NAME_KEY = 'ekran.listName';
    var VIEW_KEY = 'ekran.cardsView';
    var NAME_MAX = 60;
    var MAX_SHARE = 100;
    var EDITABLE = /^(INPUT|TEXTAREA|SELECT)$/;
    var seedEl = document.querySelector('[data-authenticated]');
    // authenticated marks live in PostgreSQL: the server seeds the id set into
    // the header (re-rendered on every request), mutations go through /marked,
    // and localStorage is used exactly once — to migrate old anonymous marks
    // (notes are a signed-in feature, there is nothing local to migrate)
    var AUTHENTICATED = !!seedEl;
    var MARKED_PAGE = location.pathname === '/marked';
    var LIST_PAGE = location.pathname === '/list';
    var ids = AUTHENTICATED ? loadSeed() : load();
    var copyTimer = null;

    // boosted navigation swaps the page without reloading this script — the
    // flags must follow the new URL
    function syncPageFlags() {
        MARKED_PAGE = location.pathname === '/marked';
        LIST_PAGE = location.pathname === '/list';
    }

    function loadSeed() {
        var raw = seedEl ? (seedEl.getAttribute('data-marked-ids') || '') : '';
        return raw.split(',').filter(Boolean).map(Number);
    }

    // accepts both the plain format ([238, 680]) and the legacy one
    // ([{movieId: 238, note: …}] — notes once lived inside the marks list;
    // notes are a signed-in feature now, so any legacy note is dropped)
    function load() {
        try {
            var raw = JSON.parse(localStorage.getItem(KEY) || '[]');
            if (!(raw instanceof Array)) {
                return [];
            }
            var seen = {};
            var out = [];
            for (var k = 0; k < raw.length; k++) {
                var entry = raw[k];
                var id = null;
                if (typeof entry === 'number' && isFinite(entry) && Math.floor(entry) === entry && entry > 0) {
                    id = entry;
                } else if (entry && typeof entry === 'object'
                    && typeof entry.movieId === 'number' && isFinite(entry.movieId)
                    && Math.floor(entry.movieId) === entry.movieId && entry.movieId > 0) {
                    id = entry.movieId;
                }
                if (!id || seen[id]) {
                    continue;
                }
                seen[id] = true;
                out.push(id);
            }
            return out;
        } catch (e) {
            return [];
        }
    }

    function save() {
        try {
            localStorage.setItem(KEY, JSON.stringify(ids));
        } catch (e) {
            // storage unavailable (private mode/quota) — marks live only for this page
        }
    }

    function isMarked(id) {
        return ids.indexOf(id) !== -1;
    }

    // optimistic flip; on a failed mutation the id goes back and the UI re-syncs
    function toggle(id) {
        var at = ids.indexOf(id);
        var marked = at === -1;
        if (marked) {
            ids.push(id);
        } else {
            ids.splice(at, 1);
        }
        if (AUTHENTICATED) {
            persistMark(id, marked);
        } else {
            save();
        }
    }

    function persistMark(id, marked) {
        fetch('/marked/' + id, { method: marked ? 'POST' : 'DELETE' })
            .then(function (response) {
                if (!response.ok) {
                    revert(id, marked);
                }
            })
            .catch(function () {
                revert(id, marked);
            });
    }

    function revert(id, marked) {
        var at = ids.indexOf(id);
        if (marked && at !== -1) {
            ids.splice(at, 1);
        } else if (!marked && at === -1) {
            ids.push(id);
        }
        if (!marked && MARKED_PAGE) {
            var card = document.querySelector('.movie-cards li[data-movie-id="' + id + '"]');
            if (!card) {
                addCard(id);
            }
        }
        refresh();
    }

    function persistBulk(movieIds, method, done) {
        var body = new URLSearchParams();
        movieIds.forEach(function (id) {
            body.append('movie', id);
        });
        fetch('/marked', { method: method, body: body })
            .then(function (response) {
                if (response.ok) {
                    done();
                }
            })
            .catch(function () {
            });
    }

    // one-time migration: anonymous marks (this browser's localStorage) are
    // merged into the account; localStorage is cleared only after the server
    // confirmed, so a failed merge keeps everything for the next attempt
    // (idempotent union)
    function migrateLocalMarks() {
        var local = load();
        if (!local.length) {
            return;
        }
        fetch('/marked/migrate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                movies: local.map(function (id) {
                    return { movieId: id };
                })
            })
        })
            .then(function (response) {
                if (!response.ok) {
                    return;
                }
                try {
                    localStorage.removeItem(KEY);
                    localStorage.removeItem(NAME_KEY);
                } catch (e) {
                }
                local.forEach(function (id) {
                    if (ids.indexOf(id) === -1) {
                        ids.push(id);
                    }
                });
                refresh();
                toast(local.length + ' marked movie' + (local.length === 1 ? '' : 's') + ' saved to your account.');
            })
            .catch(function () {
            });
    }

    function toast(message) {
        var el = document.createElement('div');
        el.className = 'app-toast';
        el.setAttribute('role', 'status');
        el.textContent = message;
        document.body.appendChild(el);
        setTimeout(function () {
            el.remove();
        }, 4000);
    }

    function storedName() {
        var value;
        try {
            value = localStorage.getItem(NAME_KEY);
        } catch (e) {
            return null;
        }
        value = value == null ? '' : value.trim();
        return value ? value.slice(0, NAME_MAX) : null;
    }

    function urlName() {
        var value = new URLSearchParams(location.search).get('name');
        value = value == null ? '' : value.trim();
        return value ? value.slice(0, NAME_MAX) : null;
    }

    function listUrl() {
        var qs = ids.slice(0, MAX_SHARE).map(function (id) {
            return 'movie=' + id;
        }).join('&');
        if (!qs) {
            return '/list';
        }
        var name = storedName();
        return name ? '/list?' + qs + '&name=' + encodeURIComponent(name) : '/list?' + qs;
    }

    // the shareable snapshot of the current set: a dialog may carry its own
    // data-share-url (the playlist page shares its /list URL); on /marked that is
    // the /list URL built from the ids (the /marked URL itself carries no state);
    // on /list the URL already mirrors the view, so it shares itself
    function shareUrl() {
        var explicit = document.querySelector('[data-share-url]');
        if (explicit) {
            var url = explicit.getAttribute('data-share-url');
            return url && url.charAt(0) === '/' ? location.origin + url : url;
        }
        return MARKED_PAGE ? location.origin + listUrl() : location.href;
    }

    function refresh() {
        var link = document.querySelector('[data-marked-link]');
        if (link) {
            var count = link.querySelector('[data-marked-count]');
            if (count) {
                count.textContent = ids.length;
            }
            // hidden until the first mark — done from JS, never in the markup,
            // so a stale/failed script can never hide the entry point.
            // authenticated: the href stays /marked (the account view);
            // anonymous: the href IS the list, built from local marks
            link.hidden = ids.length === 0;
            if (!AUTHENTICATED) {
                link.setAttribute('href', listUrl());
            }
        }
        var button = document.querySelector('[data-mark-button]');
        if (button) {
            var marked = isMarked(Number(button.getAttribute('data-movie-id')));
            button.classList.toggle('marked', marked);
            button.setAttribute('aria-pressed', marked ? 'true' : 'false');
            button.setAttribute('aria-label', (marked ? 'Unmark' : 'Mark') + ' movie (m)');
        }
        // every card-like mark control (list cards, person filmography, search results)
        Array.prototype.forEach.call(document.querySelectorAll('[data-card-mark]'), function (el) {
            var cardMarked = isMarked(Number(el.getAttribute('data-card-mark')));
            el.classList.toggle('marked', cardMarked);
            el.setAttribute('aria-pressed', cardMarked ? 'true' : 'false');
            el.setAttribute('aria-label', cardMarked ? 'Unmark movie' : 'Mark movie');
        });
        syncListView();
    }

    // the note fragments render with the page icon everywhere; the movie
    // page additionally advertises the `n` hotkey in the label — this
    // re-applies that to any area swapped into the movie note slot (save,
    // cancel, boosted navigation)
    function movieNoteAffordance() {
        var row = document.querySelector('.movie-note');
        if (!row) {
            return;
        }
        var area = row.querySelector('.card-note');
        if (!area || area.classList.contains('card-note-form')) {
            return;
        }
        var toggle = area.querySelector('.card-note-toggle');
        if (!toggle) {
            return;
        }
        if (area.querySelector('.card-note-text')) {
            toggle.classList.add('has-note');
            toggle.setAttribute('aria-label', 'Edit note (n)');
            toggle.title = 'Edit note (n)';
        } else {
            toggle.classList.remove('has-note');
            toggle.setAttribute('aria-label', 'Add note (n)');
            toggle.title = 'Add note (n)';
        }
    }

    // grid ⇄ rows: the same card markup, a container attribute switches the
    // layout; the choice is remembered across pages and sessions. A
    // server-rendered view (the notes page defaults to rows) wins over the
    // stored preference — only the toggle label syncs, the chip still works
    function applyCardsView() {
        var toggleButton = document.querySelector('[data-view-toggle]');
        var cards = document.querySelector('.movie-cards');
        if (!toggleButton || !cards) {
            return;
        }
        if (cards.getAttribute('data-view')) {
            setCardsView(toggleButton, cards, cards.getAttribute('data-view') === 'rows');
            return;
        }
        var rows = false;
        try {
            rows = localStorage.getItem(VIEW_KEY) === 'rows';
        } catch (e) {
        }
        setCardsView(toggleButton, cards, rows);
    }

    function setCardsView(toggleButton, cards, rows) {
        cards.setAttribute('data-view', rows ? 'rows' : 'grid');
        toggleButton.textContent = rows ? 'Grid' : 'Rows';
        toggleButton.setAttribute('aria-pressed', String(rows));
    }

    function toggleCardsView(toggleButton) {
        var cards = document.querySelector('.movie-cards');
        if (!cards) {
            return;
        }
        var rows = cards.getAttribute('data-view') !== 'rows';
        setCardsView(toggleButton, cards, rows);
        try {
            localStorage.setItem(VIEW_KEY, rows ? 'rows' : 'grid');
        } catch (e) {
        }
    }

    // /list is URL-driven, so it may show a shared list that differs from the
    // local marks: "Marked movies" + Clear all when every card is marked,
    // "Shared list" + Add all to marked otherwise; a custom name (URL param or,
    // for your own list, the stored one) always wins over the default title
    function syncListView() {
        if (MARKED_PAGE) {
            // /marked is always your own set: actions depend only on card presence
            var markedEmpty = visibleCardIds().length === 0;
            setHidden('[data-dialog="share"]', markedEmpty);
            setHidden('[data-print-list]', markedEmpty);
            setHidden('[data-clear-marks]', markedEmpty);
            var markedEmptyState = document.querySelector('.empty-list');
            if (markedEmptyState) {
                markedEmptyState.hidden = !markedEmpty;
            }
            return;
        }
        var title = document.querySelector('[data-list-title]');
        if (!title) {
            return;
        }
        var cardIds = visibleCardIds();
        var empty = cardIds.length === 0;
        var yours = empty || isOwnList();
        var name = urlName() || (yours && !empty ? storedName() : null);
        // title.textContent would wipe the pencil (it lives inside the h2) —
        // refresh only the text span
        var titleText = title.querySelector('[data-list-title-text]');
        if (titleText) {
            titleText.textContent = name || (yours ? 'Marked movies' : 'Shared list');
        }
        setHidden('[data-dialog="share"]', empty);
        setHidden('[data-print-list]', empty);
        setHidden('[data-mark-all]', empty || yours);
        setHidden('[data-clear-marks]', empty || !yours);
        setHidden('[data-list-name-edit]', empty);
        var emptyState = document.querySelector('.empty-list');
        if (emptyState) {
            emptyState.hidden = empty;
        }
        // keep your list's URL carrying its name, so copied/shared links include it
        if (isOwnList() && storedName() && !urlName()) {
            syncListUrl();
        }
    }

    // your list only when the visible card set IS your marked set — a subset
    // of your own marks (all cards marked, but you have more) still counts
    // as a shared view and offers Add all to marked
    function isOwnList() {
        var cardIds = visibleCardIds();
        return cardIds.length > 0 && cardIds.length === ids.length && cardIds.every(isMarked);
    }

    function visibleCardIds() {
        return Array.prototype.map.call(
            document.querySelectorAll('.movie-cards li[data-movie-id]'),
            function (li) {
                return Number(li.getAttribute('data-movie-id'));
            });
    }

    function setHidden(selector, hidden) {
        var el = document.querySelector(selector);
        if (el) {
            el.hidden = hidden;
        }
    }

    // the narrow-screen ⋯ list menu: close the panel and sync its toggle
    function closeListMenu() {
        var open = document.querySelector('.list-actions[data-open]');
        if (!open) {
            return;
        }
        open.removeAttribute('data-open');
        var toggle = open.parentElement.querySelector('[data-list-menu]');
        if (toggle) {
            toggle.setAttribute('aria-expanded', 'false');
        }
    }

    // playlists page + picker dialog: fold the create form away, bring the
    // New button back (its focus — the flow continues from there)
    function closeNewPlaylistForm() {
        var newForm = document.querySelector('[data-new-playlist-form]');
        if (newForm) {
            newForm.hidden = true;
        }
        var newPlaylistButton = document.querySelector('[data-new-playlist]');
        if (newPlaylistButton) {
            newPlaylistButton.hidden = false;
            newPlaylistButton.focus();
        }
    }

    // playlist page: the description form replaces the description text in
    // place until saved or cancelled — the text (or its placeholder) starts
    // the edit too, like the title rename
    function startDescEdit() {
        var descForm = document.querySelector('[data-desc-form]');
        if (!descForm) {
            return;
        }
        var descText = document.querySelector('[data-desc-text]');
        if (descText) {
            descText.hidden = true;
        }
        var descToggle = document.querySelector('[data-desc-toggle]');
        if (descToggle) {
            descToggle.hidden = true;
        }
        descForm.hidden = false;
        descForm.querySelector('textarea').focus();
    }

    function syncListUrl() {
        if (!LIST_PAGE) {
            return;
        }
        var params = new URLSearchParams();
        visibleCardIds().forEach(function (id) {
            params.append('movie', id);
        });
        var name = urlName() || storedName();
        if (name && params.has('movie')) {
            params.set('name', name);
        }
        var qs = params.toString();
        // the URL always mirrors the rendered view, so a refresh does not
        // resurrect removed cards; the QR encodes the same URL
        history.replaceState(null, '', qs ? '/list?' + qs : '/list');
    }

    function removeCard(card) {
        card.remove();
        syncListUrl();
    }

    // a movie marked from the search overlay while viewing /list or /marked joins
    // the view right away — the card is fetched from the /list/card fragment
    function addCard(id) {
        fetch('/list/card?movie=' + id)
            .then(function (response) {
                return response.ok ? response.text() : '';
            })
            .then(function (html) {
                if (!html) {
                    return;
                }
                var list = document.querySelector('.movie-cards');
                if (!list || list.querySelector('li[data-movie-id="' + id + '"]')) {
                    return;
                }
                list.insertAdjacentHTML('beforeend', html);
                // the fragment has no note area; on the marked page the card
                // needs the movie's note affordance, fetched in like on the
                // movie page (the note is detached from the mark — it is
                // there whether or not the movie stays marked)
                if (MARKED_PAGE && AUTHENTICATED) {
                    var li = list.querySelector('li[data-movie-id="' + id + '"]');
                    if (li) {
                        fetch('/movies/' + id + '/note')
                            .then(function (response) {
                                return response.ok ? response.text() : '';
                            })
                            .then(function (noteHtml) {
                                if (noteHtml && li.isConnected) {
                                    li.querySelector('.card-details').insertAdjacentHTML('beforeend', noteHtml);
                                    if (window.htmx) {
                                        window.htmx.process(li);
                                    }
                                }
                            })
                            .catch(function () {
                            });
                    }
                }
                syncListUrl();
                refresh();
            })
            .catch(function () {
            });
    }

    function startNameEdit() {
        var input = document.querySelector('[data-list-name-input]');
        var title = document.querySelector('[data-list-title]');
        if (!input || !title || !input.hidden) {
            return;
        }
        // no point renaming an empty list (the old pencil button was hidden for it too)
        if (visibleCardIds().length === 0) {
            return;
        }
        input.hidden = false;
        title.hidden = true;
        input.value = urlName() || (isOwnList() ? storedName() : '') || '';
        input.focus();
        input.select();
    }

    function commitNameEdit(cancelled) {
        var input = document.querySelector('[data-list-name-input]');
        if (!input || input.hidden) {
            return;
        }
        input.hidden = true;
        var title = document.querySelector('[data-list-title]');
        if (title) {
            title.hidden = false;
        }
        if (cancelled) {
            refresh();
            return;
        }
        var name = input.value.trim().slice(0, NAME_MAX) || null;
        var yours = isOwnList();
        if (yours) {
            try {
                if (name) {
                    localStorage.setItem(NAME_KEY, name);
                } else {
                    localStorage.removeItem(NAME_KEY);
                }
            } catch (e) {
                // storage unavailable — the name still travels in the URL
            }
        }
        var params = new URLSearchParams(location.search);
        if (name) {
            params.set('name', name);
        } else {
            params.delete('name');
        }
        var qs = params.toString();
        history.replaceState(null, '', qs ? '/list?' + qs : '/list');
        refresh();
    }

    function copyUrl(url) {
        copyToClipboard(url, function () {
            var button = document.querySelector('[data-qr-copy]');
            if (!button) {
                return;
            }
            // confirmation lives in the button itself — a separate feedback line
            // would resize (jump) the dialog
            button.textContent = 'Copied';
            button.classList.add('copied');
            clearTimeout(copyTimer);
            copyTimer = setTimeout(function () {
                button.textContent = 'Copy';
                button.classList.remove('copied');
            }, 1500);
        });
    }

    function copyToClipboard(url, done) {
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(url).then(done, done);
        } else {
            done();
        }
    }

    // the share dialog always encodes the current URL, so the QR is rendered
    // fresh on every dialog open — the generic opener in search.js shows the
    // modal, these branches just fill it
    function renderQr() {
        var target = document.querySelector('[data-qr-target]');
        if (!target || typeof qrcode === 'undefined') {
            return;
        }
        var url = shareUrl();
        var qr = qrcode(0, 'M');
        qr.addData(url);
        qr.make();
        target.innerHTML = qr.createSvgTag({ cellSize: 4, margin: 2, scalable: true });
        var urlText = document.querySelector('[data-qr-url-text]');
        if (urlText) {
            urlText.textContent = url;
            urlText.setAttribute('href', url);
        }
    }

    // one delegated listener for every marking/list interaction
    document.addEventListener('click', function (event) {
        if (!(event.target instanceof Element)) {
            return; // document/text nodes have no closest
        }
        // narrow screens: the list actions live behind the ⋯ toggle — open/
        // close it; any other click (a chip inside, or away) closes the panel
        var listMenuToggle = event.target.closest('[data-list-menu]');
        if (listMenuToggle) {
            var listMenu = listMenuToggle.parentElement.querySelector('.list-actions');
            if (listMenu) {
                var open = !listMenu.hasAttribute('data-open');
                if (open) {
                    listMenu.setAttribute('data-open', '');
                } else {
                    listMenu.removeAttribute('data-open');
                }
                listMenuToggle.setAttribute('aria-expanded', String(open));
            }
            return;
        }
        closeListMenu();
        var shareOpener = event.target.closest('[data-dialog="share"]');
        if (shareOpener) {
            renderQr();
            return;
        }
        var copyButton = event.target.closest('[data-qr-copy]');
        if (copyButton) {
            copyUrl(shareUrl());
            return;
        }
        var titleEdit = event.target.closest('[data-list-title]');
        if (titleEdit) {
            startNameEdit();
            return;
        }
        var button = event.target.closest('[data-mark-button]');
        if (button) {
            var movieId = Number(button.getAttribute('data-movie-id'));
            toggle(movieId);
            // the movie note is independent of the mark — the note slot
            // needs no syncing on mark toggles
            refresh();
            return;
        }
        var cardMark = event.target.closest('[data-card-mark]');
        if (cardMark) {
            var id = Number(cardMark.getAttribute('data-card-mark'));
            var wasMarked = isMarked(id);
            // computed BEFORE the toggle — after it the sets differ by design;
            // only the user's own view — /marked, or a /list that mirrors the
            // mark set exactly — tracks the mark set: playlist and shared
            // pages keep their cards, membership there is mark-independent
            var ownView = MARKED_PAGE || (LIST_PAGE && isOwnList());
            toggle(id);
            if (ownView) {
                var card = document.querySelector('.movie-cards li[data-movie-id="' + id + '"]');
                if (card) {
                    if (wasMarked) {
                        removeCard(card);
                    }
                } else if (!wasMarked) {
                    addCard(id);
                }
            }
            refresh();
            return;
        }
        var addAll = event.target.closest('[data-mark-all]');
        if (addAll) {
            var newIds = visibleCardIds().filter(function (id) {
                return !isMarked(id);
            });
            if (!newIds.length) {
                refresh();
                return;
            }
            if (AUTHENTICATED) {
                persistBulk(newIds, 'POST', function () {
                    newIds.forEach(function (id) {
                        if (ids.indexOf(id) === -1) {
                            ids.push(id);
                        }
                    });
                    refresh();
                });
            } else {
                newIds.forEach(function (id) {
                    ids.push(id);
                });
                save();
                refresh();
            }
            return;
        }
        var viewToggle = event.target.closest('[data-view-toggle]');
        if (viewToggle) {
            toggleCardsView(viewToggle);
            return;
        }
        var clearAll = event.target.closest('[data-clear-marks]');
        if (clearAll) {
            // clearing is instant and irreversible — the browser confirm guards it
            if (!window.confirm('Clear all marked movies from this list?')) {
                return;
            }
            if (AUTHENTICATED) {
                persistBulk(ids.slice(), 'DELETE', function () {
                    ids = [];
                    document.querySelectorAll('.movie-cards li').forEach(function (li) {
                        li.remove();
                    });
                    try {
                        localStorage.removeItem(NAME_KEY);
                    } catch (e) {
                    }
                    if (!MARKED_PAGE) {
                        history.replaceState(null, '', '/list');
                    }
                    refresh();
                });
                return;
            }
            // the anonymous clear drops every visible mark in one write, not one per
            // card — the movie notes are detached, they stay
            var removedIds = visibleCardIds().filter(isMarked);
            ids = ids.filter(function (id) {
                return removedIds.indexOf(id) === -1;
            });
            save();
            document.querySelectorAll('.movie-cards li').forEach(function (li) {
                li.remove();
            });
            try {
                localStorage.removeItem(NAME_KEY);
            } catch (e) {
            }
            history.replaceState(null, '', '/list');
            refresh();
            return;
        }
        var print = event.target.closest('[data-print-list]');
        if (print) {
            window.print();
        }
        // playlists page + the picker dialog: the New button reveals the create
        // form and hides itself — Cancel/Escape brings it back
        var newPlaylist = event.target.closest('[data-new-playlist]');
        if (newPlaylist) {
            var newForm = document.querySelector('[data-new-playlist-form]');
            if (newForm) {
                newPlaylist.hidden = true;
                newForm.hidden = false;
                var newInput = newForm.querySelector('input');
                newInput.focus();
            }
            return;
        }
        var newPlaylistCancel = event.target.closest('[data-new-playlist-cancel]');
        if (newPlaylistCancel) {
            closeNewPlaylistForm();
            return;
        }
        // playlist page: the rename form replaces the title in place until
        // saved or cancelled — the heading hides so the form takes its slot
        var renameToggle = event.target.closest('[data-rename-toggle]');
        if (renameToggle) {
            var form = document.querySelector('[data-rename-form]');
            if (form) {
                var heading = renameToggle.closest('h2');
                if (heading) {
                    heading.hidden = true;
                }
                form.hidden = false;
                var renameInput = form.querySelector('input');
                renameInput.focus();
                renameInput.select();
            }
            return;
        }
        // playlist page: the description form replaces the description text
        // in place until saved or cancelled — same pattern as the rename;
        // the text itself starts the edit too (the placeholder counts as
        // the text), like the title rename
        var descToggle = event.target.closest('[data-desc-toggle], .playlist-description');
        if (descToggle) {
            startDescEdit();
            return;
        }
        // playlist page: Reorder reveals the per-card move arrows until
        // toggled off — the same button becomes Done while the mode is on
        var reorderToggle = event.target.closest('[data-reorder-toggle]');
        if (reorderToggle) {
            var reorderSection = reorderToggle.closest('.marked-list');
            if (reorderSection) {
                var reordering = !reorderSection.hasAttribute('data-reordering');
                if (reordering) {
                    reorderSection.setAttribute('data-reordering', '');
                    reorderToggle.textContent = 'Done';
                } else {
                    reorderSection.removeAttribute('data-reordering');
                    reorderToggle.textContent = 'Reorder';
                }
            }
        }
    });

    // `m` toggles the displayed movie, `n` edits its note; physical key codes
    // so they also work on non-Latin keyboard layouts; never while editing text
    document.addEventListener('keydown', function (event) {
        if (!(event.target instanceof Element)) {
            return; // e.g. keydown with nothing focused (target = document)
        }
        var nameInput = event.target.closest('[data-list-name-input]');
        if (nameInput) {
            if (event.key === 'Enter') {
                event.preventDefault();
                commitNameEdit(false);
            } else if (event.key === 'Escape') {
                commitNameEdit(true);
            }
            return;
        }
        var newPlaylistForm = event.target.closest('[data-new-playlist-form]');
        if (newPlaylistForm) {
            if (event.key === 'Escape') {
                closeNewPlaylistForm();
            }
            return;
        }
        var renameForm = event.target.closest('[data-rename-form]');
        if (renameForm) {
            if (event.key === 'Escape') {
                renameForm.hidden = true;
                var renameHeading = document.querySelector('.list-head h2');
                if (renameHeading) {
                    renameHeading.hidden = false;
                    var renameToggle = renameHeading.querySelector('[data-rename-toggle]');
                    if (renameToggle) {
                        renameToggle.focus();
                    }
                }
            }
            return;
        }
        // Escape exits the playlist description edit like the other inline modes
        var descForm = event.target.closest('[data-desc-form]');
        if (descForm) {
            if (event.key === 'Escape') {
                descForm.hidden = true;
                var descText = document.querySelector('[data-desc-text]');
                if (descText) {
                    descText.hidden = false;
                }
                var descToggle = document.querySelector('[data-desc-toggle]');
                if (descToggle) {
                    descToggle.hidden = false;
                    descToggle.focus();
                }
            }
            // ⌘/Ctrl+Enter saves, like the note editors
            if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
                event.preventDefault();
                descForm.dispatchEvent(new Event('submit', {bubbles: true, cancelable: true}));
            }
            return;
        }
        // Escape cancels the htmx note editor — its [data-note-cancel] ✕ GETs
        // the display area back
        var noteForm = event.target.closest('.card-note-form');
        if (noteForm) {
            if (event.key === 'Escape') {
                event.preventDefault();
                var noteCancel = noteForm.querySelector('[data-note-cancel]');
                if (noteCancel) {
                    noteCancel.click();
                }
            }
            // ⌘/Ctrl+Enter saves: a bubbling submit reaches htmx's handler
            if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
                event.preventDefault();
                noteForm.dispatchEvent(new Event('submit', {bubbles: true, cancelable: true}));
            }
            return;
        }
        // Escape also closes the narrow-screen ⋯ list menu — but a native
        // <dialog> open on top takes precedence (Escape closes it)
        var openListMenu = document.querySelector('.list-actions[data-open]');
        if (openListMenu && event.key === 'Escape' && !document.querySelector('dialog[open]')) {
            closeListMenu();
            var listMenuToggle = openListMenu.parentElement.querySelector('[data-list-menu]');
            if (listMenuToggle) {
                listMenuToggle.focus();
            }
            return;
        }
        // Escape also exits reorder mode, like the other inline modes — but a native
        // <dialog> open on top of the list takes precedence (Escape closes it)
        var reorderSection = document.querySelector('[data-reordering]');
        if (reorderSection && event.key === 'Escape' && !document.querySelector('dialog[open]')) {
            reorderSection.removeAttribute('data-reordering');
            var reorderToggle = document.querySelector('[data-reorder-toggle]');
            if (reorderToggle) {
                reorderToggle.textContent = 'Reorder';
                reorderToggle.focus();
            }
            return;
        }
        if (event.metaKey || event.ctrlKey || event.altKey) {
            return;
        }
        var active = document.activeElement;
        if (active && (EDITABLE.test(active.tagName) || active.isContentEditable)) {
            return;
        }
        // `m` toggles the displayed movie, `n` edits its note — physical key
        // codes so both also work on non-Latin keyboard layouts; never while
        // editing text. `n` clicks the movie page's note toggle; once the
        // editor is open the toggle is gone and the key is a no-op
        if (event.code === 'KeyM') {
            var button = document.querySelector('[data-mark-button]');
            if (button) {
                event.preventDefault();
                button.click();
            }
            return;
        }
        if (event.code === 'KeyN') {
            var noteToggle = document.querySelector('.movie-note .card-note-toggle');
            if (noteToggle) {
                event.preventDefault();
                noteToggle.click();
            }
        }
    });

    // committing the list name on blur (focusout bubbles; blur does not)
    document.addEventListener('focusout', function (event) {
        if (event.target instanceof Element && event.target.closest('[data-list-name-input]')) {
            commitNameEdit(false);
        }
    });

    // playlist reorder: the move button's POST swaps positions on the server
    // (wrapping at the edges) and returns an empty body — the DOM mirrors the
    // move right here so no cards are re-fetched; the share dialog's URL is
    // rebuilt from the new card order, so the next Share carries it
    document.addEventListener('htmx:afterRequest', function (event) {
        var moveButton = event.target.closest('[data-move]');
        if (!moveButton || !event.detail.successful) {
            return;
        }
        var card = moveButton.closest('.movie-cards li');
        if (!card || !card.parentNode || card.parentNode.children.length < 2) {
            return; // a single card has nothing to swap with
        }
        var up = moveButton.getAttribute('data-move') === 'up';
        if (up && card.previousElementSibling) {
            card.parentNode.insertBefore(card, card.previousElementSibling);
        } else if (up) {
            card.parentNode.appendChild(card); // first wraps to the end
        } else if (card.nextElementSibling) {
            card.parentNode.insertBefore(card, card.nextElementSibling.nextElementSibling);
        } else {
            card.parentNode.insertBefore(card, card.parentNode.firstElementChild); // last wraps to the front
        }
        syncReorderedShareUrl();
    });

    // the playlist page's share URL is the playlist's /list snapshot — its
    // movie params must follow the live card order
    function syncReorderedShareUrl() {
        var holder = document.querySelector('[data-share-url]');
        if (!holder) {
            return;
        }
        var url = holder.getAttribute('data-share-url') || '/list';
        var parts = url.split('?');
        var params = new URLSearchParams(parts[1] || '');
        params.delete('movie');
        visibleCardIds().forEach(function (id) {
            params.append('movie', id);
        });
        var query = params.toString();
        holder.setAttribute('data-share-url', parts[0] + (query ? '?' + query : ''));
    }

    // other tabs: stay in sync with their marks and the list name
    // (anonymous mode only — authenticated marks have no local counterpart)
    window.addEventListener('storage', function (event) {
        if (AUTHENTICATED) {
            return;
        }
        if (event.key === NAME_KEY) {
            refresh();
            return;
        }
        if (event.key === KEY) {
            ids = load();
            refresh();
        }
    });

    function reseedFromServer() {
        fetch('/marked/ids')
            .then(function (response) {
                return response.ok ? response.text() : '';
            })
            .then(function (text) {
                ids = text.split(',').filter(Boolean).map(Number);
                refresh();
            })
            .catch(function () {
            });
    }

    // bfcache restore: the page comes back exactly as it was left — no
    // scripts re-run and no storage event fires — so a page kept open while
    // marks were made elsewhere would otherwise carry a stale href/count
    window.addEventListener('pageshow', function (event) {
        if (event.persisted) {
            if (AUTHENTICATED) {
                reseedFromServer();
            } else {
                ids = load();
                refresh();
            }
        }
    });

    // boosted navigation re-renders the header and movie actions; the fresh
    // header carries the current server-side mark set, the fresh cards
    // container needs the remembered view re-applied, and the page flags
    // must follow the new URL
    document.addEventListener('htmx:afterSwap', function () {
        syncPageFlags();
        if (AUTHENTICATED) {
            seedEl = document.querySelector('[data-authenticated]');
            ids = loadSeed();
        }
        refresh();
        applyCardsView();
        movieNoteAffordance();
        // move-all from the marked page: the server has already cleared the
        // marks (the header seed is stale until the next request) — the moved
        // ids ride on the confirmation, the cards and counts follow here
        var moved = document.querySelector('.pick-confirmation[data-moved-movies]');
        if (moved) {
            var movedIds = (moved.getAttribute('data-moved-movies') || '')
                .split(',').filter(Boolean).map(Number);
            ids = ids.filter(function (id) {
                return movedIds.indexOf(id) === -1;
            });
            movedIds.forEach(function (id) {
                var li = document.querySelector('.movie-cards li[data-movie-id="' + id + '"]');
                if (li) {
                    li.remove();
                }
            });
            refresh();
        }
        // a note editor swapped in takes focus straight away — clicking the
        // toggle already said "edit now"
        var noteEditor = document.querySelector('.card-note-form textarea');
        if (noteEditor) {
            noteEditor.focus();
        }
    });
    refresh();
    applyCardsView();
    if (AUTHENTICATED) {
        migrateLocalMarks();
    }
})();