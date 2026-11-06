/*
 * Behaviour test for city-select.js — the header's city control.
 *
 * Runs the REAL city-select.js in jsdom against a local stub of /city/nearest, with
 * navigator.geolocation stubbed the way a browser exposes it. It is a browser test in a Maven
 * build for the same reason the seat-map ones are: the logic exists only in the browser, and the
 * Java suite passes in full whether this file works or not.
 *
 * Step 0 reads the real base.html, because everything below depends on properties of markup this
 * file only mirrors. The mirror is by hand — base.html is Qute with conditionals and cannot be
 * rendered without Quarkus.
 */
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { JSDOM, VirtualConsole } from "jsdom";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const RESOURCES = path.resolve(HERE, "../../main/resources");
const WEB = path.join(RESOURCES, "META-INF/resources");

const scriptSrc = fs.readFileSync(path.join(WEB, "js/city-select.js"), "utf8");
const baseTemplate = fs.readFileSync(path.join(RESOURCES, "templates/base.html"), "utf8");

let failures = 0;
function check(name, actual, expected) {
    const ok = actual === expected;
    if (!ok) failures++;
    console.log(`  ${ok ? "PASS" : "FAIL"}  ${name}`);
    if (!ok) console.log(`        expected: ${expected}\n        actual:   ${actual}`);
}

/* The city form on its own, so a check about that control cannot be satisfied by markup
   belonging to some other control in the same header — the language switch, for instance, which
   also carries a submit button. */
const cityForm = (baseTemplate.match(/<form class="city-select"[\s\S]*?<\/form>/) || [""])[0];
const langForm = (baseTemplate.match(/<form class="lang-switch"[\s\S]*?<\/form>/) || [""])[0];

function assertTemplateInvariants() {
    console.log("--- 0. the real base.html still has the properties this test mirrors ---");
    check("the form is a POST to /city",
        /<form class="city-select" method="post" action="\/city">/.test(baseTemplate), true);
    check("the select the script hooks is #city-field",
        /id="city-field"/.test(baseTemplate), true);
    check("the locate button the script hooks is #city-locate",
        /id="city-locate"/.test(baseTemplate), true);
    check("the status line the script writes into is #city-locate-status",
        /id="city-locate-status"/.test(baseTemplate), true);
    /* The submit button is what makes the whole control work with this script absent. If it ever
       goes, auto-submit stops being an enhancement and becomes the mechanism.

       Matched inside the city form rather than by its label. It used to be pinned to the literal
       ">Go<", which broke the day the label became a message-bundle key — and would have broken
       again on any reword, while never noticing the button being deleted from this form and a
       different submit button appearing elsewhere in the header. What is being guarded is a
       submit button IN THIS FORM, so that is what is asserted. */
    check("a real submit button exists beside the select",
        /<button type="submit">/.test(cityForm), true);
    /* The locate button must be type="button": inside a form, the default is "submit", so a
       browser would post the form the moment it was pressed and reload before geolocation
       answered. */
    check("the locate button is type=button and not a submit",
        /<button type="button" id="city-locate"/.test(baseTemplate), true);
    /* Only rendered when some city has coordinates — the site table left them nullable and null, so without
       this gate the button's only possible answer on a fresh tenant is an apology. */
    check("the locate button is gated on isLocatable",
        /\{#if inject:city\.isLocatable\}[\s\S]*id="city-locate"/.test(baseTemplate), true);
    check("no hardcoded city name in the script",
        /Jakarta/.test(scriptSrc), false);

    /* The language switch, checked here because it lives in the same header and is held to the
       same rule: it must work with no JavaScript at all. That is the whole reason it is an
       always-visible form rather than a drawer something has to open — see base.html. No script
       hooks it, so there is nothing else that would notice it turning into a link or a div. */
    check("the language switch is a POST to /language",
        /<form class="lang-switch" method="post" action="\/language">/.test(baseTemplate), true);
    check("the language switch submits without a script",
        /<button type="submit"/.test(langForm), true);
    check("the language switch carries where to come back to",
        /name="next" value="\{inject:lang\.currentPath\}"/.test(langForm), true);
    /* lang= on the button, or a screen reader reads "Bahasa Indonesia" with English phonemes. */
    check("the switch's own label is marked up as being in the language it names",
        /lang="\{inject:lang\.otherTag\}"/.test(langForm), true);
}

const UTF8_JS = { "Content-Type": "text/javascript;charset=UTF-8" };
const UTF8_HTML = { "Content-Type": "text/html;charset=UTF-8" };
const UTF8_JSON = { "Content-Type": "application/json;charset=UTF-8" };

let nearestResponse = { city: "Rekaan" };
let nearestStatus = 200;
const posts = [];

const server = http.createServer((req, res) => {
    if (req.url === "/js/city-select.js") {
        res.writeHead(200, UTF8_JS);
        return res.end(scriptSrc);
    }
    if (req.url === "/city/nearest" && req.method === "POST") {
        let body = "";
        req.on("data", chunk => { body += chunk; });
        return req.on("end", () => {
            posts.push(body);
            res.writeHead(nearestStatus, UTF8_JSON);
            res.end(JSON.stringify(nearestResponse));
        });
    }
    res.writeHead(200, UTF8_HTML);
    res.end(`<!doctype html><html><body>
      <form class="city-select" method="post" action="/city">
        <input type="hidden" name="next" value="/cinemas">
        <select id="city-field" name="city">
          <option value="Jakarta Pusat" selected>Jakarta Pusat (1)</option>
          <option value="Rekaan">Rekaan (1)</option>
        </select>
        <button type="submit">Go</button>
        <button type="button" id="city-locate" data-locate="true">Use my location</button>
        <span id="city-locate-status" class="muted" role="status" aria-live="polite"></span>
      </form>
      <script src="/js/city-select.js"></script>
    </body></html>`);
});

function listen() {
    return new Promise(resolve => server.listen(0, "127.0.0.1", () => resolve(server.address().port)));
}

/* Loads the page with a chosen geolocation behaviour. `geo` is null to model a browser with no
 * geolocation at all (or an insecure origin, where Chrome and Safari withhold it) — which on this
 * deployment is the http:8080 entry point, a real address a real visitor reaches. */
async function load(port, geo) {
    /*
     * window.location.reload() cannot be stubbed in jsdom: window.location is non-configurable and
     * so are Location's own properties, so neither the object nor the method can be replaced.
     *
     * It is observable anyway, and more honestly than a stub would be. jsdom implements no
     * navigation, so a real reload() call raises a jsdomError reading "Not implemented:
     * navigation (except hash changes)". Listening for that asserts the script called the real
     * API rather than a double the test installed.
     */
    const virtualConsole = new VirtualConsole();
    const reload = { called: false };
    virtualConsole.on("jsdomError", err => {
        if (/navigation/i.test(err.message || "")) reload.called = true;
    });
    const dom = await JSDOM.fromURL(`http://127.0.0.1:${port}/`, {
        runScripts: "dangerously",
        resources: "usable",
        virtualConsole
    });
    dom.reload = reload;
    if (geo !== undefined) {
        Object.defineProperty(dom.window.navigator, "geolocation", { value: geo, configurable: true });
    }
    /* fetch and URLSearchParams are not in jsdom; the script uses both. Forwarded to node's own
     * implementations against the stub server, so the request the script builds is really sent
     * and really parsed rather than being asserted on as a string. */
    dom.window.fetch = (url, init) => fetch(`http://127.0.0.1:${port}${url}`, init);
    dom.window.URLSearchParams = URLSearchParams;
    return dom;
}

/* The script is loaded by the page itself, so it may already have run before the geolocation stub
 * was installed. Re-running it against the same DOM is how the stub is guaranteed to be in place;
 * the listeners it adds are the ones under test either way. */
function run(dom) {
    dom.window.eval(scriptSrc);
}

const settle = () => new Promise(resolve => setTimeout(resolve, 60));

async function main() {
    assertTemplateInvariants();
    const port = await listen();

    console.log("\n--- 1. choosing a city submits the form, so it takes one tap and not two ---");
    {
        const dom = await load(port);
        let submitted = false;
        dom.window.document.querySelector("form.city-select").submit = () => { submitted = true; };
        run(dom);
        const field = dom.window.document.getElementById("city-field");
        field.value = "Rekaan";
        field.dispatchEvent(new dom.window.Event("change"));
        await settle();
        check("the form was submitted on change", submitted, true);
        dom.window.close();
    }

    console.log("\n--- 2. sharing a location posts the coordinates and reports the answer ---");
    {
        posts.length = 0;
        nearestResponse = { city: "Rekaan" };
        const dom = await load(port, {
            getCurrentPosition: (ok) => ok({ coords: { latitude: -7.087, longitude: 110.915 } })
        });
        run(dom);
        dom.window.document.getElementById("city-locate").click();
        await settle();
        check("the coordinates reached the server", posts.length, 1);
        check("latitude was sent", /latitude=-7\.087/.test(posts[0]), true);
        check("longitude was sent", /longitude=110\.915/.test(posts[0]), true);
        check("the chosen city was named before reloading",
            dom.window.document.getElementById("city-locate-status").textContent, "Rekaan");
        /* Reload rather than patching the DOM: the city changes what almost every part of the
           page lists, and the server already renders all of it. */
        check("the page was reloaded", dom.reload.called, true);
        dom.window.close();
    }

    console.log("\n--- 3. a declined permission leaves the visitor able to pick by hand ---");
    {
        const dom = await load(port, {
            getCurrentPosition: (ok, fail) => fail({ code: 1, message: "denied" })
        });
        run(dom);
        const button = dom.window.document.getElementById("city-locate");
        button.click();
        await settle();
        check("the status line says what to do instead",
            /Pick a city from the list/.test(
                dom.window.document.getElementById("city-locate-status").textContent), true);
        /* Re-enabled, because a visitor who declined by accident has to be able to try again. A
           button left disabled after a refusal is indistinguishable from a broken one. */
        check("the button is usable again", button.disabled, false);
        dom.window.close();
    }

    console.log("\n--- 4. a browser with no geolocation says so instead of doing nothing ---");
    {
        const dom = await load(port, null);
        run(dom);
        dom.window.document.getElementById("city-locate").click();
        await settle();
        check("the status line explains rather than staying blank",
            /cannot share a location/.test(
                dom.window.document.getElementById("city-locate-status").textContent), true);
        dom.window.close();
    }

    console.log("\n--- 5. 200 with no city is a configuration state, not an error ---");
    {
        nearestResponse = { city: null };
        const dom = await load(port, {
            getCurrentPosition: (ok) => ok({ coords: { latitude: 0, longitude: 0 } })
        });
        run(dom);
        const button = dom.window.document.getElementById("city-locate");
        button.click();
        await settle();
        /* Told apart from a failed request on purpose: this means no city has coordinates yet,
           which is what a tenant who never filled in the site columns gets, and the two need
           different things done about them. */
        check("the status line says no cinema is near",
            /No cinema near you yet/.test(
                dom.window.document.getElementById("city-locate-status").textContent), true);
        check("nothing was reloaded, because nothing changed", dom.reload.called, false);
        check("the button is usable again", button.disabled, false);
        nearestResponse = { city: "Rekaan" };
        dom.window.close();
    }

    console.log("\n--- 6. a failed request does not leave the control dead ---");
    {
        nearestStatus = 500;
        const dom = await load(port, {
            getCurrentPosition: (ok) => ok({ coords: { latitude: -7.087, longitude: 110.915 } })
        });
        run(dom);
        const button = dom.window.document.getElementById("city-locate");
        button.click();
        await settle();
        check("the status line admits it could not work it out",
            /Could not work out your nearest cinema/.test(
                dom.window.document.getElementById("city-locate-status").textContent), true);
        check("the button is usable again", button.disabled, false);
        nearestStatus = 200;
        dom.window.close();
    }

    server.close();
    if (failures > 0) {
        console.log(`\n${failures} CHECK(S) FAILED`);
        process.exit(1);
    }
    console.log("\nALL CHECKS PASSED");
}

main().catch(err => {
    console.error(err);
    process.exit(1);
});
