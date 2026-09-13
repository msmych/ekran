(function () {
    'use strict';
    var KEY = 'ekran.markedMovies';
    var NAME_KEY = 'ekran.listName';
    var NAME_MAX = 60;
    var MAX_SHARE = 100;
    var EDITABLE = /^(INPUT|TEXTAREA|SELECT)$/;
    var ids = load();
    var copyTimer = null;

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

    function toggle(id) {
        var at = ids.indexOf(id);
        if (at === -1) {
            ids.push(id);
        } else {
            ids.splice(at, 1);
        }
        save();
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

    function refresh() {
        var link = document.querySelector('[data-marked-link]');
        if (link) {
            var count = link.querySelector('[data-marked-count]');
            if (count) {
                count.textContent = ids.length;
            }
            // hidden until the first mark — done from JS, never in the markup,
            // so a stale/failed script can never hide the entry point
            link.hidden = ids.length === 0;
            link.setAttribute('href', listUrl());
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
        var title = document.querySelector('[data-list-title]');
        if (!title) {
            return;
        }
        var cardIds = visibleCardIds();
        var empty = cardIds.length === 0;
        var yours = empty || isOwnList();
        var name = urlName() || (yours && !empty ? storedName() : null);
        title.textContent = name || (yours ? 'Marked movies' : 'Shared list');
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
        input.hidden = false;
        title.hidden = true;
        setHidden('[data-list-name-edit]', true);
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
        var url = location.href;
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
            copyUrl(location.href);
            return;
        }
        var nameEdit = event.target.closest('[data-list-name-edit]');
        if (nameEdit) {
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
            if (location.pathname === '/list') {
                // on /list the card set is the URL — unmarking removes the card,
                // marking from the search overlay appends it; elsewhere cards stay put
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
            visibleCardIds().forEach(function (id) {
                if (!isMarked(id)) {
                    toggle(id);
                }
            });
            refresh();
            return;
        }
        var clearAll = event.target.closest('[data-clear-marks]');
        if (clearAll) {
            // clearing is instant and irreversible — the browser confirm guards it
            if (!window.confirm('Clear all marked movies from this list?')) {
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

    // other tabs: stay in sync with their marks and the list name
    window.addEventListener('storage', function (event) {
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

    // bfcache restore: the page comes back exactly as it was left — no
    // scripts re-run and no storage event fires — so a page kept open while
    // marks were made elsewhere would otherwise carry a stale href/count
    window.addEventListener('pageshow', function (event) {
        if (event.persisted) {
            ids = load();
            refresh();
        }
    });

    // boosted navigation re-renders the header and movie actions
    document.addEventListener('htmx:afterSwap', refresh);
    refresh();
})();