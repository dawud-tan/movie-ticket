/*
 * Seat-map push subscription — the client half of the seat-freed push.
 *
 * Registers a watch while the seat map is open and removes it when the page closes, which is what
 * "scoping delivery to 'the seat-map page is currently open'" means: delivery only
 * ever has to reach a foregrounded, definitely-alive tab, which sidesteps the far more uneven
 * cross-browser reliability of background push.
 *
 * Push is an ENHANCEMENT here, not the mechanism. The map already refreshes itself by polling
 * every 3s (see seatmapFragment.html), so everything below can fail — permission denied, no
 * Firebase config, iOS Safari in a plain tab, a blocked service worker — and the page still works.
 * Nothing in this file throws into the page.
 *
 * Once a subscription is confirmed live (the /push/subscribe POST below returns ok — NOT merely
 * once permission is granted, which register() and that POST can both still fail after), the poll
 * backs off to 30s: push then carries normal-latency updates via the seat-availability-changed
 * trigger, and the slow poll is only a safety net for a missed or silently failed message. It goes
 * back to 3s the moment the subscription is known stale (push-subscription-changed fires), so a poll
 * interval keyed off request cost never gets left slow against a channel that isn't delivering.
 *
 * An ES module on the MODULAR Firebase JS, because register()/onRegistered() (12.14.0+) exist only
 * there: they hand us a Firebase Installation ID, which the server sends as `fid`.
 * Firebase says not to mix them with getToken(). The version is pinned in both
 * imports and in push/sw.html; change the three together. A blocked gstatic.com stops this module
 * from running at all, which leaves the page exactly as it is with push off: the 3s poll.
 */
import { initializeApp } from "https://www.gstatic.com/firebasejs/12.19.0/firebase-app.js";
import { getMessaging, register, onRegistered, onMessage }
    from "https://www.gstatic.com/firebasejs/12.19.0/firebase-messaging.js";

(function () {
    "use strict";

    var el = document.getElementById("seatmap-push");
    if (!el) {
        return;
    }
    var poolId = el.getAttribute("data-pool-id");
    var sessionId = el.getAttribute("data-session-id");
    var statusEl = document.getElementById("seatmap-push-status");

    var currentToken = null;

    function setStatus(text) {
        if (statusEl) {
            statusEl.textContent = text;
        }
    }

    /*
     * Copy comes from #seatmap-push's data-msg-* attributes, not from this file — a .js file is
     * outside the message bundle's reach, and the template is the only layer that knows the
     * language. Same mechanism as data-pool-id above, and the same one seatmap-selection.js uses.
     *
     * The English fallback is not decoration: src/test/js builds its own DOM, and a template edit
     * that drops an attribute should degrade to a stale language rather than to "undefined" in
     * front of a customer.
     */
    function msg(name, fallback) {
        var value = el.getAttribute("data-msg-" + name);
        return value === null || value === "" ? fallback : value;
    }

    /* fastTrigger is read from the template rather than hardcoded here, so the 3s interval and the
     * trigger's other events stay defined in exactly one place. slowTrigger is derived from it by
     * swapping only the interval, so it inherits those same events instead of risking drift from a
     * hand-maintained second copy. (The hidden-tab gate is seatmap-selection.js's, on the element,
     * so it covers both.)
     *
     * #seat-grid replaces itself wholesale (outerHTML) on every poll, and the markup swapped in is
     * rendered by the server, which always emits the fast trigger — so editing the attribute once
     * would be undone by the very next poll. pushConfirmed tracks the desired state instead, and the
     * htmx:load listener below re-applies it to every freshly swapped-in grid. htmx:load specifically
     * because it fires with the NEW, connected node as evt.target; htmx:afterSwap/afterSettle target
     * the OLD, already-detached node on an outerHTML swap (checked directly against this bundled
     * htmx.min.js — that split is not documented). */
    var fastTrigger = (function () {
        var initialGrid = document.getElementById("seat-grid");
        return initialGrid ? initialGrid.getAttribute("hx-trigger") : null;
    })();
    var slowTrigger = fastTrigger ? fastTrigger.replace("every 3s", "every 30s") : null;
    var pushConfirmed = false;

    function applyPollTrigger(gridEl) {
        var trigger = pushConfirmed ? slowTrigger : fastTrigger;
        if (!gridEl || !window.htmx || !trigger || gridEl.getAttribute("hx-trigger") === trigger) {
            return;
        }
        gridEl.setAttribute("hx-trigger", trigger);
        window.htmx.process(gridEl);
    }

    function setPushConfirmed(confirmed) {
        pushConfirmed = confirmed;
        applyPollTrigger(document.getElementById("seat-grid"));
    }

    if (fastTrigger) {
        document.body.addEventListener("htmx:load", function (evt) {
            if (evt.target && evt.target.id === "seat-grid") {
                applyPollTrigger(evt.target);
            }
        });
    }

    /*
     * iOS supports the Push API only from 16.4 AND only for a PWA actually added to the home
     * screen — a plain Safari tab has no access at all. The plan calls this out specifically and
     * asks for a visible prompt rather than assuming Android's zero-friction behaviour transfers.
     */
    function isIos() {
        return /iPad|iPhone|iPod/.test(navigator.userAgent)
            || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
    }

    function isStandalone() {
        return window.navigator.standalone === true
            || window.matchMedia("(display-mode: standalone)").matches;
    }

    function showIosHint() {
        var hint = document.getElementById("seatmap-ios-hint");
        if (hint) {
            hint.hidden = false;
        }
    }

    /* Refresh the grid in place rather than reloading the page: a reload would throw away the
     * customer's typed name/phone and their current seat selection, which is the whole reason the
     * polling refresh swaps only #seat-grid. Reuse that same element so both paths behave alike. */
    function refreshSeatGrid() {
        var grid = document.getElementById("seat-grid");
        if (grid && window.htmx) {
            window.htmx.trigger(grid, "seat-availability-changed");
        }
    }

    function unsubscribe() {
        if (!currentToken) {
            return;
        }
        /* keepalive lets the request outlive the document being torn down, which a plain fetch
         * does not — the browser cancels in-flight requests on unload. sendBeacon would also
         * survive but can only issue POST, and this is a DELETE.
         *
         * Best-effort by design: if even the keepalive request is cut short, expires_at is the
         * safety net for exactly this case. */
        try {
            fetch("/push/subscribe", {
                method: "DELETE",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ poolId: poolId, sessionId: sessionId, token: currentToken }),
                keepalive: true
            });
        } catch (e) {
            /* nothing to recover; expires_at handles it */
        }
    }

    async function subscribe(config) {
        var swReg = await navigator.serviceWorker.register("/firebase-messaging-sw.js");
        await navigator.serviceWorker.ready;

        var permission = await Notification.requestPermission();
        if (permission !== "granted") {
            setStatus(msg("push-off",
                "Notifications off — the map still refreshes automatically."));
            return;
        }

        var messaging = getMessaging(initializeApp({
            apiKey: config.apiKey,
            authDomain: config.authDomain,
            projectId: config.projectId,
            storageBucket: config.storageBucket,
            messagingSenderId: config.messagingSenderId,
            appId: config.appId
        }));

        /* Foreground message: the tab is focused, so a banner would be noise on top of a map the
         * customer is already looking at. Refresh the grid instead. */
        onMessage(messaging, function () {
            refreshSeatGrid();
        });

        /* The service worker forwards background messages here too, for a tab that is open but
         * unfocused, and says when the push subscription rotated. */
        navigator.serviceWorker.addEventListener("message", function (event) {
            var payload = event.data || {};
            if (payload.type === "seat-availability") {
                refreshSeatGrid();
            } else if (payload.type === "push-subscription-changed") {
                /* The old subscription stops being live the moment this fires, so drop back to
                 * fast polling for the gap. The re-registration itself is the SDK's: its worker
                 * re-registers the FID and tells this page, which lands in onRegistered below, and
                 * only that handler's confirmed POST earns the slow poll back. */
                setPushConfirmed(false);
            }
        });

        /* Fires after register() below (every time, already registered or not), when the FID
         * changes, and after the worker re-registers on pushsubscriptionchange. Each is the same
         * /push/subscribe upsert. Set before register(), which refuses to run without it. */
        onRegistered(messaging, function (fid) {
            watch(fid).catch(function () {
                setStatus(msg("push-unavailable",
                    "Notifications unavailable — the map still refreshes automatically."));
            });
        });

        /* pagehide rather than unload: unload does not fire on mobile Safari when a tab is
         * backgrounded and then discarded, which is precisely the platform where an abandoned
         * watch is most likely. */
        window.addEventListener("pagehide", unsubscribe);

        /* The VAPID key is required by register() and is the ONLY thing VAPID is used for in this
         * design — the server side sends through the Admin SDK and never touches VAPID. A failure
         * here rejects into start()'s catch. */
        await register(messaging, {
            vapidKey: config.vapidKey,
            serviceWorkerRegistration: swReg
        });
    }

    async function watch(fid) {
        currentToken = fid;
        var response = await fetch("/push/subscribe", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ poolId: poolId, sessionId: sessionId, token: fid })
        });
        if (!response.ok) {
            setStatus(msg("push-not-registered",
                "Could not register for updates — the map still refreshes automatically."));
            return;
        }
        setStatus(msg("push-subscribed",
            "You'll be notified when seats for this showing change."));
        setPushConfirmed(true);
    }

    async function start() {
        if (!("serviceWorker" in navigator) || !("Notification" in window) || !("PushManager" in window)) {
            if (isIos() && !isStandalone()) {
                showIosHint();
            }
            return;
        }
        if (isIos() && !isStandalone()) {
            /* iOS 16.4+ exposes the APIs above only inside an installed PWA, so the capability
             * check can pass in a plain tab on newer iOS while subscription still fails. Prompt
             * rather than attempt it. */
            showIosHint();
            return;
        }

        var config;
        try {
            var response = await fetch("/push/config");
            config = await response.json();
        } catch (e) {
            return;
        }
        if (!config || !config.enabled || !config.vapidKey) {
            /* No VAPID key configured on this deployment — nothing to subscribe with. Silent by
             * design: the customer does not need to know the operator has not set it up. */
            return;
        }

        try {
            await subscribe(config);
        } catch (e) {
            setStatus(msg("push-unavailable",
                "Notifications unavailable — the map still refreshes automatically."));
        }
    }

    start();
})();
