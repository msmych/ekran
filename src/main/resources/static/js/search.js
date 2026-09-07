(function () {
    'use strict';
    var INPUT_FIELDS = /^(INPUT|TEXTAREA|SELECT)$/;

    document.addEventListener('keydown', function (event) {
        var input = document.getElementById('search-input');
        if (!input) {
            return;
        }
        if (event.key === 'Escape' && document.activeElement === input) {
            if (input.parentElement.classList.contains('site-search')) {
                // overlay search: close the panel, keep the query, stay on the page
                event.preventDefault();
                input.blur();
            } else {
                // home search: clear the query and the results below
                event.preventDefault();
                input.value = '';
                input.dispatchEvent(new Event('input', { bubbles: true }));
            }
            return;
        }
        var cmdK = (event.metaKey || event.ctrlKey) && (event.key === 'k' || event.key === 'K' || event.code === 'KeyK');
        var slash = event.key === '/' || event.code === 'Slash';
        if (cmdK || slash && !INPUT_FIELDS.test(document.activeElement.tagName)) {
            event.preventDefault();
            input.focus();
            if (cmdK) {
                input.select();
            }
        }
    });

    // click away from the overlay closes it even when the browser keeps focus on the input (Safari)
    document.addEventListener('click', function (event) {
        var input = document.getElementById('search-input');
        if (input && document.activeElement === input && !input.parentElement.contains(event.target)) {
            input.blur();
        }
    });
})();