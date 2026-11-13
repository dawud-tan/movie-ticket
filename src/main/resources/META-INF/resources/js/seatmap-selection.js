/*
 * Tells the customer about the two things that happen to them silently: a seat they had already
 * ticked being taken by someone else, and the server refusing a request outright.
 *
 * Both are reporting only. Nothing here alters the selection, re-ticks anything, or blocks a
 * submit — the server is the authority in both cases and the DOM is already correct by the time
 * this runs.
 *
 * --- 1. a ticked seat taken by someone else ---
 *
 * The revert itself needs no code and already worked: an available seat renders as
 * <label id="seat-{uuid}" hx-preserve="true"> wrapping a checkbox, a taken one renders as a plain
 * <span> with no id, so htmx has nothing to preserve, the ticked label is dropped on the swap, and
 * the checkbox leaves the form and cannot be submitted. What was missing is any indication that it
 * happened: with three seats ticked and one taken, the seat quietly changed colour and the customer
 * could hold two seats believing they had asked for three. The notice distinguishes a seat someone
 * else merely HELD from one they CONFIRMED, because only the first may come back — see phraseFor().
 *
 * Independent of push and of the notification permission: it hangs off htmx's swap events, so it
 * covers the 3s poll, the tab-focus refresh, the manual Refresh button and an FCM-triggered
 * refresh identically. Nothing here throws into the page.
 *
 * --- 2. a request the server refused ---
 *
 * Every failure the server can describe in a fragment comes back as HTTP 200 with an .error-banner
 * inside it — a seat lost to the EXCLUDE constraint, a closed showing — precisely because htmx
 * swaps 2xx only. A refusal from the security layer is different in kind: it never reaches the
 * resource method, so there is no fragment to swap, and htmx's default is to log to the console and
 * change the page not at all. The customer presses "Hold selected seats" and nothing whatsoever
 * happens.
 *
 * That is not hypothetical, and the case that matters is not the interesting one. A platform_admin
 * pressing Hold gets a 403 by design (the endpoint allows customer and tenant_staff only, and a
 * platform admin has no home tenant to hold a seat against) — but an ordinary customer whose OIDC
 * session expired while they were choosing gets a 401 through the same silent path, on the one flow
 * that takes money.
 *
 * --- 3. the poll pauses while the tab is hidden ---
 *
 * The one thing here that does decide something, and it decides only whether to ask: a request for
 * #seat-grid from a hidden tab is cancelled before it is sent, and showing the tab again fires the
 * same refresh a push does. It was a filter on the grid's hx-trigger until the page's
 * Content-Security-Policy refused the eval htmx compiles a filter with (seatmapFragment.html).
 */
(function () {
    "use strict";

    /* Seats reported as lost but not yet acknowledged. Accumulated rather than replaced: two seats
     * taken by two different people, thirty seconds apart, are two separate swaps, and overwriting
     * would leave the customer told about only the most recent one. */
    var lostSeats = [];

    /* Ticked seats captured from the OLD grid, before htmx replaces it. */
    var ticked = null;

    function grid() {
        return document.getElementById("seat-grid");
    }

    function notice() {
        return document.getElementById("seat-taken-notice");
    }

    /*
     * Copy comes from the DOM, not from this file.
     *
     * A .js file is outside the message bundle's reach — Qute cannot render into one, and this
     * one is a static asset served straight off the classpath with its own content hash. So the
     * template renders each sentence into a data-msg-* attribute and this reads it back, exactly
     * as data-signin-url already worked: the template is the only layer that knows the language,
     * for the same reason it was already the only layer that knew the sign-in origin.
     *
     * The fallback is English and is not decoration. src/test/js drives these scripts against a
     * DOM it builds itself, and a missing attribute must produce a readable sentence rather than
     * the string "undefined" in front of a customer — the failure mode of a template edit that
     * drops one attribute is then a stale language, not a broken page.
     */
    function msg(el, name, fallback) {
        var value = el ? el.getAttribute("data-msg-" + name) : null;
        return value === null || value === "" ? fallback : value;
    }

    /* %seats% and %status% rather than Qute's own {…}: these are patterns filled in here, after
     * a seat is lost or a request fails, so they have to survive the bundle unresolved. Msg.java
     * has the argument. Split/join rather than String.replace, because replace() reads $& and
     * friends in the replacement — and the replacement here is a seat label, which is tenant
     * data. */
    function fill(pattern, token, value) {
        return pattern.split("%" + token + "%").join(value);
    }

    function tickedSeats() {
        var g = grid();
        if (!g) {
            return [];
        }
        var seats = [];
        g.querySelectorAll('input[name="seatIds"]:checked').forEach(function (box) {
            var label = box.closest("label");
            seats.push({
                id: box.value,
                /* The visible label ("A1"), which is what the customer picked and the only name
                 * that means anything to them — a UUID in a banner would be worse than silence. */
                name: label ? label.textContent.trim() : box.value
            });
        });
        return seats;
    }

    /* Compared by iterating rather than by building an attribute selector from a seat id: the id
     * comes from the DOM and would otherwise be interpolated into a querySelector string. */
    function selectableIds() {
        var ids = Object.create(null);
        var g = grid();
        if (g) {
            g.querySelectorAll('input[name="seatIds"]').forEach(function (box) {
                ids[box.value] = true;
            });
        }
        return ids;
    }

    /*
     * The status of a seat that has left the selectable set, read off the span that replaced it.
     *
     * Matched on the visible label rather than the seat id, because the taken branch deliberately
     * renders NEITHER an id nor hx-preserve — that absence IS the revert mechanism, and giving the
     * span an id just to make this lookup convenient would silently break it. Labels are unique
     * within a pool, so they are a sound key here.
     *
     * Iterated rather than turned into a selector, for the same reason selectableIds() iterates:
     * the value being matched came out of the DOM.
     *
     * Returns null when no span matches — a fragment that dropped the seat entirely rather than
     * re-rendering it. Callers fall back to the blunter wording, which is the safe direction to be
     * wrong in: it understates nothing.
     */
    function statusOf(name) {
        var g = grid();
        if (!g) {
            return null;
        }
        var found = null;
        g.querySelectorAll("span.seat").forEach(function (span) {
            if (found || span.textContent.trim() !== name) {
                return;
            }
            if (span.classList.contains("held")) {
                found = "held";
            } else if (span.classList.contains("confirmed")) {
                found = "confirmed";
            }
        });
        return found;
    }

    /*
     * Held and confirmed are different promises, and saying "taken" about both loses the one that
     * matters to the customer standing there deciding what to do next.
     *
     * A confirmed seat is paid for and gone. A held one is someone else's booking.hold-duration-
     * minutes claim on it, which ReservationLifecycleService hands straight back if they don't pay
     * — and when it does, the HOLDS_EXPIRED push re-renders this very grid with the seat available
     * again. Telling someone their seat was "taken" sends them off to pick a worse one rather than
     * waiting a few minutes for the one they actually wanted. The seat map already draws the two
     * states in different colours; this is the notice catching up with the legend.
     */
    function phraseFor(names, status) {
        var box = notice();
        var many = names.length > 1;
        var list = many
            ? names.slice(0, -1).join(", ") + " " + msg(box, "and", "and") + " "
                + names[names.length - 1]
            : names[0];
        /* Singular and plural are two whole sentences from the template rather than a ternary on
         * " is"/" are" here. That ternary was English grammar compiled into a script that now
         * serves two languages: Indonesian marks neither number nor the it/they split, and no
         * amount of conditional assembly in here would have produced its sentence. */
        var pattern = status === "held"
            ? msg(box, many ? "held-many" : "held-one",
                many ? "%seats% are being held by someone else — they may free up shortly."
                     : "%seats% is being held by someone else — it may free up shortly.")
            : msg(box, many ? "taken-many" : "taken-one",
                many ? "%seats% were just taken by someone else."
                     : "%seats% was just taken by someone else.");
        return fill(pattern, "seats", list);
    }

    function namesWithStatus(held) {
        var names = [];
        lostSeats.forEach(function (s) {
            if ((s.status === "held") === held) {
                names.push(s.name);
            }
        });
        return names;
    }

    function render() {
        var box = notice();
        if (!box) {
            return;
        }
        if (lostSeats.length === 0) {
            box.hidden = true;
            box.textContent = "";
            return;
        }
        /* Two clauses rather than one merged list, because lostSeats accumulates across swaps and a
         * customer choosing for several minutes can easily lose one seat each way. Confirmed first:
         * it is the half they can do nothing about, so it belongs where it will be read. */
        var parts = [];
        var gone = namesWithStatus(false);
        var held = namesWithStatus(true);
        if (gone.length > 0) {
            parts.push(phraseFor(gone, "confirmed"));
        }
        if (held.length > 0) {
            parts.push(phraseFor(held, "held"));
        }
        parts.push(msg(box, "others-selected", "Your other seats are still selected."));
        box.textContent = parts.join(" ");
        box.hidden = false;
    }

    function clearNotice() {
        if (lostSeats.length === 0) {
            return;
        }
        lostSeats = [];
        render();
    }

    /*
     * Which swaps to inspect. The availability refresh swaps #seat-grid; the manual Refresh button
     * swaps the whole #seat-map-container with a GET.
     *
     * The hold POST also targets #seat-map-container, and must be excluded: it turns the
     * customer's OWN seats into held spans, so treating it like a refresh would announce
     * "A1 was just taken by someone else" about the seat they themselves just successfully held.
     * The verb is what separates the two.
     */
    function isAvailabilityRefresh(detail) {
        var target = detail && detail.target;
        if (!target || !target.id) {
            return false;
        }
        if (target.id === "seat-grid") {
            return true;
        }
        var verb = detail.requestConfig && detail.requestConfig.verb;
        return target.id === "seat-map-container" && verb === "get";
    }

    document.body.addEventListener("htmx:beforeSwap", function (event) {
        ticked = isAvailabilityRefresh(event.detail) ? tickedSeats() : null;
    });

    /*
     * afterSettle, not afterSwap. htmx restores hx-preserve'd elements during the settle step — in
     * browsers with moveBefore it parks the old nodes in a #--htmx-preserve-pantry- div outside the
     * grid first — so a seat that is merely being preserved is legitimately absent from the grid
     * mid-swap. Checking then would report seats nobody took.
     */
    document.body.addEventListener("htmx:afterSettle", function () {
        if (!ticked || ticked.length === 0) {
            ticked = null;
            return;
        }
        var previously = ticked;
        ticked = null;

        var stillThere = selectableIds();
        /* An empty grid means the fragment failed to render or the showing closed, not that every
         * seat was taken in one swap. Accusing strangers of taking all of them would be wrong. */
        if (Object.keys(stillThere).length === 0) {
            return;
        }

        previously.forEach(function (seat) {
            if (stillThere[seat.id]) {
                return;
            }
            var known = lostSeats.some(function (s) { return s.id === seat.id; });
            if (!known) {
                /* Read now, not at render time: #seat-grid is replaced on every poll, so by the
                 * time the customer reads the banner the span this came from is several swaps
                 * gone. The status is a fact about the moment they lost the seat anyway. */
                seat.status = statusOf(seat.name);
                lostSeats.push(seat);
            }
        });
        render();
    });

    /* Dismiss when the customer next changes their selection: that is the point they have taken it
     * in and are choosing again, and it also clears a notice that has gone stale because the seat
     * was released and re-ticked. Not a timeout — the reason this exists is the customer who looked
     * away, and a banner that hides itself after five seconds is no better than no banner. */
    document.addEventListener("change", function (event) {
        var t = event.target;
        if (t && t.name === "seatIds") {
            clearNotice();
        }
    });

    /* ---------------------------------------------------------------------------------------
     * Refusals the server could not put in a fragment. See part 2 of the header comment.
     *
     * There is deliberately no "clear on success" here, unlike clearNotice() above. The 3s poll
     * succeeds constantly, so clearing on a successful request would erase this banner about three
     * seconds after the customer read it. A full container swap re-renders the element hidden and
     * empty, and that is the right moment: by then they have refreshed or held something, so the
     * message really is stale.
     * --------------------------------------------------------------------------------------- */

    function errorNotice() {
        return document.getElementById("seat-error-notice");
    }

    /*
     * Built with createElement and textContent rather than innerHTML. The message text is fixed
     * here, but the href is read back out of the DOM, and assembling markup from a value that came
     * out of an attribute is the habit worth not having.
     *
     * @param text  the sentence to show.
     * @param link  {href, label} offering the next action, or null when there isn't a useful one.
     */
    function showError(text, link) {
        var box = errorNotice();
        if (!box) {
            return;
        }
        box.textContent = text;
        if (link && link.href) {
            box.appendChild(document.createTextNode(" "));
            var a = document.createElement("a");
            a.setAttribute("href", link.href);
            a.textContent = link.label;
            box.appendChild(a);
        }
        box.hidden = false;
    }

    function signInLink() {
        var box = errorNotice();
        var href = box ? box.getAttribute("data-signin-url") : null;
        return href ? { href: href, label: msg(box, "sign-in-again", "Sign in again") } : null;
    }

    /*
     * 401 (and 499, its signed-out twin below) and 403 are surfaced whichever request produced them — neither is ever transient, and
     * both mean the customer must do something about their identity before anything else works.
     *
     * Any other status is surfaced only for a non-GET. A 500 or a 502 on the background poll is
     * usually a restart or a blip that the next poll three seconds later fixes by itself, and
     * flashing a red banner about a problem that has already gone away is worse than the silence
     * this replaces. A failed hold is the opposite: nothing retries it, and the customer is
     * standing there waiting.
     */
    function describe(status) {
        var box = errorNotice();
        /* 499 is Quarkus's code for "a JavaScript request needs to sign in": the web tenant answers
         * htmx with it instead of a 302 to Google, which htmx would follow cross-origin and fail
         * as status 0 (HtmxRequestChecker). Same meaning as a 401, so the same notice. */
        if (status === 401 || status === 499) {
            /* Not "you are not logged in" — they were, which is why they got this far with a form
             * full of seats. The expiry is the thing that surprises them. */
            return {
                text: msg(box, "session-expired",
                    "Your session expired, so those seats were not held. "
                    + "Your selection is still here."),
                link: signInLink()
            };
        }
        if (status === 403) {
            /* Authenticated but not permitted: a platform_admin, or a deactivated account whose
             * augmentor granted no roles. Offer sign-out rather than sign-in — signing in again as
             * the same identity lands in exactly the same place. /logout is local-only and does not
             * touch the Google session (see LogoutResource), so it is safe to send them there.
             *
             * /logout stays a literal here while the sign-in URL comes from an attribute, and the
             * asymmetry is the one SignInLink explains: sign-in has to be absolute onto the https
             * origin, sign-out is same-origin and relative is correct. */
            return {
                text: msg(box, "not-permitted",
                    "This account is not allowed to hold seats. Use the account you book "
                    + "with — your selection is unchanged."),
                link: { href: "/logout", label: msg(box, "sign-out", "Sign out") }
            };
        }
        return {
            text: fill(msg(box, "server-error",
                "Those seats could not be held (server error %status%). "
                + "Your selection is unchanged — please try again."), "status", status),
            link: null
        };
    }

    document.body.addEventListener("htmx:responseError", function (event) {
        var detail = event.detail || {};
        var status = detail.xhr ? detail.xhr.status : 0;
        var verb = detail.requestConfig && detail.requestConfig.verb;

        if (status !== 401 && status !== 499 && status !== 403 && (!verb || verb === "get")) {
            return;
        }
        var message = describe(status);
        showError(message.text, message.link);
    });

    /* A dropped connection rather than a response: same invisible outcome, same filter. htmx fires
     * this instead of responseError when the request never completed, so without it a hold pressed
     * on a flaky connection is exactly as silent as the 403 was. */
    document.body.addEventListener("htmx:sendError", function (event) {
        var detail = event.detail || {};
        var verb = detail.requestConfig && detail.requestConfig.verb;
        if (!verb || verb === "get") {
            return;
        }
        showError(msg(errorNotice(), "unreachable",
            "Could not reach the server, so those seats were not held. "
            + "Your selection is unchanged — please try again."), null);
    });

    /* Keyed on the element, not on the trigger, so it holds for the 3s poll, seatmap-push.js's 30s
     * one and a push alike. A push that lands while the tab is hidden waits for the tab to be shown;
     * nobody is looking at the map before then. */
    document.body.addEventListener("htmx:beforeRequest", function (event) {
        var elt = event.detail && event.detail.elt;
        if (elt && elt.id === "seat-grid" && document.visibilityState !== "visible") {
            event.preventDefault();
        }
    });

    document.addEventListener("visibilitychange", function () {
        var el = grid();
        if (el && window.htmx && document.visibilityState === "visible") {
            window.htmx.trigger(el, "seat-availability-changed");
        }
    });
})();
