(function () {
    'use strict';
    var INPUT_FIELDS = /^(INPUT|TEXTAREA|SELECT)$/;

    function input() {
        return document.getElementById('search-input');
    }

    // event.target may be a non-Element (document, text node); guard once here
    function up(target, selector) {
        return target && target.closest ? target.closest(selector) : null;
    }

    function results() {
        var i = input();
        if (!i) {
            return [];
        }
        var container = document.querySelector(i.getAttribute('hx-target') || '#results');
        return container ? container.querySelectorAll('.result') : [];
    }

    function selectedIndex(items) {
        for (var k = 0; k < items.length; k++) {
            if (items[k].classList.contains('selected')) {
                return k;
            }
        }
        return -1;
    }

    function moveSelection(delta) {
        var items = results();
        var next = selectedIndex(items) + delta;
        if (items.length === 0 || next < 0 || next >= items.length) {
            return;
        }
        for (var k = 0; k < items.length; k++) {
            items[k].classList.remove('selected');
        }
        var item = items[next];
        item.classList.add('selected');
        item.scrollIntoView({ block: 'nearest' });
    }

    document.addEventListener('keydown', function (event) {
        var i = input();
        if (!i) {
            return;
        }

        if (document.activeElement === i) {
            if (event.key === 'ArrowDown' || event.ctrlKey && event.key === 'n') {
                if (results().length > 0) {
                    event.preventDefault();
                    moveSelection(1);
                }
                return;
            }
            if (event.key === 'ArrowUp' || event.ctrlKey && event.key === 'p') {
                if (results().length > 0) {
                    event.preventDefault();
                    moveSelection(-1);
                }
                return;
            }
            if (event.key === 'Enter') {
                var items = results();
                var at = selectedIndex(items);
                if (at >= 0) {
                    event.preventDefault();
                    items[at].click();
                }
                // no selection: default fires the `search` event -> immediate search
                return;
            }
            if (event.key === 'Escape') {
                event.preventDefault();
                if (i.parentElement.classList.contains('site-search')) {
                    // overlay search: close the panel, keep the query, stay on the page
                    i.blur();
                } else {
                    // home search: clear the query and the results below
                    i.value = '';
                    i.dispatchEvent(new Event('input', { bubbles: true }));
                }
                return;
            }
        }

        var cmdK = (event.metaKey || event.ctrlKey) && (event.key === 'k' || event.key === 'K' || event.code === 'KeyK');
        var slash = event.key === '/' || event.code === 'Slash';
        if (cmdK || slash && !INPUT_FIELDS.test(document.activeElement.tagName)) {
            event.preventDefault();
            i.focus();
            if (cmdK) {
                i.select();
            }
        }
    });

    // typing resets the highlight; fresh results arrive via the htmx swap
    document.addEventListener('input', function (event) {
        var i = input();
        if (!i || event.target !== i) {
            return;
        }
        results().forEach(function (item) {
            item.classList.remove('selected');
        });
    }, true);

    // mobile: tapping a result blurs the input between `pointerdown` and the
    // synthesized `click`, and the `:focus-within` loss would hide the panel —
    // removing the link before the click lands. Worse, iOS Safari with the
    // keyboard up swallows the first tap entirely: it only dismisses the
    // keyboard and never synthesizes the click, so no panel grace window can
    // save it. The tap is therefore completed manually: `pointerdown` arms the
    // `.tap-through` window (keeps the panel visible past the blur), and
    // `pointerup` on a result fires the click right away — `pointerup` is always
    // delivered, unlike the click. The click the browser may still synthesize
    // afterwards is swallowed in the capture-phase listener below, so the
    // htmx-boosted navigation (and its history entry) fires exactly once.
    // A pointer that moved more than a few pixels is a scroll gesture, not a
    // tap — the overlay scrolls, no click fires.
    // With a mouse, preventDefault keeps the focus on the input (clicks still
    // fire); same for the Cmd+K tip badge.
    var tapStart = null;
    var suppressOverlayClickUntil = 0;

    document.addEventListener('pointerdown', function (event) {
        var overlay = up(event.target, '.search-overlay');
        if (overlay) {
            if (event.pointerType === 'mouse') {
                event.preventDefault();
            } else {
                tapStart = { x: event.clientX, y: event.clientY, id: event.pointerId };
                overlay.classList.add('tap-through');
                setTimeout(function () {
                    overlay.classList.remove('tap-through');
                }, 500);
            }
            return;
        }
        if (event.pointerType === 'mouse' && up(event.target, '#search-kbd')) {
            event.preventDefault();
        }
    });

    document.addEventListener('pointerup', function (event) {
        if (!tapStart || event.pointerId !== tapStart.id) {
            return;
        }
        var start = tapStart;
        tapStart = null;
        var tapped = up(event.target, 'a, button');
        if (!tapped || !up(tapped, '.search-overlay')) {
            return;
        }
        var moved = Math.abs(event.clientX - start.x) > 8 || Math.abs(event.clientY - start.y) > 8;
        if (!moved) {
            tapped.click();
            suppressOverlayClickUntil = Date.now() + 500;
        }
    });

    document.addEventListener('pointercancel', function (event) {
        if (tapStart && event.pointerId === tapStart.id) {
            tapStart = null;
        }
    });

    // swallow the click the browser may still synthesize after the manual one;
    // capture phase so it never reaches htmx or the other click handlers
    document.addEventListener('click', function (event) {
        if (Date.now() < suppressOverlayClickUntil && event.isTrusted && up(event.target, '.search-overlay')) {
            event.preventDefault();
            event.stopPropagation();
        }
    }, true);

    // search-bar clicks: movies/people toggle, Cmd+K tip focus, click-away close
    // (closes the overlay even when the browser keeps focus on the input — Safari)
    document.addEventListener('click', function (event) {
        var i = input();
        if (!i) {
            return;
        }
        var toggle = up(event.target, '[data-search-type]');
        if (toggle && i.parentElement.contains(toggle)) {
            var hidden = document.getElementById('search-type');
            var type = toggle.getAttribute('data-search-type') || '';
            if (!hidden || hidden.value === type) {
                return;
            }
            hidden.value = type;
            i.parentElement.querySelectorAll('[data-search-type]').forEach(function (button) {
                button.classList.toggle('active', button === toggle);
            });
            i.placeholder = type === 'person' ? 'Search people\u2026' : 'Search movies\u2026';
            if (i.value.trim()) {
                // re-fire through the bare `search` trigger: no `changed` gate,
                // so it runs even though the query itself did not change
                i.dispatchEvent(new Event('search', { bubbles: true }));
            }
            return;
        }
        if (up(event.target, '#search-kbd')) {
            i.focus();
            return;
        }
        if (document.activeElement === i && !i.parentElement.contains(event.target)) {
            i.blur();
        }
    });

    // native <dialog> handling: [data-dialog="id"] openers, ✕/backdrop/Escape closers;
    // document-level so it survives htmx swaps
    // closing the dialog pauses the video: display:none does not stop the audio,
    // so on the dialog's `close` event (fires for every close path) we send
    // YouTube's pauseVideo post-message (enabled by enablejsapi=1 in the embed URL)
    function pauseVideos(dialog) {
        var frames = dialog.querySelectorAll('iframe');
        for (var k = 0; k < frames.length; k++) {
            frames[k].contentWindow.postMessage('{"event":"command","func":"pauseVideo","args":[]}', '*');
        }
    }

    document.addEventListener('click', function (event) {
        var video = up(event.target, '.video-list a');
        if (video) {
            var list = video.closest('.video-list');
            var items = list.querySelectorAll('li');
            for (var k = 0; k < items.length; k++) {
                items[k].classList.remove('selected');
            }
            video.closest('li').classList.add('selected');
            return;
        }
        var opener = up(event.target, '[data-dialog]');
        if (opener) {
            event.preventDefault();
            var dialog = document.getElementById(opener.getAttribute('data-dialog'));
            if (dialog && typeof dialog.showModal === 'function') {
                if (!dialog.dataset.pauseHooked) {
                    dialog.dataset.pauseHooked = '1';
                    dialog.addEventListener('close', function () {
                        pauseVideos(dialog);
                    });
                }
                dialog.showModal();
            }
            return;
        }
        var closer = up(event.target, '[data-dialog-close]');
        if (closer) {
            var dialogToClose = closer.closest('dialog');
            if (dialogToClose) {
                dialogToClose.close();
            }
            return;
        }
        if (event.target.tagName === 'DIALOG') {
            event.target.close();
        }
    });

    // account menu: click-away closes the open <details>
    document.addEventListener('click', function (event) {
        var menu = document.querySelector('details[data-account-menu][open]');
        if (menu && !menu.contains(event.target)) {
            menu.removeAttribute('open');
        }
    });

    // Cmd+K tip: platform-correct label; document-level so it survives htmx swaps,
    // and re-applied after each swap (boosted navigation re-inserts the raw template label)
    function applyKbdLabel() {
        var kbd = document.getElementById('search-kbd');
        if (!kbd) {
            return;
        }
        var mac = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);
        kbd.textContent = mac ? '⌘K' : 'Ctrl K';
    }

    applyKbdLabel();
    document.addEventListener('htmx:afterSwap', applyKbdLabel);
})();