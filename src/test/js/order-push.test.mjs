/*
 * Behaviour test for order-push.js, "Notify me about this order" on the order page.
 *
 * Same harness as seatmap-push.test.mjs: the REAL htmx.min.js and order-push.js in jsdom, the two
 * gstatic imports turned into reads of window.__firebase, and Notification, serviceWorker, fetch
 * and localStorage's contents set per scenario. What it pins: the button is hidden unless push is
 * configured; a click asks once and turns on only when the server took the watch; the same button
 * turns it off only when the DELETE answered; the stored FID brings the "Stop" state back on a
 * reload; and the fragment's 3 s poll swapping #reservation-status leaves the control as it was
 * (hx-preserve), which is why the template carries it.
 */
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { JSDOM, VirtualConsole } from "jsdom";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const RESOURCES = path.resolve(HERE, "../../main/resources");
const WEB = path.join(RESOURCES, "META-INF/resources");

const htmxSrc = fs.readFileSync(path.join(WEB, "js/htmx.min.js"), "utf8");
const pushModule = fs.readFileSync(path.join(WEB, "js/order-push.js"), "utf8");
const seatmapModule = fs.readFileSync(path.join(WEB, "js/seatmap-push.js"), "utf8");
const fragmentTemplate = fs.readFileSync(path.join(RESOURCES, "templates/customer/reservationFragment.html"), "utf8");
const pageTemplate = fs.readFileSync(path.join(RESOURCES, "templates/customer/reservation.html"), "utf8");

const IMPORT = /^import \{([^}]+)\}\s*from "https:\/\/www\.gstatic\.com\/firebasejs\/([^/]+)\/([^"]+)";$/gm;
const versions = (src) => [...src.matchAll(IMPORT)].map((m) => m[2]);
const pushSrc = "{" + pushModule.replace(IMPORT,
    (_, names, version, file) => `const {${names}} = window.__firebase[${JSON.stringify(file)}];`) + "}";

let failures = 0;
function check(name, actual, expected) {
    const ok = actual === expected;
    if (!ok) failures++;
    console.log(`  ${ok ? "PASS" : "FAIL"}  ${name}`);
    if (!ok) console.log(`        expected: ${expected}\n        actual:   ${actual}`);
}

console.log("--- 0. the templates hand the script what it reads ---");
for (const name of ["watch", "unwatch", "on", "stopped", "refused", "failed"]) {
    check(`#order-push carries data-msg-${name} from the bundle`,
        new RegExp(`data-msg-${name}="\\{msg:[A-Za-z]+\\}"`).test(fragmentTemplate), true);
}
check("#order-push is preserved across the fragment's poll",
    /<div id="order-push" hx-preserve="true"/.test(fragmentTemplate), true);
check("the button starts hidden", /<button type="button" id="order-push-toggle"[^>]* hidden>/.test(fragmentTemplate), true);
check("the order page loads order-push.js as a module",
    /<script type="module" src="\/js\/order-push\.js[^"]*"><\/script>/.test(pageTemplate), true);
check("order-push.js imports both Firebase files", versions(pushModule).length, 2);
check("order-push.js names the Firebase version seatmap-push.js does",
    new Set([...versions(pushModule), ...versions(seatmapModule)]).size, 1);

const COPY = { watch: "[[watch]]", unwatch: "[[unwatch]]", on: "[[on]]", stopped: "[[stopped]]",
    refused: "[[refused]]", failed: "[[failed]]" };

function fragment(n) {
    return `<div id="reservation-status" hx-get="/fragment" hx-trigger="poll-now" hx-swap="outerHTML">
      <p id="swap-count">${n}</p>
      <div id="order-push" hx-preserve="true" data-group-id="g-1"
        ${Object.entries(COPY).map(([k, v]) => `data-msg-${k}="${v}"`).join(" ")}>
        <button type="button" id="order-push-toggle" hidden>${COPY.watch}</button>
        <p id="order-push-status"></p>
      </div></div>`;
}

/* opts: enabled (/push/config), permission, postOk, deleteOk, stored (a FID already saved). */
function pageHtml(opts) {
    return `<!doctype html><html><head><script src="/js/htmx.min.js"></script></head><body>
      ${fragment(0)}
      <script>
        window.__opts = ${JSON.stringify(opts)};
        window.__calls = [];
        if (window.__opts.stored) localStorage.setItem("order-push:g-1", window.__opts.stored);
        window.PushManager = function () {};
        window.Notification = function () {};
        window.Notification.requestPermission = () => { window.__calls.push("permission"); return Promise.resolve(window.__opts.permission); };
        navigator.serviceWorker = { register: () => Promise.resolve({}), ready: Promise.resolve() };
        window.fetch = (url, init) => {
            if (url === "/push/config") return Promise.resolve({ json: () => Promise.resolve({
                enabled: window.__opts.enabled, vapidKey: "v", apiKey: "a", authDomain: "a",
                projectId: "a", storageBucket: "a", messagingSenderId: "a", appId: "a" }) });
            window.__calls.push(init.method + " " + url + " " + init.body);
            return Promise.resolve({ ok: init.method === "POST" ? window.__opts.postOk : window.__opts.deleteOk });
        };
        window.__firebase = {
            "firebase-app.js": { initializeApp: () => ({}) },
            "firebase-messaging.js": {
                getMessaging: () => ({}),
                onRegistered: (m, cb) => { window.__onRegistered = cb; },
                register: () => { window.__onRegistered("fid-1"); return Promise.resolve(); }
            }
        };
      </script>
      <script src="/js/order-push.js"></script>
    </body></html>`;
}

let currentOpts = null;
let swaps = 0;
const server = http.createServer((req, res) => {
    const js = { "/js/htmx.min.js": htmxSrc, "/js/order-push.js": pushSrc }[req.url];
    if (js) {
        res.writeHead(200, { "Content-Type": "text/javascript;charset=UTF-8" });
        return res.end(js);
    }
    res.writeHead(200, { "Content-Type": "text/html;charset=UTF-8" });
    res.end(req.url === "/fragment" ? fragment(++swaps) : pageHtml(currentOpts));
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const port = server.address().port;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function launch(opts) {
    currentOpts = opts;
    const virtualConsole = new VirtualConsole();
    virtualConsole.on("jsdomError", (e) => { console.log("  [jsdom error]", e.message); failures++; });
    const dom = await JSDOM.fromURL(`http://127.0.0.1:${port}/`, {
        runScripts: "dangerously", resources: "usable", pretendToBeVisual: true, virtualConsole,
        beforeParse(w) {
            const proto = w.XPathExpression && w.XPathExpression.prototype;
            if (proto && proto.evaluate) {
                const orig = proto.evaluate;
                proto.evaluate = function (ctx, type, result) { return orig.call(this, ctx, type || 5, result ?? null); };
            }
        }
    });
    dom.window.addEventListener("unhandledrejection", (e) => { console.log("  [unhandled rejection]", e.reason); failures++; });
    await sleep(400);
    return dom;
}
const $ = (dom, id) => dom.window.document.getElementById(id);
const click = async (dom) => { $(dom, "order-push-toggle").click(); await sleep(200); };
const stored = (dom) => dom.window.localStorage.getItem("order-push:g-1");

console.log("\n--- 1. push not configured: the control never shows ---");
let dom = await launch({ enabled: false });
check("the button stays hidden", $(dom, "order-push-toggle").hidden, true);
dom.window.close();

console.log("\n--- 2. a click turns it on, and the poll's swap keeps it ---");
dom = await launch({ enabled: true, permission: "granted", postOk: true, deleteOk: true });
check("the button shows, offering the watch", $(dom, "order-push-toggle").hidden, false);
check("in the template's words", $(dom, "order-push-toggle").textContent, COPY.watch);
check("nothing was asked on load", dom.window.__calls.length, 0);
await click(dom);
check("one permission prompt, then the FID POSTed for this order", dom.window.__calls.join(" | "),
    'permission | POST /orders/g-1/push {"token":"fid-1"}');
check("the button now turns it off", $(dom, "order-push-toggle").textContent, COPY.unwatch);
check("the status line says it is on", $(dom, "order-push-status").textContent, COPY.on);
check("the FID is remembered for this order", stored(dom), "fid-1");
dom.window.htmx.trigger($(dom, "reservation-status"), "poll-now");
await sleep(300);
check("the fragment really was swapped", $(dom, "swap-count").textContent, "1");
check("still the off button after the swap", $(dom, "order-push-toggle").textContent, COPY.unwatch);
check("still shown after the swap", $(dom, "order-push-toggle").hidden, false);

console.log("\n--- 3. the same button turns it off ---");
await click(dom);
check("DELETE with the stored FID", dom.window.__calls.at(-1), 'DELETE /orders/g-1/push {"token":"fid-1"}');
check("back to offering the watch", $(dom, "order-push-toggle").textContent, COPY.watch);
check("the status line says it is off", $(dom, "order-push-status").textContent, COPY.stopped);
check("the FID is forgotten", stored(dom), null);
dom.window.close();

console.log("\n--- 4. a reload with the FID stored offers the off button, and a failed DELETE keeps it ---");
dom = await launch({ enabled: true, permission: "granted", postOk: true, deleteOk: false, stored: "fid-9" });
check("the off button, before any click", $(dom, "order-push-toggle").textContent, COPY.unwatch);
await click(dom);
check("DELETE sent for the stored FID", dom.window.__calls.at(-1), 'DELETE /orders/g-1/push {"token":"fid-9"}');
check("still the off button: the server did not take it", $(dom, "order-push-toggle").textContent, COPY.unwatch);
check("the status line says it failed", $(dom, "order-push-status").textContent, COPY.failed);
check("the FID is kept", stored(dom), "fid-9");
dom.window.close();

console.log("\n--- 5. permission refused: nothing is sent ---");
dom = await launch({ enabled: true, permission: "denied", postOk: true, deleteOk: true });
await click(dom);
check("no POST", dom.window.__calls.join(" | "), "permission");
check("the status line says notifications are blocked", $(dom, "order-push-status").textContent, COPY.refused);
check("still offering the watch", $(dom, "order-push-toggle").textContent, COPY.watch);
dom.window.close();

console.log("\n--- 6. the server refuses the watch: it stays off ---");
dom = await launch({ enabled: true, permission: "granted", postOk: false, deleteOk: true });
await click(dom);
check("still offering the watch", $(dom, "order-push-toggle").textContent, COPY.watch);
check("the status line says it failed", $(dom, "order-push-status").textContent, COPY.failed);
check("nothing remembered", stored(dom), null);
dom.window.close();

console.log(failures === 0 ? "\nALL CHECKS PASSED" : `\n${failures} CHECK(S) FAILED`);
server.close();
process.exit(failures === 0 ? 0 : 1);
