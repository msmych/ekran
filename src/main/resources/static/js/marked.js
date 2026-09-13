(function () {
    'use strict';
    var KEY = 'ekran.markedMovies';
    var MAX_SHARE = 100;
    var EDITABLE = /^(INPUT|TEXTAREA|SELECT)$/;
    var ids = load();

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

    function listUrl() {
        var qs = ids.slice(0, MAX_SHARE).map(function (id) {
            return 'movie=' + id;
        }).join('&');
        return qs ? '/list?' + qs : '/list';
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
    }

    // /list: keep the URL in sync with the rendered view so a refresh
    // does not resurrect removed cards; the QR encodes the same URL
    function syncListUrl() {
        var remaining = Array.prototype.map.call(
            document.querySelectorAll('.movie-cards li'),
            function (li) {
                return li.getAttribute('data-movie-id');
            });
        var qs = remaining.map(function (id) {
            return 'movie=' + id;
        }).join('&');
        history.replaceState(null, '', qs ? '/list?' + qs : '/list');
    }

    function copyUrl(url) {
        copyToClipboard(url, function () {
            var feedback = document.querySelector('[data-share-feedback]');
            if (!feedback) {
                return;
            }
            feedback.textContent = 'Link copied';
            feedback.classList.add('visible');
            setTimeout(function () {
                feedback.classList.remove('visible');
                feedback.textContent = '';
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

    // one delegated listener for every marking/list interaction; the share
    // dialog always encodes the current URL (kept in sync by syncListUrl),
    // so the QR is rendered fresh on every dialog open — the generic opener
    // in search.js shows the modal, these branches just fill it
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
        var button = event.target.closest('[data-mark-button]');
        if (button) {
            toggle(Number(button.getAttribute('data-movie-id')));
            refresh();
            return;
        }
        var remove = event.target.closest('[data-remove-movie]');
        if (remove) {
            var id = Number(remove.getAttribute('data-remove-movie'));
            if (isMarked(id)) {
                toggle(id);
            }
            var card = remove.closest('li');
            if (card) {
                card.remove();
            }
            syncListUrl();
            refresh();
        }
    });

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
        }
    }

    // `m` toggles the displayed movie; physical key code so it also
    // works on non-Latin keyboard layouts; never while editing text
    document.addEventListener('keydown', function (event) {
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

    // other tabs: stay in sync with their marks
    window.addEventListener('storage', function (event) {
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