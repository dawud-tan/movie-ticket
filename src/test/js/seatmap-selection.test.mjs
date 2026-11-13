/*
 * Behaviour test for seatmap-selection.js — the notice that names a seat taken out from under a
 * customer mid-selection.
 *
 * Runs the REAL htmx.min.js and the REAL seatmap-selection.js in jsdom, against a local server that
 * serves the same markup the Qute fragment produces, and drives real htmx swaps. It is a browser
 * test in a Maven build because the logic under test exists only in the browser: the seat revert
 * happens through htmx's hx-preserve semantics during a swap, and no JVM test can reach it. The
 * Java suite passed in full while this behaviour was entirely absent.
 *
 * Two traps this file exists to stay ahead of, both of which produced false results while it was
 * being written:
 *
 *   1. Vacuous passes. jsdom rejects XPathExpression.evaluate() with the default ANY_TYPE that real
 *      browsers accept; htmx uses exactly that while scanning for hx-on-* attributes, so its init
 *      threw, NOTHING was ever wired, no swap happened, and the "tick was preserved" assertions
 *      passed because the DOM was never touched. Hence the shim below AND assertRequestsHappened().
 *   2. Drift. The markup here mirrors seatmapFragment.html by hand, because that template is Qute
 *      with loops and cannot be rendered without Quarkus. assertTemplateInvariants() reads the real
 *      template and fails if the properties this mirror depends on stop holding.
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
const selectionSrc = fs.readFileSync(path.join(WEB, "js/seatmap-selection.js"), "utf8");
const fragmentTemplate = fs.readFileSync(
    path.join(RESOURCES, "templates/customer/seatmapFragment.html"), "utf8");
const indonesian = fs.readFileSync(path.join(RESOURCES, "messages/msg_id.properties"), "utf8");

/*
 * The copy this test hands the script, and it is deliberately NOT the English the script falls
 * back to.
 *
 * Same discipline as SIGNIN_URL below: if the expected value were the fallback, every assertion
 * would pass just as well against a script that ignored the attribute entirely — which is the
 * regression this file exists to catch, now that the sentences live in the template. Marked
 * strings that no fallback could ever produce mean a pass is proof the attribute was read.
 *
 * %seats% and %status% are the script's own placeholders; see Msg.java for why they are not
 * Qute's {…}.
 */
const COPY = {
    "taken-one": "[[one %seats% gone]]",
    "taken-many": "[[many %seats% gone]]",
    "held-one": "[[one %seats% held]]",
    "held-many": "[[many %seats% held]]",
    "and": "&&",
    "others-selected": "[[rest kept]]",
    "session-expired": "[[expired]]",
    "sign-in-again": "[[sign in]]",
    "not-permitted": "[[not allowed]]",
    "sign-out": "[[sign out]]",
    "server-error": "[[broke %status%]]",
    "unreachable": "[[unreachable]]"
};
const copyAttrs = Object.entries(COPY)
    .map(([name, value]) => `data-msg-${name}="${value}"`).join("\n           ");

let failures = 0;
function check(name, actual, expected) {
    const ok = actual === expected;
    if (!ok) failures++;
    console.log(`  ${ok ? "PASS" : "FAIL"}  ${name}`);
    if (!ok) console.log(`        expected: ${expected}\n        actual:   ${actual}`);
}

/* The mirror below is only as good as these properties of the real template. */
function assertTemplateInvariants() {
    console.log("--- 0. the real template still has the properties this test mirrors ---");
    check("available seat carries hx-preserve",
        /hx-preserve="true"/.test(fragmentTemplate), true);
    check("available seat carries id=\"seat-{...}\"",
        /id="seat-\{seat\.resourceUnitId\}"/.test(fragmentTemplate), true);
    check("available seat wraps a seatIds checkbox",
        /name="seatIds"/.test(fragmentTemplate), true);
    // The revert depends entirely on the taken branch having NEITHER an id nor hx-preserve: that is
    // what leaves htmx nothing to preserve, so the ticked label is dropped.
    const takenBranch = fragmentTemplate.slice(fragmentTemplate.indexOf("{#else}"));
    const takenSpan = takenBranch.slice(0, takenBranch.indexOf("{/if}"));
    check("taken seat has NO id", /id=/.test(takenSpan), false);
    check("taken seat has NO hx-preserve", /hx-preserve/.test(takenSpan), false);
    /* Those two absences are also why the notice cannot look the seat up by id, and reads the span
     * matching the visible label instead. Both of the properties that lookup depends on: */
    check("taken seat carries its status as a class",
        /class="seat \{seat\.seatStatus\}"/.test(takenSpan), true);
    check("taken seat's text is the seat label", />\{seat\.label\}</.test(takenSpan), true);
    check("notice element exists outside the swapped grid",
        fragmentTemplate.indexOf('id="seat-taken-notice"') < fragmentTemplate.indexOf('id="seat-grid"'),
        true);

    /* The error banner has the same placement requirement as the notice, for the same reason: the
     * poll swaps only #seat-grid, so anything inside it is wiped seconds after being written. */
    check("error banner exists outside the swapped grid",
        fragmentTemplate.indexOf('id="seat-error-notice"') > -1
            && fragmentTemplate.indexOf('id="seat-error-notice"') < fragmentTemplate.indexOf('id="seat-grid"'),
        true);
    /* The sign-in href must come from the template, which is the only layer that knows whether this
     * deployment needs an absolute https origin. A "/login" that reappeared in the JS would work in
     * dev and send an http:8080 visitor to a dead port in production — see SignInLink. */
    check("error banner carries the injected sign-in url",
        /id="seat-error-notice"[^>]*data-signin-url="\{inject:signIn\.url\}"/s.test(fragmentTemplate),
        true);
    check("no hardcoded login path in the script",
        /["']\/login["']/.test(selectionSrc), false);

    /* --- the copy handed in through data-msg-* ------------------------------------------
     * The sentences moved out of the script and into the template, so the mirror
     * above is now mirroring attributes as well as structure. These are the properties that
     * makes that mirror honest. */
    for (const name of Object.keys(COPY)) {
        check(`template hands the script data-msg-${name}`,
            new RegExp(`data-msg-${name}="\\{msg:[A-Za-z]+\\}"`).test(fragmentTemplate), true);
    }
    /* The script keeps an English fallback for every one of them, so a dropped attribute
     * degrades to a stale language rather than to the string "undefined" on a customer's
     * screen. What it must NOT do is keep a sentence it uses unconditionally. */
    check("every sentence in the script is a fallback, never a direct use",
        /\btextContent = "[A-Z]/.test(selectionSrc), false);

    /* Placeholders are the script's own, not Qute's, and both halves have to agree on the
     * spelling or the seat labels silently never get substituted in. */
    check("the Indonesian keeps the %seats% placeholder",
        /^jsSeatTakenOne=.*%seats%/m.test(indonesian), true);
    check("the Indonesian keeps the %status% placeholder",
        /^jsHoldServerError=.*%status%/m.test(indonesian), true);
    /* Indonesian marks no plural, so its two forms are the same sentence — which is the honest
     * encoding rather than a copy-paste slip, and is worth pinning so nobody "fixes" it. */
    const one = /^jsSeatTakenOne=(.*)$/m.exec(indonesian);
    const many = /^jsSeatTakenMany=(.*)$/m.exec(indonesian);
    check("Indonesian singular and plural are deliberately identical",
        one !== null && many !== null && one[1] === many[1], true);
}

const SEATS = [
    { id: "11111111-1111-1111-1111-111111111111", label: "A1" },
    { id: "22222222-2222-2222-2222-222222222222", label: "A2" },
    { id: "33333333-3333-3333-3333-333333333333", label: "A3" }
];

/* status is null (available), "held" or "confirmed" — the same three the real fragment emits, since
 * the notice now reads the class to decide whether the seat may come back. */
function seatMarkup(seat, status) {
    return status
        ? `<span class="seat ${status}" title="${seat.label} (${status})">${seat.label}</span>`
        : `<label class="seat available" title="${seat.label}" id="seat-${seat.id}" hx-preserve="true">
             <input type="checkbox" name="seatIds" value="${seat.id}"> ${seat.label}
           </label>`;
}

function statusIn(seat, confirmed, held) {
    if (held.includes(seat.id)) return "held";
    if (confirmed.includes(seat.id)) return "confirmed";
    return null;
}

function grid(confirmed, held = []) {
    return `<div class="seat-grid" id="seat-grid"
         hx-get="/fragment" hx-trigger="seat-availability-changed"
         hx-select="#seat-grid" hx-target="this" hx-swap="outerHTML">
      <div class="seat-row">${SEATS.map(s => seatMarkup(s, statusIn(s, confirmed, held))).join("")}</div>
    </div>`;
}

/* Absolute and obviously not "/login", so the assertions below prove the href was read out of the
 * data attribute rather than hardcoded in the script. This is what PUBLIC_HTTPS_BASE_URL produces
 * on the on-prem deployment. */
const SIGNIN_URL = "https://tickets.example:8443/login";

function container(confirmed, held = []) {
    return `<div id="seat-map-container">
      <div id="seat-taken-notice" class="notice-banner" role="status" aria-live="polite" hidden
           ${copyAttrs}></div>
      <div id="seat-error-notice" class="error-banner" role="alert" hidden
           data-signin-url="${SIGNIN_URL}"
           ${copyAttrs}></div>
      <form hx-post="/hold" hx-target="#seat-map-container" hx-swap="outerHTML">
        ${grid(confirmed, held)}
      </form>
    </div>`;
}

let takenIds = [];
let heldIds = [];
let holdResponse = null;
/* Non-200 makes the corresponding endpoint answer with a bare status and no fragment, which is what
 * the security layer does: the resource method never runs, so there is nothing to swap. */
let holdStatus = 200;
let fragmentStatus = 200;
const hits = [];

/*
 * charset=UTF-8 on every response, because the real server sends it and omitting it here produced a
 * failure that looked like an app bug and was not: the script's user-facing strings contain an em
 * dash, jsdom fell back to latin-1 without the parameter, and the banner asserted as "â€”". Verified
 * against the deployment — Quarkus serves static .js as "text/javascript;charset=UTF-8" — so the
 * harness was the thing that diverged. A mirror that decodes differently from production can only
 * produce false results in one direction or the other.
 */
const UTF8_JS = { "Content-Type": "text/javascript;charset=UTF-8" };
const UTF8_HTML = { "Content-Type": "text/html;charset=UTF-8" };

const server = http.createServer((req, res) => {
    if (!req.url.startsWith("/js/")) hits.push(req.method + " " + req.url);
    const js = { "/js/htmx.min.js": htmxSrc, "/js/seatmap-selection.js": selectionSrc }[req.url];
    if (js) {
        res.writeHead(200, UTF8_JS);
        return res.end(js);
    }
    if (req.url === "/fragment") {
        if (fragmentStatus !== 200) {
            res.writeHead(fragmentStatus, { "Content-Type": "text/plain" });
            return res.end("refused");
        }
        res.writeHead(200, UTF8_HTML);
        return res.end(container(takenIds, heldIds));
    }
    if (req.url === "/hold") {
        if (holdStatus !== 200) {
            res.writeHead(holdStatus, { "Content-Type": "text/plain" });
            return res.end("refused");
        }
        res.writeHead(200, UTF8_HTML);
        return res.end(holdResponse);
    }
    res.writeHead(200, UTF8_HTML);
    res.end(`<!doctype html><html><head><script src="/js/htmx.min.js"></script></head><body>
        ${container([])}
        <script src="/js/seatmap-selection.js"></script>
      </body></html>`);
});

await new Promise(r => server.listen(0, "127.0.0.1", r));
const port = server.address().port;

const virtualConsole = new VirtualConsole();
virtualConsole.on("jsdomError", e => {
    console.log("  [jsdom error]", e.message);
    failures++;   // htmx failing to initialise must never look like a pass
});

const dom = await JSDOM.fromURL(`http://127.0.0.1:${port}/`, {
    runScripts: "dangerously",
    resources: "usable",
    pretendToBeVisual: true,
    virtualConsole,
    /* jsdom rejects XPathExpression.evaluate() with the default ANY_TYPE (0), which real browsers
     * accept, and htmx uses exactly that to scan for hx-on-* attributes during node processing.
     * Without this shim htmx's init throws and nothing is ever wired. A jsdom gap, not an app bug. */
    beforeParse(w) {
        const proto = w.XPathExpression && w.XPathExpression.prototype;
        if (proto && proto.evaluate) {
            const orig = proto.evaluate;
            proto.evaluate = function (ctx, type, result) {
                return orig.call(this, ctx, type || 5 /* UNORDERED_NODE_ITERATOR_TYPE */, result ?? null);
            };
        }
    }
});

const { window } = dom;
const doc = window.document;
const sleep = ms => new Promise(r => setTimeout(r, ms));
const seatOf = label => SEATS.find(s => s.label === label);
const boxOf = label => doc.querySelector(`input[value="${seatOf(label).id}"]`);

function tick(label) {
    const box = boxOf(label);
    box.checked = true;
    box.dispatchEvent(new window.Event("change", { bubbles: true }));
}
async function refresh() {
    window.htmx.trigger(doc.getElementById("seat-grid"), "seat-availability-changed");
    await sleep(400);
}
const notice = () => doc.getElementById("seat-taken-notice");
const noticeText = () => (notice().hidden ? "(hidden)" : notice().textContent.trim());
const isChecked = label => (boxOf(label) ? boxOf(label).checked : "no checkbox");

const errorNotice = () => doc.getElementById("seat-error-notice");
const errorText = () => (errorNotice().hidden ? "(hidden)" : errorNotice().textContent.trim());
/* href and label together: a link to the right place with no readable label, or a sensible label
 * pointing at the wrong origin, are both failures worth seeing separately in the output. */
const errorLink = () => {
    const a = errorNotice().querySelector("a");
    return a ? `${a.getAttribute("href")} | ${a.textContent}` : "(no link)";
};

/* The hold submit and the manual Refresh button, both of which replace the whole container. */
async function hold() {
    window.htmx.ajax("POST", "/hold", { target: "#seat-map-container", swap: "outerHTML" });
    await sleep(400);
}
async function fullRefresh() {
    window.htmx.ajax("GET", "/fragment", { target: "#seat-map-container", swap: "outerHTML" });
    await sleep(400);
}

await sleep(600);   // let htmx and the selection script load and bind

assertTemplateInvariants();

console.log("\n--- 1. htmx is really wired, and a tick survives a no-op refresh ---");
tick("A1");
tick("A2");
await refresh();
/* Guards against the vacuous-pass mode: if htmx never issued the request, "still ticked" below is
 * meaningless because nothing was swapped. */
check("the refresh actually reached the server", hits.includes("GET /fragment"), true);
check("A1 still ticked", isChecked("A1"), true);
check("A2 still ticked", isChecked("A2"), true);
check("no notice when nothing was taken", noticeText(), "(hidden)");

console.log("\n--- 2. someone else confirms A1 while it is ticked ---");
takenIds = [seatOf("A1").id];
await refresh();
check("A1 rendered as a taken span", doc.querySelectorAll("#seat-grid span.seat").length, 1);
check("A1's checkbox is gone from the form", isChecked("A1"), "no checkbox");
check("A2 untouched and still ticked", isChecked("A2"), true);
check("notice names A1", noticeText(),
    "[[one A1 gone]] [[rest kept]]");

console.log("\n--- 3. a second seat goes in a later swap: names accumulate ---");
takenIds = [seatOf("A1").id, seatOf("A2").id];
await refresh();
check("notice names both, not just the newest", noticeText(),
    "[[many A1 && A2 gone]] [[rest kept]]");

/*
 * A held seat is someone else's 10-minute claim that the expiry sweep may hand straight back, and
 * the map already draws it in its own colour. Calling that "taken" is the difference between a
 * customer waiting for the seat they wanted and one settling for a worse seat that was never
 * necessary.
 */
console.log("\n--- 4. a seat merely HELD by someone else is not described as taken ---");
takenIds = [];
await refresh();
tick("A1");            // also clears the notice from 3, via the change handler
tick("A3");
heldIds = [seatOf("A3").id];
await refresh();
check("A3 rendered as a held span, not a confirmed one",
    doc.querySelectorAll("#seat-grid span.seat.held").length, 1);
check("notice says held, and that it may come back", noticeText(),
    "[[one A3 held]] [[rest kept]]");

/* lostSeats accumulates across swaps, so one of each is reachable in an ordinary selection. */
console.log("\n--- 5. held and confirmed in one notice stay separate claims ---");
takenIds = [seatOf("A1").id];
await refresh();
check("each seat keeps its own wording", noticeText(),
    "[[one A1 gone]] [[one A3 held]] [[rest kept]]");

console.log("\n--- 6. changing the selection dismisses it ---");
takenIds = [];
heldIds = [];
await refresh();
tick("A3");
check("notice cleared once the customer re-selects", noticeText(), "(hidden)");

console.log("\n--- 7. the customer's OWN hold must not accuse anyone ---");
takenIds = [];
await refresh();
tick("A1");
/* Held, not confirmed: a successful hold is exactly what the customer's own seats become, and the
 * softer wording would be just as wrong to show them about a seat they just secured. */
holdResponse = container([], [seatOf("A1").id, seatOf("A3").id]);
await hold();
check("no notice after a successful hold", noticeText(), "(hidden)");
check("no error banner after a successful hold", errorText(), "(hidden)");

/*
 * From here: the refusals htmx swallows. htmx does not swap a non-2xx response, and the security
 * layer never reaches the resource method, so there is no fragment carrying an .error-banner —
 * without a responseError handler the customer presses Hold and the page does nothing at all.
 */
console.log("\n--- 8. a role that may not hold seats is told so, not left with a dead button ---");
takenIds = [];
await fullRefresh();
tick("A1");
tick("A2");
const hitsBefore = hits.length;
holdStatus = 403;
await hold();
check("the hold really was attempted", hits.length > hitsBefore, true);
check("403 names the account, not the mechanism", errorText(),
    "[[not allowed]] [[sign out]]");
/* Sign-out, not sign-in: signing in again as the same identity lands in exactly the same 403. */
check("403 offers local sign-out", errorLink(), "/logout | [[sign out]]");
check("A1 still ticked after the refusal", isChecked("A1"), true);
check("A2 still ticked after the refusal", isChecked("A2"), true);

console.log("\n--- 9. an expired session is the case that matters, and links to the right origin ---");
holdStatus = 401;
await hold();
check("401 says the session expired", errorText(), "[[expired]] [[sign in]]");
/* The whole point of data-signin-url: a hardcoded "/login" would work here and send a real
 * http:8080 visitor to https://host:8080/callback, a port with no TLS on it. */
check("401 links to the configured https origin", errorLink(), `${SIGNIN_URL} | [[sign in]]`);
check("the selection survived the expiry", isChecked("A1"), true);

/* 499 is what the web tenant actually answers a signed-out htmx request with (HtmxRequestChecker),
 * because a 302 to Google cannot be followed by htmx. It must read exactly as the 401 does. */
holdStatus = 499;
await hold();
check("499 says the session expired", errorText(), "[[expired]] [[sign in]]");
check("499 links to the configured https origin", errorLink(), `${SIGNIN_URL} | [[sign in]]`);

console.log("\n--- 10. the banner outlives a poll, and only a real refresh clears it ---");
await refresh();
check("a grid-only poll does not wipe it", errorText(), "[[expired]] [[sign in]]");
holdStatus = 200;
await fullRefresh();
check("a full container swap does clear it", errorText(), "(hidden)");

console.log("\n--- 11. a blipped background poll stays as quiet as it was before ---");
fragmentStatus = 503;
await refresh();
check("no banner from a failed poll", errorText(), "(hidden)");
fragmentStatus = 200;
await refresh();
check("the poll recovers on its own", errorText(), "(hidden)");

console.log("\n--- 12. a hidden tab asks for no grid, and showing it again refreshes once ---");
/* The gate the template's hx-trigger filter used to be: under the CSP that filter is an eval. */
const gridGets = () => hits.filter(h => h === "GET /fragment").length;
Object.defineProperty(doc, "visibilityState", { value: "hidden", configurable: true });
hits.length = 0;
await refresh();
check("a refresh while hidden sends nothing", gridGets(), 0);
Object.defineProperty(doc, "visibilityState", { value: "visible", configurable: true });
doc.dispatchEvent(new window.Event("visibilitychange"));
await sleep(400);
check("showing the tab sends one grid request", gridGets(), 1);
await refresh();
check("a visible tab refreshes as before", gridGets(), 2);

console.log(failures === 0 ? "\nALL CHECKS PASSED" : `\n${failures} CHECK(S) FAILED`);
window.close();
server.close();
process.exit(failures === 0 ? 0 : 1);
