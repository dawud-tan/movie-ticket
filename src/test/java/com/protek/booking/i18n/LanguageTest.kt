package com.protek.booking.i18n

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The language rules that do not need a server: what a header asks for, what a path says, and
 * what a link in the other language looks like.
 *
 * Plain JUnit and no `@QuarkusTest`, so this runs on a machine with no container
 * runtime — it is on the list of classes that do. [LanguageNegotiationTest] is
 * the same rules exercised through an actual request, and needs Docker.
 */
class LanguageTest {

    // --- Accept-Language ------------------------------------------------------------------

    @Test
    fun anIndonesianBrowserAsksForIndonesian() {
        assertSame(Language.INDONESIAN, Language.fromAcceptLanguage("id"))
        assertSame(Language.INDONESIAN, Language.fromAcceptLanguage("id-ID,id;q=0.9,en;q=0.8"))
    }

    @Test
    fun qValuesDecideRatherThanTheOrderTheyHappenToBeWrittenIn() {
        // The tempting implementation splits on commas and takes the first, which gets this
        // backwards: en is written first and asked for least.
        assertSame(Language.INDONESIAN, Language.fromAcceptLanguage("en;q=0.2,id;q=0.9"))
    }

    @Test
    fun aRegionSubtagFallsBackToItsLanguage() {
        // en-GB is not a tag this site publishes; lookup truncates to en rather than giving up
        // and landing on the default by accident, which would pass this assertion for the
        // wrong reason — hence the Indonesian half below.
        assertSame(Language.ENGLISH, Language.fromAcceptLanguage("en-GB,en;q=0.9"))
        assertSame(Language.INDONESIAN, Language.fromAcceptLanguage("id-ID"))
    }

    @Test
    fun anOldAndroidPhoneSendingTheRetiredInCodeStillGetsIndonesian() {
        // The app sends toLanguageTag, which is "id" on every Android, but a client that builds
        // the header from Locale.getLanguage sends "in". LanguageRange.parse adds the IANA
        // equivalent, so lookup finds "id" without a mapping of our own (Mobile M6).
        assertSame(Language.INDONESIAN, Language.fromAcceptLanguage("in"))
        assertSame(Language.INDONESIAN, Language.fromAcceptLanguage("in-ID,en;q=0.8"))
    }

    @Test
    fun aLanguageThisSiteDoesNotPublishGetsTheDefault() {
        assertSame(Language.ENGLISH, Language.fromAcceptLanguage("ja,ko;q=0.8"))
    }

    @Test
    fun aMissingOrMalformedHeaderIsIgnoredRatherThanRefused() {
        // Locale.LanguageRange.parse throws on garbage. A visitor whose browser sends something
        // odd still gets a page.
        assertSame(Language.ENGLISH, Language.fromAcceptLanguage(null))
        assertSame(Language.ENGLISH, Language.fromAcceptLanguage(""))
        assertSame(Language.ENGLISH, Language.fromAcceptLanguage("*;q=notanumber"))
        assertSame(Language.ENGLISH, Language.fromAcceptLanguage(";;;"))
    }

    // --- The path prefix ------------------------------------------------------------------

    @Test
    fun thePrefixIsMatchedAsAWholeSegment() {
        assertSame(Language.INDONESIAN, Language.split("/id/movies").language)
        assertEquals("/movies", Language.split("/id/movies").path)
        assertSame(Language.INDONESIAN, Language.split("/id").language)
        assertEquals("/", Language.split("/id").path, "/id alone is the home page")
    }

    @Test
    fun aPathThatMerelyStartsWithTheLettersIsNotPrefixed() {
        // The reason Language.split does not call startsWith("/id"): this tree has no /identity
        // today, and the day it gains one is not the day to find out.
        assertFalse(Language.split("/identity").wasPrefixed())
        assertEquals("/identity", Language.split("/identity").path)
        assertFalse(Language.split("/movies").wasPrefixed())
    }

    @Test
    fun anEncodedSegmentSurvivesTheSplitUntouched() {
        // /cities/{city} is the one public path with free text in it, and it arrives
        // percent-encoded. Nothing here decodes it.
        assertEquals("/cities/Jakarta%20Pusat", Language.split("/id/cities/Jakarta%20Pusat").path)
    }

    // --- Building the other language's URL ------------------------------------------------

    @Test
    fun theDefaultLanguageKeepsEveryUrlThisSiteAlreadyPublishes() {
        assertEquals("/", Language.ENGLISH.urlFor("/"))
        assertEquals("/movies", Language.ENGLISH.urlFor("/movies"))
    }

    @Test
    fun theHomePageIsSlashIdRatherThanSlashIdSlash() {
        // One spelling, so the canonical this page claims and the link that reached it are the
        // same string — the thing PageMeta exists to stop being two.
        assertEquals("/id", Language.INDONESIAN.urlFor("/"))
        assertEquals("/id/movies", Language.INDONESIAN.urlFor("/movies"))
    }

    @Test
    fun splitAndUrlForRoundTrip() {
        for (language in Language.all()) {
            for (path in arrayOf("/", "/movies", "/cinemas/demo-cinema")) {
                val url = language.urlFor(path)
                val split: Language.PrefixedPath = Language.split(url)
                assertEquals(path, split.path, url)
                assertSame(language, if (split.wasPrefixed()) split.language else Language.DEFAULT, url)
            }
        }
    }

    @Test
    fun theSwitchOffersTheLanguageYouAreNotIn() {
        assertSame(Language.INDONESIAN, Language.ENGLISH.other())
        assertSame(Language.ENGLISH, Language.INDONESIAN.other())
        assertTrue(Language.ENGLISH.isDefault())
        assertFalse(Language.INDONESIAN.isDefault())
    }

    @Test
    fun theLocaleHandedToQuteTagsIndonesianAsIdAndNotTheRetiredIn() {
        // Java's legacy ISO code for Indonesian is "in", and which of the two Locale.getLanguage
        // returns has changed between JDKs. The message bundle is keyed by toLanguageTag, which
        // is "id" either way — this asserts the tag, because that is what has to match
        // messages/msg_id.properties.
        assertEquals("id", Language.INDONESIAN.locale().toLanguageTag())
        assertEquals("en", Language.ENGLISH.locale().toLanguageTag())
    }
}
