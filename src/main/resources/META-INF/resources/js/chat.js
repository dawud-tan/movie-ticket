/*
 * /chat's form: show #chat-wait while a request is out, and clear the form after one succeeds.
 *
 * These were two hx-on:: attributes on the form. htmx runs those through new Function, which a
 * Content-Security-Policy without 'unsafe-eval' refuses (CanonicalAuthorityFilter sends one), so
 * they live here as listeners on the same two htmx events instead.
 */
(function () {
    "use strict";

    var form = document.getElementById("chat-form");
    var wait = document.getElementById("chat-wait");
    if (!form || !wait) {
        return;
    }
    form.addEventListener("htmx:beforeRequest", function () {
        wait.hidden = false;
    });
    form.addEventListener("htmx:afterRequest", function (event) {
        wait.hidden = true;
        if (event.detail.successful) {
            form.reset();
        }
    });
})();
