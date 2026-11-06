// The /push/test harness (push/test.html). A file rather than an inline module, because the page's
// Content-Security-Policy allows scripts from this origin and gstatic only; the Firebase web config
// comes from /push/config, as seatmap-push.js reads it, instead of being written in by Qute.
//
// The seat map's API and version (seatmap-push.js): a getToken() registration token here would
// come back UNREGISTERED from /push/test-send, which sends it as an FID.
import { initializeApp } from "https://www.gstatic.com/firebasejs/12.19.0/firebase-app.js";
import { getMessaging, register, onRegistered, onMessage } from "https://www.gstatic.com/firebasejs/12.19.0/firebase-messaging.js";

// The pool this harness registers watches against — the seeded demo hall used
// throughout this project's own testing (see V2__seed_demo_data.sql).
const DEMO_POOL_ID = "00000000-0000-0000-0000-000000000002";

async function main() {
    console.log("step: fetching /push/config");
    const config = await (await fetch("/push/config")).json();
    if (!config.enabled || !config.vapidKey) {
        document.getElementById("status").textContent = "server has no VAPID public key configured";
        return;
    }

    console.log("step: initializing firebase app");
    const app = initializeApp({
        apiKey: config.apiKey,
        authDomain: config.authDomain,
        projectId: config.projectId,
        storageBucket: config.storageBucket,
        messagingSenderId: config.messagingSenderId,
        appId: config.appId
    });
    const messaging = getMessaging(app);

    console.log("step: registering messaging service worker");
    const swReg = await navigator.serviceWorker.register("/push/firebase-messaging-sw.js");
    await navigator.serviceWorker.ready;

    onMessage(messaging, (payload) => {
        console.log("step: onMessage fired", payload);
        document.getElementById("received").textContent = JSON.stringify(payload);
    });

    console.log("step: requesting notification permission");
    const perm = await Notification.requestPermission();
    console.log("step: permission result " + perm);
    if (perm !== "granted") {
        document.getElementById("status").textContent = "permission denied: " + perm;
        return;
    }

    console.log("step: calling register");
    const fid = await new Promise((resolve, reject) => {
        onRegistered(messaging, resolve);
        register(messaging, { vapidKey: config.vapidKey, serviceWorkerRegistration: swReg }).catch(reject);
    });
    document.getElementById("tokenSuffix").textContent = fid.slice(-8);

    console.log("step: posting FID to /push/subscribe");
    const resp = await fetch("/push/subscribe", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ poolId: DEMO_POOL_ID, token: fid })
    });
    const result = await resp.json();
    document.getElementById("status").textContent = "subscribed";
    document.getElementById("watchId").textContent = result.resourcePoolWatchId;
}

main().catch((e) => {
    console.error(e);
    document.getElementById("status").textContent = "error: " + e;
});
