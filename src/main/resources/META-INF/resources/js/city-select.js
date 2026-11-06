/*
 * The city select in the header.
 *
 * Two enhancements over markup that already works, and neither one is load-bearing:
 *
 *   1. submit the form on change, so choosing a city takes one tap instead of two;
 *   2. "Use my location", which asks the browser for coordinates and posts them so the server
 *      can pick the nearest city.
 *
 * The same rule seatmap-selection.js states applies here: NOTHING in this file decides anything.
 * The form submits the chosen city with or without it, the "Go" button is always rendered, and
 * the locate button is only rendered at all when some city has coordinates to be near. A visitor
 * with JavaScript disabled, or with this file failing to load, has the whole feature.
 */
(function () {
    "use strict";

    var form = document.querySelector("form.city-select");
    if (!form) {
        return;
    }

    /* Auto-submit on change. Deliberately not on the locate button — that is type="button" and
       lives inside the same form precisely so it can share the layout without submitting it. */
    var field = form.querySelector("#city-field");
    if (field) {
        field.addEventListener("change", function () {
            form.submit();
        });
    }

    var locate = document.getElementById("city-locate");
    var status = document.getElementById("city-locate-status");
    if (!locate) {
        return;
    }

    function say(message) {
        if (status) {
            status.textContent = message;
        }
    }

    locate.addEventListener("click", function () {
        /* Feature-detected rather than assumed: geolocation is absent in older browsers and is
           blocked outright by Chrome and Safari on insecure origins. On this deployment that is
           the http:8080 entry point, which is a real address a real visitor reaches — see
           SignInLink for the other half of the same asymmetry. Saying so beats a silent no-op. */
        if (!navigator.geolocation) {
            say("This browser cannot share a location. Pick a city from the list.");
            return;
        }
        locate.disabled = true;
        say("Finding your nearest cinema…");

        navigator.geolocation.getCurrentPosition(function (position) {
            var body = new URLSearchParams();
            body.set("latitude", String(position.coords.latitude));
            body.set("longitude", String(position.coords.longitude));

            fetch("/city/nearest", {
                method: "POST",
                headers: { "Content-Type": "application/x-www-form-urlencoded" },
                body: body.toString(),
                /* The response sets the city cookie, so the request has to be allowed to carry
                   and receive cookies. Same-origin is the default in modern browsers and is
                   stated anyway, because the default changed once already. */
                credentials: "same-origin"
            }).then(function (response) {
                if (!response.ok) {
                    throw new Error("HTTP " + response.status);
                }
                return response.json();
            }).then(function (chosen) {
                if (!chosen || !chosen.city) {
                    /* A 200 with no city means "none of this tenant's cities has coordinates",
                       which is a configuration state and not a failure of the request. Told
                       apart from an error on purpose: the two need different things done. */
                    say("No cinema near you yet. Pick a city from the list.");
                    locate.disabled = false;
                    return;
                }
                say(chosen.city);
                /* Reload rather than re-render: the city changes what almost every part of the
                   page lists, and the server already renders all of it. Trying to patch the DOM
                   here would be a second, divergent copy of the page's own logic. */
                window.location.reload();
            }).catch(function () {
                say("Could not work out your nearest cinema. Pick a city from the list.");
                locate.disabled = false;
            });
        }, function () {
            /* Covers denial, timeout and position-unavailable alike. They are distinguishable in
               the error code, and are not distinguished here on purpose: the visitor's next
               action is the same in all three, and naming the reason invites them to retry
               something that will fail again. */
            say("Location not shared. Pick a city from the list.");
            locate.disabled = false;
        }, { timeout: 10000, maximumAge: 300000 });
    });
})();
