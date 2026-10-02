(function () {
    'use strict';
    var KEY = 'ekran.markedMovies';
    var NAME_KEY = 'ekran.listName';
    var NAME_MAX = 60;
    var MAX_SHARE = 100;
    var EDITABLE = /^(INPUT|TEXTAREA|SELECT)$/;
    var seedEl = document.querySelector('[data-authenticated]');
    // authenticated marks live in PostgreSQL: the server seeds the id set into
    // the header (re-rendered on every request), mutations go through /marked,
    // and localStorage is used exactly once — to migrate old anonymous marks
    var AUTHENTICATED = !!seedEl;
    var MARKED_PAGE = location.pathname === '/marked';
    var ids = AUTHENTICATED ? loadSeed() : load();
    var copyTimer = null;

    function loadSeed() {
        var raw = seedEl ? (seedEl.getAttribute('data-marked-ids') || '') : '';
        return raw.split(',').filter(Boolean).map(Number);
    }

    function load() {
        try {
            var raw = JSON.parse(localStorage.getItem(KEY) || '[]');
            if (!(raw instanceof Array)) {
                return [];
            }
            var seen = {};
            var out = [];
            for (var k = 0; k < raw.length; k++) {
                var id = raw[k];
                if (typeof id === 'number' && isFinite(id) && Math.floor(id) === id && id > 0 && !seen[id]) {
                    seen[id] = true;
                    out.push(id);
                }
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

    // one-time migration: anonymous marks (this browser's localStorage) are merged
    // into the account; localStorage is cleared only after the server confirmed,
    // so a failed merge keeps them for the next attempt (idempotent union)
    function migrateLocalMarks() {
        var local = load();
        if (!local.length) {
            return;
        }
        persistBulk(local, 'POST', function () {
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
        }
        // every card-like mark control (list cards, person filmography, search results)
        Array.prototype.forEach.call(document.querySelectorAll('[data-card-mark]'), function (el) {
            var cardMarked = isMarked(Number(el.getAttribute('data-card-mark')));
            el.classList.toggle('marked', cardMarked);
            el.setAttribute('aria-pressed', cardMarked ? 'true' : 'false');
        });
        syncListView();
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
            emptyState.hidden = !empty;
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

    function syncListUrl() {
        if (location.pathname !== '/list') {
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

    // a movie marked from the search overlay while viewing /list joins the
    // view right away — the card is fetched from the /list/card fragment
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
        var titleEdit = event.target.closest && event.target.closest('[data-list-title]');
        if (titleEdit) {
            startNameEdit();
            return;
        }
        var button = event.target.closest('[data-mark-button]');
        if (button) {
            toggle(Number(button.getAttribute('data-movie-id')));
            refresh();
            return;
        }
        var cardMark = event.target.closest('[data-card-mark]');
        if (cardMark) {
            var id = Number(cardMark.getAttribute('data-card-mark'));
            var wasMarked = isMarked(id);
            toggle(id);
            // on /list and /marked the card set is the user's own set — unmarking
            // removes the card, marking from the search overlay appends it;
            // elsewhere cards stay put
            if (location.pathname === '/list' || MARKED_PAGE) {
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
            visibleCardIds().forEach(function (id) {
                if (isMarked(id)) {
                    toggle(id);
                }
            });
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
        // playlists page: the New button reveals the name row above the list
        var newPlaylist = event.target.closest('[data-new-playlist]');
        if (newPlaylist) {
            var newForm = document.querySelector('[data-new-playlist-form]');
            if (newForm) {
                newForm.hidden = false;
                var newInput = newForm.querySelector('input');
                newInput.focus();
            }
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

    // `m` toggles the displayed movie; physical key code so it also
    // works on non-Latin keyboard layouts; never while editing text
    document.addEventListener('keydown', function (event) {
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
                newPlaylistForm.hidden = true;
                var newPlaylistButton = document.querySelector('[data-new-playlist]');
                if (newPlaylistButton) {
                    newPlaylistButton.focus();
                }
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
        // Escape also exits reorder mode, like the other inline modes
        var reorderSection = document.querySelector('[data-reordering]');
        if (reorderSection && event.key === 'Escape') {
            reorderSection.removeAttribute('data-reordering');
            var reorderToggle = document.querySelector('[data-reorder-toggle]');
            if (reorderToggle) {
                reorderToggle.textContent = 'Reorder';
                reorderToggle.focus();
            }
            return;
        }
        if (event.code !== 'KeyM' || event.metaKey || event.ctrlKey || event.altKey) {
            return;
        }
        var active = document.activeElement;
        if (active && (EDITABLE.test(active.tagName) || active.isContentEditable)) {
            return;
        }
        var button = document.querySelector('[data-mark-button]');
        if (button) {
            event.preventDefault();
            button.click();
        }
    });

    // committing the list name on blur (focusout bubbles; blur does not)
    document.addEventListener('focusout', function (event) {
        if (event.target.closest && event.target.closest('[data-list-name-input]')) {
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

    // other tabs: stay in sync with their marks and the list name (anonymous mode
    // only — authenticated marks have no local counterpart to sync)
    window.addEventListener('storage', function (event) {
        if (AUTHENTICATED) {
            return;
        }
        if (event.key === NAME_KEY) {
            refresh();
            return;
        }
        if (event.key !== KEY) {
            return;
        }
        ids = load();
        refresh();
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
    // header carries the current server-side mark set
    document.addEventListener('htmx:afterSwap', function () {
        if (AUTHENTICATED) {
            seedEl = document.querySelector('[data-authenticated]');
            ids = loadSeed();
        }
        refresh();
    });
    refresh();
    if (AUTHENTICATED) {
        migrateLocalMarks();
    }
})();