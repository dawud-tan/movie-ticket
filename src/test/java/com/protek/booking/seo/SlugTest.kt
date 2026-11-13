package com.protek.booking.seo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * The slug rules, as plain unit tests — no Quarkus, because none of this touches a
 * database and the cases worth having are cheap enough to have all of them.
 *
 * An earlier SQL backfill copied these rules and could not strip diacritics ([Slug]'s doc has
 * why). It no longer runs anywhere, and
 * [diacriticsAreStrippedHereEvenThoughTheBackfillCannot] still pins the Java side.
 */
class SlugTest {

    @Test
    fun aBranchNameBecomesTheUrlSomebodyWouldSearchFor() {
        assertEquals("grand-cinema-rekaan", Slug.of("Grand Cinema Rekaan"))
    }

    @Test
    fun punctuationAndRunsOfSpaceCollapseToOneHyphen() {
        assertEquals("fast-furious-tokyo-drift", Slug.of("Fast & Furious:  Tokyo Drift!"))
    }

    @Test
    fun leadingAndTrailingSeparatorsAreTrimmed() {
        // "  — Studio 1 —  " must not become "--studio-1--", which is a URL with visible
        // punctuation at both ends and reads as a bug rather than as a name.
        assertEquals("studio-1", Slug.of("  — Studio 1 —  "))
    }

    @Test
    fun diacriticsAreStrippedHereEvenThoughTheBackfillCannot() {
        // The Java side normalises to NFD and drops the combining marks, so "Café" keeps its
        // "e". The slug SQL backfill has no unaccent extension available and produces "caf" for a
        // row that predates this code — a documented difference, not a disagreement.
        assertEquals("cafe-du-cinema", Slug.of("Café du Cinéma"))
    }

    @Test
    fun aNameWithNothingSluggableInItYieldsEmptyRatherThanASharedPlaceholder() {
        // Empty, so the caller can build a fallback that is unique. A shared "untitled" would
        // collide with itself the second time a tenant needed it.
        assertEquals("", Slug.of("★★★"))
        assertEquals("", Slug.of("上映中"))
        assertEquals("", Slug.of(null))
    }

    @Test
    fun aVeryLongNameIsCutOnAWordBoundary() {
        val name = ("The Extremely Long Cinema Name That Somebody Typed Into The Admin Form "
                + "Without Thinking About How Long It Was Going To Be")
        val slug = Slug.of(name)

        assertTrue(slug.length <= 80, "slug was " + slug.length + " characters: " + slug)
        assertFalse(slug.endsWith("-"), "a trailing hyphen means it was cut mid-separator: " + slug)
        // Every character kept is a character the untruncated slug would have had, in order,
        // and the next one is the separator — which is what "cut on a word boundary" means.
        val untruncated = name.lowercase(Locale.ROOT).replace(' ', '-')
        assertTrue(untruncated.startsWith(slug + "-"), "cut mid-word: " + slug)
    }

    @Test
    fun theFirstCandidateIsTheBareSlugAndTheSecondIsNumberedTwo() {
        // Matches the row_number() backfill: the oldest row keeps the bare slug, so a URL
        // that has been linked to for a year does not move when a duplicate name is added.
        assertEquals("grand-cinema", Slug.candidate("grand-cinema", 1))
        assertEquals("grand-cinema-2", Slug.candidate("grand-cinema", 2))
    }

    @Test
    fun allocationSkipsTheSlugsAlreadyTaken() {
        assertEquals("grand-cinema-3",
            Slug.free("Grand Cinema", "cinema"
            ) { base -> listOf("grand-cinema", "grand-cinema-2") })
    }

    @Test
    fun allocationFallsBackToSomethingUniqueForAnUnsluggableName() {
        val slug = Slug.free("★★★", "cinema") { base -> listOf() }
        assertTrue(slug.startsWith("cinema-"), slug)
        assertNotEquals("cinema-", slug, "the fallback has to be unique, not a bare prefix")
    }

    @Test
    fun aUuidRefIsRecognisedSoBothUrlFormsCanResolve() {
        assertTrue(Slug.looksLikeUuid("00000000-0000-0000-0000-000000000004"))
        assertFalse(Slug.looksLikeUuid("grand-cinema-rekaan"))
        assertFalse(Slug.looksLikeUuid(null))
        // Same length as a uuid but not one — must be treated as a slug, or the page 404s
        // on a lookup that would have succeeded.
        assertFalse(Slug.looksLikeUuid("not-a-uuid-but-exactly-thirty-six-ch"))
    }

    @Test
    fun likePatternsEscapeTheMetacharactersASlugBaseCanContain() {
        // A branch called "Studio_1" would otherwise match "studio-1-x" through LIKE's
        // single-character wildcard, and be handed -3 where -2 was free.
        assertEquals("studio\\_1-%", Slug.likePrefix("studio_1"))
        assertEquals("100\\%-cinema-%", Slug.likePrefix("100%-cinema"))
    }
}
