package com.protek.booking

/*
 * java.lang.String's own rules, for Kotlin code converted from Java that relied on them.
 * Kotlin's same-named functions use different ones,
 * and the difference only shows on input nobody writes a test for until it arrives.
 */

/** Java's `String.isBlank()`: every char is `Character.isWhitespace`. Kotlin's `isBlank()` also
 * counts space separators such as U+00A0, so it would call a non-breaking space blank where Java
 * did not. */
internal fun String.isJavaBlank(): Boolean = all { Character.isWhitespace(it) }

/** Java's `String.trim()`: strips chars at or below U+0020 from both ends. Kotlin's `trim()`
 * strips Unicode whitespace instead, which reaches further (U+00A0, U+2007) and not as far (it
 * keeps the C0 controls that are not whitespace). */
internal fun String.javaTrim(): String = trim { it <= ' ' }
