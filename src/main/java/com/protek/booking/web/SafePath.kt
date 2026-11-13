package com.protek.booking.web

/**
 * A caller-supplied "where I was" reduced to something that can only ever be a path on this
 * origin.
 *
 * <p>Lifted out of {@code CityResource}, which had it first and is still the fuller
 * explanation, when {@code LanguageResource} grew the same form field. Two copies of an
 * open-redirect guard is the shape where one of them quietly stops matching the other; there is
 * one copy, and both header controls call it.
 *
 * <p>The value arrives in a form field, so it is caller-supplied, and a {@code Location} built
 * straight from one of those is an open redirect — the classic shape being
 * {@code //evil.example}, which a browser reads as a protocol-relative absolute URL and which
 * passes any check that only asks "does it start with a slash".
 */
object SafePath {

    /**
     * {@code next} if it is a rooted, single-slash path; {@code "/"} otherwise.
     *
     * <p>Rejects anything not starting with a single {@code /} — which covers {@code //host},
     * {@code https://host}, and a bare {@code javascript:} — and strips any authority a
     * backslash-normalising browser might reconstruct. Falls back to the home page rather than
     * erroring: the visitor asked to change a display preference, and refusing that because the
     * return path was malformed would punish them for something they did not send by hand.
     */
    @JvmStatic
    fun next(next: String?): String {
        if (next == null || next.length < 2 || next[0] != '/'
                || next[1] == '/' || next[1] == '\\') {
            return "/"
        }
        return next
    }
}
