/*
 * "Notify me about this order": the buyer's own push for the three events the buyer's email covers, showing
 * cancelled, refund sent, refund refused. The app's order screen has the same control.
 *
 * Unlike the seat map's watch (seatmap-push.js), this one outlives the page: it is asked for with
 * a click, never on load, and nothing unsubscribes on pagehide, because the news it carries comes
 * days later. The same button turns it off (DELETE). Which FID this browser registered for this
 * order is kept in localStorage, a per-viewer convenience: lost, the button offers "Notify me"
 * again, and pressing it is the server's upsert, the same watch.
 *
 * Same modular Firebase as seatmap-push.js, pinned to the same version as it and push/sw.html
 * (src/test/js checks the three agree). With no push config, no service worker or no Notification
 * API, the button stays hidden and the page is as it was.
 *
 * Copy comes from #order-push's data-msg-* attributes (House style: a .js file is outside the
 * bundle's reach); each keeps an English fallback.
 */
import { initializeApp } from "https://www.gstatic.com/firebasejs/12.19.0/firebase-app.js";
import { getMessaging, register, onRegistered }
    from "https://www.gstatic.com/firebasejs/12.19.0/firebase-messaging.js";

(function () {
    "use strict";

    var el = document.getElementById("order-push");
    if (!el) {
        return;
    }
    var groupId = el.getAttribute("data-group-id");
    var button = document.getElementById("order-push-toggle");
    var statusEl = document.getElementById("order-push-status");
    var key = "order-push:" + groupId;
    var messaging = null;
    var busy = false;

    function msg(name, fallback) {
        var value = el.getAttribute("data-msg-" + name);
        return value === null || value === "" ? fallback : value;
    }

    function setStatus(text) {
        if (statusEl) {
            statusEl.textContent = text;
        }
    }

    /* Private windows and blocked storage throw here; the control then just forgets. */
    function stored() {
        try {
            return window.localStorage.getItem(key);
        } catch (e) {
            return null;
        }
    }

    function store(fid) {
        try {
            if (fid) {
                window.localStorage.setItem(key, fid);
            } else {
                window.localStorage.removeItem(key);
            }
        } catch (e) {
            /* see stored() */
        }
    }

    function show(on) {
        button.textContent = on ? msg("unwatch", "Stop notifying me about this order")
            : msg("watch", "Notify me about this order");
        button.hidden = false;
    }

    function failed() {
        setStatus(msg("failed", "Could not turn on notifications. Try again later."));
    }

    async function send(method, fid) {
        var response = await fetch("/orders/" + groupId + "/push", {
            method: method,
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ token: fid })
        });
        return response.ok;
    }

    async function turnOn(config) {
        var swReg = await navigator.serviceWorker.register("/firebase-messaging-sw.js");
        await navigator.serviceWorker.ready;
        if (await Notification.requestPermission() !== "granted") {
            setStatus(msg("refused", "Notifications are blocked for this site, so nothing can be sent."));
            return;
        }
        if (!messaging) {
            messaging = getMessaging(initializeApp({
                apiKey: config.apiKey,
                authDomain: config.authDomain,
                projectId: config.projectId,
                storageBucket: config.storageBucket,
                messagingSenderId: config.messagingSenderId,
                appId: config.appId
            }));
            /* Fires for register() below, and again when the worker re-registers a rotated FID
             * while this page is open: each is the same upsert, under the newest FID. */
            onRegistered(messaging, function (fid) {
                send("POST", fid).then(function (ok) {
                    if (!ok) {
                        failed();
                        return;
                    }
                    store(fid);
                    show(true);
                    setStatus(msg("on", "You'll be notified if the showing is cancelled or a refund is sent."));
                }, failed);
            });
        }
        await register(messaging, { vapidKey: config.vapidKey, serviceWorkerRegistration: swReg });
    }

    /* Flipped only once the server says the row is gone: a lost DELETE must not leave a button
     * saying "off" over a watch that still pushes. */
    async function turnOff(fid) {
        if (!(await send("DELETE", fid))) {
            failed();
            return;
        }
        store(null);
        show(false);
        setStatus(msg("stopped", "Notifications for this order are off."));
    }

    async function start() {
        if (!button || !("serviceWorker" in navigator) || !("Notification" in window) || !("PushManager" in window)) {
            return;
        }
        var config;
        try {
            config = await (await fetch("/push/config")).json();
        } catch (e) {
            return;
        }
        if (!config || !config.enabled || !config.vapidKey) {
            return;
        }
        show(!!stored());
        button.addEventListener("click", async function () {
            if (busy) {
                return;
            }
            busy = true;
            button.disabled = true;
            try {
                var fid = stored();
                if (fid) {
                    await turnOff(fid);
                } else {
                    await turnOn(config);
                }
            } catch (e) {
                failed();
            } finally {
                busy = false;
                button.disabled = false;
            }
        });
    }

    start();
})();
