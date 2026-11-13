/*
 * The date and time fields on /booking.
 *
 * One enhancement and nothing else: submit the field's own form when its value changes, so
 * changing a showtime is one tap rather than two. Both forms carry a visible submit button and
 * both are plain POSTs, so the page works in full with this file absent — which is the same
 * arrangement city-select.js and seatmap-selection.js use, and for the same reason: the server
 * is the authority and the DOM is already correct before any of this runs.
 *
 * Scoped to .booking-field rather than to every form on the page. The seat map lives on this
 * page too and its form has an age-attestation tick box in it; auto-submitting on change there
 * would post a hold the moment somebody ticked a seat.
 */
(function () {
    "use strict";

    var fields = document.querySelectorAll("form.booking-field select");
    for (var i = 0; i < fields.length; i++) {
        fields[i].addEventListener("change", function (event) {
            var form = event.target.form;
            if (form) {
                form.submit();
            }
        });
    }
})();
