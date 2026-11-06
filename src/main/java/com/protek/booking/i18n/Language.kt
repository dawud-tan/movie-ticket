package com.protek.booking.i18n

import com.protek.booking.isJavaBlank
import com.protek.booking.javaTrim
import java.util.Locale
import java.util.Optional

/**
 * The languages the interface is published in: English, and Bahasa Indonesia.
 *
 * <p><b>English is the default and carries the empty prefix,</b> so every URL that exists today
 * keeps meaning what it means — {@code /movies} is the English film list and {@code /id/movies}
 * is the Indonesian one. The alternative (prefixing both, {@code /en/…} and {@code /id/…}) would
 * have needed a redirect in front of every existing URL, and this deployment already has more
 * spellings of each page than it wants: {@code PageMeta}'s javadoc counts four for a branch page
 * before any of this. Adding a prefix for the new language only adds one more.
 *
 * <p>Flipping the default to Indonesian later is this enum plus {@code quarkus.default-locale}
 * plus a rename of the message files — worth deciding before the translated corpus grows, not
 * after.
 *
 * <p><b>The endonym is not a translated string</b> and deliberately does not live in
 * {@link Msg}. A language names itself the same way whatever page it appears on: the switch
 * on an English page says "Bahasa Indonesia", not "Indonesian", because the person who needs
 * that control is the person who cannot read the page they are on.
 *
 * <p><b>No flag emoji, and that is a decision rather than an omission.</b> Three reasons, in
 * order of how expensive each is to discover later. Windows ships no country-flag glyphs in
 * Segoe UI Emoji, so Chrome, Edge and Firefox on Windows render a flag as the bare letter pair
 * "ID" / "GB" — and a demo audience is as likely to be on Windows as not.
 * A language is not a country: English would have to pick between two flags that both misname
 * it, and Indonesian is spoken outside Indonesia. And a flag carries no accessible name — a
 * screen reader announces "flag of Indonesia", which is not what the control does.
 */
enum class Language(
    private val tag: String,
    private val prefix: String,
    private val endonym: String,
) {

    /** The default. Empty prefix: {@code /movies} is this language. */
    ENGLISH("en", "", "English"),

    /** {@code /id/movies}. */
    INDONESIAN("id", "/id", "Bahasa Indonesia");

    /** The BCP 47 tag: what goes in {@code <html lang>}, in {@code hreflang}, and what names
     * the message file ({@code messages/msg_id.properties}). */
    fun tag(): String = tag

    /** The URL prefix, {@code ""} for the default language and {@code "/id"} otherwise. */
    fun prefix(): String = prefix

    /** The language's name in itself — see the class javadoc for why this is not translated. */
    fun endonym(): String = endonym

    fun isDefault(): Boolean = this == DEFAULT

    /**
     * The language the switch in the header offers.
     *
     * <p>Correct only while there are exactly two, which is why it is a method rather than
     * something the template works out: a third language turns the header's one button into a
     * select, and this method is where the compiler will not help — so it is the one place to
     * look. {@link #others()} is what that select would iterate.
     */
    fun other(): Language = if (this == ENGLISH) INDONESIAN else ENGLISH

    /** Every language but this one. What a select would list, and what nothing uses yet. */
    // stream().toList(), not filter: the Java returned an unmodifiable list.
    fun others(): List<Language> = ALL.stream().filter { language -> language != this }.toList()

    /** {@code path} under this language's prefix. {@code path} is expected rooted and already
     * unprefixed; {@code "/"} under a prefixed language becomes {@code "/id"} rather than
     * {@code "/id/"}, which is the form the pre-matching filter and the canonical both use. */
    fun urlFor(path: String?): String {
        if (path == null || path.isEmpty() || path == "/") {
            return if (prefix.isEmpty()) "/" else prefix
        }
        return prefix + path
    }

    /** The {@link Locale} handed to Qute as the template instance's {@code locale} attribute.
     * Built from the tag, because {@code Locale.toLanguageTag()} round-trips {@code "id"}
     * whatever the JVM's opinion of the retired {@code "in"} code is — and the tag is what the
     * message bundle is keyed by. */
    fun locale(): Locale = Locale.forLanguageTag(tag)

    /** A path split into the language its prefix named — null when it carried none — and the
     * path the application should route on. */
    @JvmRecord
    data class PrefixedPath(val language: Language?, val path: String) {

        fun wasPrefixed(): Boolean = language != null
    }

    companion object {

        /** What an unprefixed URL and an unset {@code Accept-Language} both resolve to. Kept equal
         * to {@code quarkus.default-locale} in application.properties — the message bundle takes
         * its default from that property, and the two disagreeing would mean a page whose URL says
         * one language and whose copy is in the other. */
        @JvmField
        val DEFAULT: Language = ENGLISH

        private val ALL: List<Language> = java.util.List.of(*entries.toTypedArray())
        private val TAGS: List<String> = ALL.stream().map { it.tag }.toList()

        /** Every language, in declaration order — the default first. */
        @JvmStatic
        fun all(): List<Language> = ALL

        @JvmStatic
        fun ofTag(tag: String?): Optional<Language> {
            if (tag == null) {
                return Optional.empty()
            }
            // javaTrim, not trim(): Java's trim() strips up to U+0020 and nothing else.
            val normalised = tag.javaTrim().lowercase(Locale.ROOT)
            return ALL.stream().filter { language -> language.tag == normalised }.findFirst()
        }

        /**
         * The language a path names, and what is left of the path once it is removed.
         *
         * <p>Exact-segment match — {@code /id} and {@code /id/…} but never {@code /identity}, which
         * is not a path this tree has and is exactly the kind of thing a {@code startsWith("/id")}
         * would swallow the day somebody adds one.
         */
        @JvmStatic
        fun split(rawPath: String?): PrefixedPath {
            val path = if (rawPath == null || rawPath.isEmpty()) "/" else rawPath
            for (language in ALL) {
                if (language.prefix.isEmpty()) {
                    continue
                }
                if (path == language.prefix) {
                    return PrefixedPath(language, "/")
                }
                if (path.startsWith(language.prefix + "/")) {
                    return PrefixedPath(language, path.substring(language.prefix.length))
                }
            }
            return PrefixedPath(null, path)
        }

        /**
         * What the browser asked for, reduced to a language published here.
         *
         * <p>{@link Locale.LanguageRange} rather than a hand-rolled split on commas: the header
         * carries q-values that order the list ({@code en-GB,en;q=0.9,id;q=0.8}) and region
         * subtags that have to fall back to the base language ({@code id-ID} is Indonesian), and
         * both of those are things the JDK already does correctly. {@code lookupTag} is the
         * matching mode that truncates rather than requiring an exact tag.
         *
         * <p>A malformed header yields the default rather than an error: it is caller-supplied
         * input on a GET that is otherwise perfectly serviceable, and refusing to render a page
         * because a header was odd would be a worse answer than rendering it in English.
         */
        @JvmStatic
        fun fromAcceptLanguage(header: String?): Language {
            // isJavaBlank, not isBlank(): Kotlin's also counts U+00A0.
            if (header == null || header.isJavaBlank()) {
                return DEFAULT
            }
            return try {
                val best = Locale.lookupTag(Locale.LanguageRange.parse(header), tags())
                ofTag(best).orElse(DEFAULT)
            } catch (e: IllegalArgumentException) {
                DEFAULT
            }
        }

        private fun tags(): Collection<String> = TAGS
    }
}
