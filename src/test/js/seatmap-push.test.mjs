/*
 * Behaviour test for seatmap-push.js — specifically the poll-interval throttling layered on top of
 * the push subscription: the 3s background poll backs off to 30s once a subscription is
 * confirmed live, and only then.
 *
 * Runs the REAL htmx.min.js and the REAL seatmap-push.js in jsdom. Notification, PushManager,
 * serviceWorker, Firebase and fetch are all stubbed on window — none exist in jsdom (fetch included,
 * which is worth knowing: seatmap-push.js's own /push/config call silently lands in its catch
 * without a stub, so the whole subscribe flow is skipped and every "stayed fast" assertion passes
 * vacuously). htmx itself uses XMLHttpRequest, so its swaps still hit the real local server.
 *
 * Each scenario gets a FRESH page, because start() runs once per load and several of these are
 * about how it terminates early.
 *
 * The point of the whole file: "notifications are on" is not the same claim as "notifications are
 * arriving". Permission can be granted and the subscription still never materialise — register()
 * rejects, the /push/subscribe POST fails — and a poll interval that backed off on permission
 * alone would leave the customer on a 30s-stale seat map with nothing pushing to it. Scenarios 3
 * and 4 pin exactly that. Scenario 2 pins the other regression: #seat-grid replaces itself
 * wholesale on every poll and the server always renders the fast trigger, so a throttle applied
 * once to the attribute gets silently reverted by the next poll.
 */
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";
import { JSDOM, VirtualConsole } from "jsdom";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const RESOURCES = path.resolve(HERE, "../../main/resources");
const WEB = path.join(RESOURCES, "META-INF/resources");

const htmxSrc = fs.readFileSync(path.join(WEB, "js/htmx.min.js"), "utf8");
const pushModule = fs.readFileSync(path.join(WEB, "js/seatmap-push.js"), "utf8");
const swTemplate = fs.readFileSync(path.join(RESOURCES, "templates/push/sw.html"), "utf8");

/* seatmap-push.js is an ES module importing the modular Firebase JS from gstatic, and jsdom runs no
 * modules. Each import becomes a read of window.__firebase (the stubs in pageHtml), keyed by file
 * name, so the rest of the script runs unchanged as a classic one, in a block for its own scope. */
const IMPORT = /^import \{([^}]+)\}\s*from "https:\/\/www\.gstatic\.com\/firebasejs\/([^/]+)\/([^"]+)";$/gm;
const firebaseVersions = [...pushModule.matchAll(IMPORT)].map((m) => m[2]);
const pushSrc = "{" + pushModule.replace(IMPORT,
    (_, names, version, file) => `const {${names}} = window.__firebase[${JSON.stringify(file)}];`) + "}";
const fragmentTemplate = fs.readFileSync(
    path.join(RESOURCES, "templates/customer/seatmapFragment.html"), "utf8");
const seatmapTemplate = fs.readFileSync(
    path.join(RESOURCES, "templates/customer/seatmap.html"), "utf8");

const bookingTemplate = fs.readFileSync(
    path.join(RESOURCES, "templates/customer/booking.html"), "utf8");

/* Deliberately not the English the script falls back to — see the assertion in scenario 1. */
const PUSH_COPY = {
    subscribed: "[[watching]]",
    off: "[[off]]",
    unavailable: "[[unavailable]]",
    notRegistered: "[[not registered]]"
};
const baseTemplate = fs.readFileSync(path.join(RESOURCES, "templates/base.html"), "utf8");

let failures = 0;
function check(name, actual, expected) {
    const ok = actual === expected;
    if (!ok) failures++;
    console.log(`  ${ok ? "PASS" : "FAIL"}  ${name}`);
    if (!ok) console.log(`        expected: ${expected}\n        actual:   ${actual}`);
}

/* Read out of the real template rather than hardcoded, so changing the interval or the trigger's
 * other events there fails this test instead of quietly going untested. */
const FAST_TRIGGER = fragmentTemplate.match(/id="seat-grid"[\s\S]*?hx-trigger="([^"]+)"/)[1];
const SLOW_TRIGGER = FAST_TRIGGER.replace("every 3s", "every 30s");

console.log("--- 0. the real template still renders the trigger this test mirrors ---");
check("template's seat-grid trigger starts with the 3s poll",
    FAST_TRIGGER.startsWith("every 3s"), true);
check("deriving the slow form actually changed something", SLOW_TRIGGER !== FAST_TRIGGER, true);
check("the template never renders the slow form itself",
    fragmentTemplate.includes('hx-trigger="every 30s'), false);

/* Source-level, and only source-level. These assert the two halves of the resource-hint work are
 * both still present and still paired; they cannot prove Qute actually places the hint into
 * <head>, because nothing here renders a template. That part rides on the same browser pass the
 * defer change needs. What this does catch is the cheap regression: someone deleting the slot in
 * base.html, or the {#head} block here, leaving the other half silently doing nothing. */
console.log("--- 0b. the resource hints for the third-party SDK are still wired up ---");
check("base.html still offers a head slot for pages to fill",
    baseTemplate.includes("{#insert head}"), true);
check("the seat map still fills it", seatmapTemplate.includes("{#head}"), true);
/* The four status lines moved out of the script and into the template. A dropped
 * attribute degrades to the script's English fallback rather than breaking, which is the right
 * failure but an invisible one — so it is asserted here instead of waited for. */
/* BOTH templates, not just the seat map. One script serves two pages — /halls/{pool}/showings/
 * {session} and /booking — and the page that breaks is always the second one, which is
 * exactly how the advisory banner broke /booking when it was added to the fragment
 * (AgeAdvisoryService's javadoc). A check that read only seatmap.html would have gone green
 * against a /booking page showing English status lines to an Indonesian customer. */
for (const [label, template] of [["seat map", seatmapTemplate], ["booking page", bookingTemplate]]) {
    for (const name of ["off", "unavailable", "not-registered", "subscribed"]) {
        check(`the ${label} hands the script data-msg-push-${name}`,
            new RegExp(`data-msg-push-${name}="\\{msg:[A-Za-z]+\\}"`).test(template), true);
    }
}
check("the preconnect keeps crossorigin, without which the socket is not reused",
    /<link rel="preconnect" href="https:\/\/www\.gstatic\.com" crossorigin>/.test(seatmapTemplate), true);
/* A module is deferred by nature, which is what the old three `defer`s were for. */
for (const [label, template] of [["seat map", seatmapTemplate], ["booking page", bookingTemplate]]) {
    check(`the ${label} loads seatmap-push.js as a module`,
        /<script type="module" src="\/js\/seatmap-push\.js[^"]*"><\/script>/.test(template), true);
    check(`the ${label} loads no compat Firebase, which Firebase says not to mix with register()`,
        template.includes("-compat.js"), false);
}
/* The worker re-registers the page's FID on pushsubscriptionchange, so the two must be one release. */
check("seatmap-push.js imports both Firebase files", firebaseVersions.length, 2);
check("both imports and the service worker name one Firebase version",
    new Set([...firebaseVersions,
        ...[...swTemplate.matchAll(/firebasejs\/([^/]+)\//g)].map((m) => m[1])]).size, 1);
/* Every web push carries a `notification` (PushNotificationService.sendToAll), which the Firebase
 * SDK shows itself. A worker that also showed one doubled each push (2026-11-06, Chrome 156 on
 * Android: one copy untagged, one tagged `seatmap`). Run the worker with stubs and hand it a push. */
{
    const shown = [], posted = [];
    let onBackground = null;
    const sw = {
        importScripts() {},
        firebase: { initializeApp() {}, messaging: () => ({ onBackgroundMessage: (h) => { onBackground = h; } }) },
        registration: { showNotification: async (...args) => { shown.push(args); } },
        clients: { matchAll: async () => [{ postMessage: (m) => posted.push(m) }] },
        addEventListener() {}
    };
    sw.self = sw;
    vm.runInNewContext(swTemplate, sw);
    await onBackground({ notification: { title: "Refund sent", body: "Rp 50.000" }, data: { type: "order", groupId: "g" } });
    check("the service worker shows no second notification for a push the SDK already shows", shown.length, 0);
    check("the service worker still tells the open pages about the push", posted.length, 1);
}
/* The optional {asset:v(...)} is the content-hash cache-busting suffix every local script now
 * carries. What this still asserts is the part that matters — the tag has no `defer`, so it runs
 * before the three deferred ones above. Matching the filename alone would pass even if someone
 * added defer, which is the regression this line exists to catch. */
check("seatmap-selection.js is deliberately left undeferred, so it still runs first",
    /<script src="\/js\/seatmap-selection\.js(\{asset:v\([^)]*\)\})?"><\/script>/.test(seatmapTemplate), true);

function gridMarkup() {
    return `<div class="seat-grid" id="seat-grid" hx-get="/fragment" hx-trigger="${FAST_TRIGGER}"
         hx-select="#seat-grid" hx-target="this" hx-swap="outerHTML">grid content</div>`;
}

/* `opts` is inlined into the page as the stub's configuration: permission is what
 * Notification.requestPermission() resolves to, fids is what register() hands onRegistered, in call
 * order (null = register() rejects), subscribeOk is whether POST /push/subscribe answers ok. */
function pageHtml(opts) {
    return `<!doctype html><html><head><script src="/js/htmx.min.js"></script></head><body>
      <div id="seat-map-container">${gridMarkup()}</div>
      <div id="seatmap-push" data-pool-id="pool-1" data-session-id="sess-1"
           data-msg-push-subscribed="${PUSH_COPY.subscribed}"
           data-msg-push-off="${PUSH_COPY.off}"
           data-msg-push-unavailable="${PUSH_COPY.unavailable}"
           data-msg-push-not-registered="${PUSH_COPY.notRegistered}"></div>
      <p id="seatmap-push-status"></p>
      <p id="seatmap-ios-hint" hidden></p>
      <script>
        window.__opts = ${JSON.stringify(opts)};
        window.__swMessageListeners = [];

        window.PushManager = function () {};
        window.Notification = function () {};
        window.Notification.requestPermission = () => Promise.resolve(window.__opts.permission);

        navigator.serviceWorker = {
            register: () => Promise.resolve({}),
            ready: Promise.resolve(),
            addEventListener: (type, cb) => {
                if (type === "message") window.__swMessageListeners.push(cb);
            }
        };

        /* jsdom has no fetch. Only seatmap-push.js's own calls go through here; htmx uses XHR. */
        window.fetch = (url) => {
            if (url === "/push/config") {
                return Promise.resolve({ json: () => Promise.resolve({
                    enabled: true, vapidKey: "vapid-key", apiKey: "a", authDomain: "a",
                    projectId: "a", storageBucket: "a", messagingSenderId: "a", appId: "a"
                }) });
            }
            if (url === "/push/subscribe") {
                return Promise.resolve({ ok: window.__opts.subscribeOk });
            }
            return Promise.resolve({ ok: true, json: () => Promise.resolve({}) });
        };

        /* The modular API as seatmap-push.js imports it. register() calls the onRegistered
         * handler before resolving, as 12.19.0's does; __onRegistered is also how a scenario plays
         * the service worker re-registering after pushsubscriptionchange. */
        window.__firebase = {
            "firebase-app.js": { initializeApp: () => ({}) },
            "firebase-messaging.js": {
                getMessaging: () => ({}),
                onMessage: () => {},
                onRegistered: (messaging, cb) => { window.__onRegistered = cb; },
                register: () => {
                    const fid = window.__opts.fids.length ? window.__opts.fids.shift() : null;
                    if (!fid) return Promise.reject(new Error("messaging/token-subscribe-failed"));
                    window.__onRegistered(fid);
                    return Promise.resolve();
                }
            }
        };
      </script>
      <script src="/js/seatmap-push.js"></script>
    </body></html>`;
}

let currentOpts = null;
const server = http.createServer((req, res) => {
    const js = { "/js/htmx.min.js": htmxSrc, "/js/seatmap-push.js": pushSrc }[req.url];
    if (js) {
        res.writeHead(200, { "Content-Type": "text/javascript;charset=UTF-8" });
        return res.end(js);
    }
    if (req.url === "/fragment") {
        /* What the real endpoint does on every single poll: re-render the grid straight from the
         * template, fast trigger baked in, indifferent to anything the client wanted. */
        res.writeHead(200, { "Content-Type": "text/html;charset=UTF-8" });
        return res.end(`<div id="seat-map-container">${gridMarkup()}</div>`);
    }
    res.writeHead(200, { "Content-Type": "text/html;charset=UTF-8" });
    res.end(pageHtml(currentOpts));
});

await new Promise((r) => server.listen(0, "127.0.0.1", r));
const port = server.address().port;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function launch(opts) {
    currentOpts = opts;
    const virtualConsole = new VirtualConsole();
    virtualConsole.on("jsdomError", (e) => {
        console.log("  [jsdom error]", e.message);
        failures++;   // a script that failed to run must never look like a pass
    });
    const dom = await JSDOM.fromURL(`http://127.0.0.1:${port}/`, {
        runScripts: "dangerously",
        resources: "usable",
        pretendToBeVisual: true,
        virtualConsole,
        /* Same jsdom XPath gap seatmap-selection.test.mjs documents: htmx's hx-on-* scan throws
         * without this and nothing is ever wired. */
        beforeParse(w) {
            const proto = w.XPathExpression && w.XPathExpression.prototype;
            if (proto && proto.evaluate) {
                const orig = proto.evaluate;
                proto.evaluate = function (ctx, type, result) {
                    return orig.call(this, ctx, type || 5, result ?? null);
                };
            }
        }
    });
    dom.window.addEventListener("unhandledrejection", (e) => {
        console.log("  [unhandled rejection]", e.reason);
        failures++;
    });
    await sleep(400);   // htmx + seatmap-push.js load, start() runs to completion
    return dom;
}

const triggerOf = (dom) =>
    dom.window.document.getElementById("seat-grid").getAttribute("hx-trigger");

console.log("\n--- 1. a confirmed subscription is what earns the slower poll ---");
let dom = await launch({ permission: "granted", fids: ["fid-1"], subscribeOk: true });
check("hx-trigger backs off to 30s once /push/subscribe confirms", triggerOf(dom), SLOW_TRIGGER);
/* The marked string, not the English one the script falls back to: if the expectation here were
 * the fallback, this would pass just as happily against a script that never read the attribute,
 * which is exactly the regression worth catching now that the copy lives in the template. */
check("the status line says notifications are on, in the words the template handed it",
    dom.window.document.getElementById("seatmap-push-status").textContent,
    PUSH_COPY.subscribed);

console.log("\n--- 2. the slower poll survives the grid replacing itself ---");
/* The regression this scenario exists for: drive a real outerHTML swap the way the poll timer
 * would, and confirm the freshly swapped-in node — which arrived from the server carrying the FAST
 * trigger — is corrected back rather than left on whatever the server just sent. */
dom.window.htmx.trigger(dom.window.document.getElementById("seat-grid"), "seat-availability-changed");
await sleep(400);
check("grid really was replaced by the swap",
    dom.window.document.getElementById("seat-grid").textContent.trim(), "grid content");
check("still 30s after a poll response re-rendered the fast trigger", triggerOf(dom), SLOW_TRIGGER);
dom.window.close();

console.log("\n--- 3. permission granted but register() fails: nothing is subscribed, so keep polling fast ---");
dom = await launch({ permission: "granted", fids: [null], subscribeOk: true });
check("hx-trigger stays on the 3s poll when register() rejects",
    triggerOf(dom), FAST_TRIGGER);
check("the status line says notifications are unavailable",
    dom.window.document.getElementById("seatmap-push-status").textContent,
    PUSH_COPY.unavailable);
dom.window.close();

console.log("\n--- 4. permission granted but the subscribe POST fails: still no live watch ---");
/* The exact case that makes "gate on Notification.permission" wrong: the customer said yes, the
 * browser handed over an FID, and the server never recorded the watch. Nothing will be pushed. */
dom = await launch({ permission: "granted", fids: ["fid-1"], subscribeOk: false });
check("hx-trigger stays on the 3s poll when /push/subscribe is refused",
    triggerOf(dom), FAST_TRIGGER);
check("the status line admits it did not register",
    dom.window.document.getElementById("seatmap-push-status").textContent,
    PUSH_COPY.notRegistered);
dom.window.close();

console.log("\n--- 5. permission declined: the poll is the only mechanism left, so leave it fast ---");
dom = await launch({ permission: "denied", fids: ["fid-1"], subscribeOk: true });
check("hx-trigger stays on the 3s poll with notifications declined", triggerOf(dom), FAST_TRIGGER);
dom.window.close();

console.log("\n--- 6. a stale subscription drops straight back to fast polling ---");
dom = await launch({ permission: "granted", fids: ["fid-1"], subscribeOk: true });
check("precondition: slowed down after the initial subscribe", triggerOf(dom), SLOW_TRIGGER);
/* The worker's re-registration never reports back, so nothing confirms a replacement. */
dom.window.__swMessageListeners.forEach((cb) => cb({ data: { type: "push-subscription-changed" } }));
/* Synchronously — the map must not sit on a 30s poll for however long the worker's
 * re-registration takes, or if it never completes. */
check("reverts to 3s the instant the subscription is known stale", triggerOf(dom), FAST_TRIGGER);
await sleep(400);
check("stays on 3s because no re-registration confirmed a replacement",
    triggerOf(dom), FAST_TRIGGER);
dom.window.close();

console.log("\n--- 7. a successful re-subscribe earns the slower poll back ---");
dom = await launch({ permission: "granted", fids: ["fid-1"], subscribeOk: true });
dom.window.__swMessageListeners.forEach((cb) => cb({ data: { type: "push-subscription-changed" } }));
check("dropped to 3s while the replacement is pending", triggerOf(dom), FAST_TRIGGER);
/* What the SDK's worker then does: re-register and post the FID to the page's onRegistered. */
dom.window.__onRegistered("fid-2");
await sleep(400);
check("back to 30s once the replacement subscription confirms", triggerOf(dom), SLOW_TRIGGER);
dom.window.close();

console.log(failures === 0 ? "\nALL CHECKS PASSED" : `\n${failures} CHECK(S) FAILED`);
server.close();
process.exit(failures === 0 ? 0 : 1);
