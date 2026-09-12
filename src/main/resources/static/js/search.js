(function () {
    'use strict';
    var INPUT_FIELDS = /^(INPUT|TEXTAREA|SELECT)$/;

    function input() {
        return document.getElementById('search-input');
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

    // mobile: keep focus on the input for taps inside the results panel, otherwise the
    // blur hides the panel (:focus-within) before the tap becomes a click on the link;
    // same for the Cmd+K tip — preventDefault keeps focus handling to the click handlers
    document.addEventListener('pointerdown', function (event) {
        if (!event.target.closest) {
            return;
        }
        if (event.target.closest('.search-overlay') || event.target.closest('#search-kbd')) {
            event.preventDefault();
        }
    });

    // click away from the overlay closes it even when the browser keeps focus on the input (Safari)
    document.addEventListener('click', function (event) {
        var i = input();
        if (!i) {
            return;
        }
        if (event.target.closest && event.target.closest('#search-kbd')) {
            i.focus();
            return;
        }
        if (document.activeElement === i && !i.parentElement.contains(event.target)) {
            i.blur();
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