package com.protek.booking.admission

/**
 * A Code 128 (code set B) encoder that renders straight to SVG — the visible half of the
 * admission artifact.
 *
 * **Why this is here and not a dependency.** Nothing on the classpath draws a barcode, and
 * the three obvious ways to get one are each worse than ~120 lines of table lookup. A third-party
 * origin costs DNS + TCP + TLS on the ticket page and fails the moment it is slow, rate-limited
 * or down — the same cost the seat map already pays for its Firebase SDK, except a
 * ticket that will not render is not a latency problem, it is a customer at a door with nothing
 * to show. A server-side imaging library brings a dependency to track for something a table
 * lookup already does. And a client-side JS encoder would put the one
 * artifact the whole feature exists to produce behind JavaScript, on a phone, in a queue.
 *
 * **Why Code 128 rather than QR.** Honest reason first: a QR encoder is Reed-Solomon over
 * GF(256), mask selection and BCH format words, and there is no decoder here to check its output
 * against — it would ship unverified. Code 128 is a table lookup plus a modulo-103 checksum, and
 * both halves are checkable: `Code128Test` asserts every pattern satisfies the invariants
 * ISO/IEC 15417 requires of the symbology (six elements, eleven modules, even bar parity, all 107
 * distinct) and checks the checksum against values worked by hand. Practically it also suits the
 * door better than it first looks — a counter laser scanner reads Code 128 natively, and it
 * prints legibly, which is what the walk-in customers of a counter sale actually get.
 * Google Wallet lists `CODE_128` alongside `QR_CODE` as a barcode type, so this does
 * not foreclose the pass.
 *
 * **Swapping symbology later changes only this class.** The token, the admission state and
 * the scan endpoint neither know nor care how the value is drawn — the barcode is a display of
 * `reservation.admission_token`, and the scan endpoint accepts that same string typed by
 * hand. That is deliberate: the artifact keeps working while the picture of it changes.
 *
 * **Verified against a third-party decoder on 2026-08-31; hardware is still owed.** The
 * invariants above prove the table is well-formed, which is not the same as decodable. ZXing —
 * which shares no code and no table with this repository — now reads the exact token back out of
 * the SVG this class renders, through the real stylesheet, at the device pixel ratios phones
 * paint at and at print resolution. So a failure at a door is an optics or sizing problem rather
 * than a wrong symbol. The sizing result that matters: at the ticket page's 340 CSS px cap
 * a 28-character token lands near **2 device px per module**, and below that readability is
 * scale-dependent rather than gradual. What is still owed is one scan by a real reader at a real
 * door, which needs hardware — the same class as the seat map's "check in a real browser" note.
 *
 * **Why an `object` with `@JvmStatic`, and why the test hooks are `internal` + `@JvmName`.**
 * Java callers (`TemplateExtensions`, `Code128Test`) still call `Code128.toSvg(…)` as a static
 * method, which `@JvmStatic` keeps true. The Java original kept `pattern`, `patternCount`,
 * `modules` and `symbols` package-private for the test; Kotlin has no package-private, and
 * `internal` is the nearest thing — but Kotlin mangles an internal member's JVM name, which would
 * leave the Java test calling a method that no longer exists under that name. `@JvmName` pins it.
 */
object Code128 {

    /**
     * The symbology's 107 element-width patterns, indexed by symbol value, each digit a run
     * length in modules, alternating bar-space-bar-space-… starting with a bar.
     *
     * Every entry is machine-checked by `Code128Test` rather than trusted, because a
     * single transposed digit here yields a symbol that scans as the wrong character or does not
     * scan at all, and neither failure is visible by reading the SVG.
     */
    private val PATTERNS = arrayOf(
        "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312", "132212", "221213",
        "221312", "231212", "112232", "122132", "122231", "113222", "123122", "123221", "223211", "221132",
        "221231", "213212", "223112", "312131", "311222", "321122", "321221", "312212", "322112", "322211",
        "212123", "212321", "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
        "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121", "313121", "211331",
        "231131", "213113", "213311", "213131", "311123", "311321", "331121", "312113", "312311", "332111",
        "314111", "221411", "431111", "111224", "111422", "121124", "121421", "141122", "141221", "112214",
        "112412", "122114", "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
        "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112", "421211", "212141",
        "214121", "412121", "111143", "111341", "131141", "114113", "114311", "411113", "411311", "113141",
        "114131", "311141", "411131", "211412", "211214", "211232", "2331112",
    )

    /** Code set B: symbol value 0 is the space character, and every printable ASCII follows. */
    private const val START_B = 104
    private const val STOP = 106
    private const val MODULO = 103

    /** The symbology's minimum, and not decoration: a scanner needs clear margin either side to
     * find the symbol's edges at all. Drawn as part of the SVG so a caller cannot forget it by
     * placing the image flush against something dark. */
    private const val QUIET_ZONE_MODULES = 10

    /** In viewBox units, with one module as the unit. The rendered size is the page's business —
     * the SVG scales — but the aspect ratio is set here so a barcode is never drawn so short that
     * a scanner cannot get a clean scan line across it. */
    private const val HEIGHT_MODULES = 60

    /** Read access to the table for `Code128Test`, which checks every entry against the
     * symbology's invariants rather than trusting the transcription. An accessor rather than
     * relaxing the field's visibility, so the array itself stays beyond reach of a caller who
     * could corrupt it for every subsequent ticket in the process. */
    @JvmStatic
    @JvmName("pattern")
    internal fun pattern(symbolValue: Int): String = PATTERNS[symbolValue]

    @JvmStatic
    @JvmName("patternCount")
    internal fun patternCount(): Int = PATTERNS.size

    /**
     * @throws IllegalArgumentException if `value` contains anything outside code set B's
     *         printable-ASCII range. Refusing beats silently dropping a character: a barcode that
     *         encodes a *different* token than the one printed beneath it would be discovered at
     *         a door, by a customer.
     *
     * `value` stays nullable because the Java original accepted null and refused it with the
     * same `IllegalArgumentException` as an empty code; a non-null parameter would turn that into
     * Kotlin's own `NullPointerException` for every Java caller.
     */
    @JvmStatic
    fun toSvg(value: String?): String {
        val modules = modules(value)
        val width = modules.size + 2 * QUIET_ZONE_MODULES

        val svg = StringBuilder(512)
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"barcode\" viewBox=\"0 0 ")
            .append(width).append(' ').append(HEIGHT_MODULES)
            .append("\" preserveAspectRatio=\"xMidYMid meet\" role=\"img\" aria-label=\"Admission code ")
            // modules(value) has already refused null, so the value here is never null.
            .append(xmlEscape(value!!)).append("\">")
        // An explicit white ground rather than relying on the page: the quiet zone only works if
        // it is actually light, and this SVG may sit on a themed card or be printed.
        svg.append("<rect width=\"").append(width).append("\" height=\"").append(HEIGHT_MODULES)
            .append("\" fill=\"#ffffff\"/>")

        // Consecutive bar modules are merged into one rect. A 28-character token is ~340 modules;
        // emitting one rect per module would triple the markup on a page that renders one of
        // these per seat.
        svg.append("<g fill=\"#000000\">")
        var i = 0
        while (i < modules.size) {
            if (!modules[i]) {
                i++
                continue
            }
            var run = 0
            while (i + run < modules.size && modules[i + run]) {
                run++
            }
            svg.append("<rect x=\"").append(i + QUIET_ZONE_MODULES).append("\" y=\"0\" width=\"")
                .append(run).append("\" height=\"").append(HEIGHT_MODULES).append("\"/>")
            i += run
        }
        svg.append("</g></svg>")
        return svg.toString()
    }

    /** The bar/space bitmap, quiet zones excluded — `true` is a bar. Internal so the
     * test can assert on the symbol structure rather than on generated markup. */
    @JvmStatic
    @JvmName("modules")
    internal fun modules(value: String?): BooleanArray {
        val symbols = symbols(value)
        val widths = StringBuilder()
        for (symbol in symbols) {
            widths.append(PATTERNS[symbol])
        }

        val modules = BooleanArray(totalModules(widths))
        var at = 0
        // Bar/space is positional, not a property of the run: every pattern starts on a bar and
        // strictly alternates. No per-pattern reset is needed because each is six elements, so
        // six flips land back on a bar for the next one — and the sole seven-element pattern,
        // stop, is by construction last.
        var bar = true
        for (i in widths.indices) {
            val run = widths[i] - '0'
            repeat(run) {
                modules[at++] = bar
            }
            bar = !bar
        }
        return modules
    }

    /** Start, data, checksum, stop — the full symbol value sequence. Internal for the
     * checksum test, which is the half of this class that can be verified by hand. */
    @JvmStatic
    @JvmName("symbols")
    internal fun symbols(value: String?): IntArray {
        if (value.isNullOrEmpty()) {
            throw IllegalArgumentException("Cannot encode an empty admission code")
        }
        val symbols = IntArray(value.length + 3)
        symbols[0] = START_B
        // The start character carries weight 1, and each data character its 1-based position, so
        // the running sum starts at the start value rather than at zero.
        var weighted = START_B.toLong()
        for (i in value.indices) {
            val ch = value[i]
            // toString(16) rather than Integer.toHexString: the same lowercase digits for a
            // non-negative code, and the last JVM-only call in this file, so the file can move into
            // a Kotlin Multiplatform commonMain unchanged.
            require(ch.code in 32..126) {
                "Code set B encodes printable ASCII only; got U+" +
                    ch.code.toString(16) + " at index " + i
            }
            val symbol = ch.code - 32
            symbols[i + 1] = symbol
            weighted += (i + 1).toLong() * symbol
        }
        symbols[value.length + 1] = (weighted % MODULO).toInt()
        symbols[value.length + 2] = STOP
        return symbols
    }

    private fun totalModules(widths: CharSequence): Int {
        var total = 0
        for (i in widths.indices) {
            total += widths[i] - '0'
        }
        return total
    }

    /** The token is generated as `[A-Z0-9-]` by the admission schema, so this never fires today. It is here
     * because this class is reachable with any code-set-B string and an SVG attribute is not a
     * place to find out that assumption changed. */
    private fun xmlEscape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
}
