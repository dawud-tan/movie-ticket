package com.protek.booking.seo

import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.function.Function

/**
 * Turns a branch name or a film title into the path segment it is published under
 * ({@code site.slug}, {@code film.slug}).
 *
 * <p>The rules are the boring ones — lower case, ASCII, hyphens — and this is their only
 * implementation. An earlier migration also applied them in SQL, to backfill rows that existed before
 * slugs did, with one gap: SQL cannot strip diacritics without the {@code unaccent} extension,
 * which a migrating role without superuser rights cannot create. So a row slugged by
 * that backfill may read "caf" where this gives "cafe". V1 seeds its slugs as
 * literals and runs no backfill.
 *
 * <p>An {@code object} with {@code @JvmStatic} members,
 * so {@code TestFixture} and {@code SlugTest} still call {@code Slug.of(…)} statically.
 */
object Slug {

    /**
     * Long enough for "Bioskop Sentral Kota Lama Timur" and every branch name a chain actually
     * uses; short enough that a pasted link stays readable. Truncation lands on a hyphen
     * rather than mid-word, because a slug ending "…-cinep" reads as a bug to the operator
     * who typed the name.
     */
    private const val MAX_LENGTH = 80

    /**
     * The slug for {@code text}, or empty when it contains nothing sluggable at all.
     *
     * <p>Empty rather than a substitute: the caller knows what the row is and can build a
     * fallback that is unique (the slug backfill used the uuid), and a shared "untitled" here would collide
     * with itself the second time it was needed.
     */
    @JvmStatic
    fun of(text: String?): String {
        if (text == null) {
            return ""
        }
        // NFD splits "é" into "e" + combining acute, so stripping the combining marks leaves
        // the letter behind. Done before lower-casing only because it costs nothing to;
        // neither order changes the result for the Latin range this sees.
        val ascii = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        val hyphenated = ascii.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .replace(Regex("^-+|-+$"), "")
        if (hyphenated.length <= MAX_LENGTH) {
            return hyphenated
        }
        val cut = hyphenated.substring(0, MAX_LENGTH)
        val lastHyphen = cut.lastIndexOf('-')
        return if (lastHyphen > 0) cut.substring(0, lastHyphen) else cut
    }

    /**
     * The {@code n}th candidate for {@code base}: the bare slug first, then {@code -2},
     * {@code -3}, … — matching the slug backfill, which numbers duplicates the same way.
     *
     * @param attempt 1-based, so attempt 1 is the slug an operator would expect to get.
     */
    @JvmStatic
    fun candidate(base: String?, attempt: Int): String? =
        if (attempt <= 1) base else "$base-$attempt"

    /**
     * The first slug under {@code text} that nothing else on this tenant is using.
     *
     * <p><b>This is a pre-check in front of a unique constraint, and that is deliberate —
     * it is not the seat rule.</b> That rule forbids a pre-check in front of the seat {@code EXCLUDE}
     * constraint because losing that race sells one seat twice, silently, to two paying
     * customers. Losing this one produces a unique violation on an admin form the operator is
     * looking at, and the constraint is still the thing that guarantees two branches never
     * share a URL. The query exists to make the common case give the operator the slug they
     * expect rather than to decide correctness.
     *
     * <p>The fallback covers a name that slugs to nothing — "★★★" — and matches the backfill's, which
     * has to solve the same problem for backfilled rows.
     *
     * <p>Lives here rather than in {@code CatalogService} so that {@code TestFixture}, which
     * inserts through the repositories directly, allocates by the same rule as production
     * instead of inventing a second one that could drift from it.
     *
     * @param taken given a base slug, every slug already using it — see
     *        {@code SiteRepository.findSlugsUnder}.
     */
    @JvmStatic
    fun free(text: String?, fallbackPrefix: String?, taken: Function<String, List<String>>): String {
        val base = of(text)
        if (base.isEmpty()) {
            return fallbackPrefix + "-" + UUID.randomUUID()
        }
        // java.util.Set.copyOf, as the Java had: it refuses a null element rather than
        // treating null as a slug that is in use.
        val used: Set<String> = java.util.Set.copyOf(taken.apply(base))
        for (attempt in 1..1000) {
            val candidate = candidate(base, attempt)
            if (!used.contains(candidate)) {
                return candidate!!
            }
        }
        // A thousand rows sharing one title on one tenant is not a catalogue, it is a runaway
        // import. Fall through to something unique rather than looping forever.
        return base + "-" + UUID.randomUUID()
    }

    /**
     * {@code base} as a LIKE pattern matching {@code base-<anything>}, with LIKE's
     * metacharacters escaped.
     *
     * <p>Needed because a name containing an underscore is ordinary ("Studio_1") and an
     * unescaped {@code _} matches any character. Widening the pattern would not corrupt
     * anything — the caller only uses the result to pick a free suffix — but it would hand
     * out {@code -3} where {@code -2} was free, which reads as a bug in the URL.
     */
    @JvmStatic
    fun likePrefix(base: String?): String =
        base!!.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "-%"

    /**
     * Whether {@code ref} in {@code /cinemas/{ref}} is a uuid rather than a slug.
     *
     * <p>Both forms resolve, so every link, bookmark and test that predates the slug column keeps working
     * (see {@code ShowtimeResource}). The two can never be confused: a slug that happened to
     * be 36 characters of hex and hyphens would have to have been generated from a title that
     * was already a uuid, and it would resolve to the same row anyway.
     */
    @JvmStatic
    fun looksLikeUuid(ref: String?): Boolean {
        if (ref == null || ref.length != 36) {
            return false
        }
        return try {
            UUID.fromString(ref)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
