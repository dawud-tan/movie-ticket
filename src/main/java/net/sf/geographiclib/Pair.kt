/**
 * Implementation of the net.sf.geographiclib.Pair class
 * <p>
 * Copyright (c) Charles Karney (2013-2020) <charles@karney.com>∑ and licensed
 * under the MIT/X11 License.  For more information, see
 * https://geographiclib.sourceforge.io/
 **********************************************************************/
package net.sf.geographiclib

/**
 * A pair of double precision numbers.
 * <p>
 * This duplicates the C++ class {@code std::pair<double, double>}.
 **********************************************************************/
open class Pair {
    /**
     * The first member of the pair.
     **********************************************************************/
    @JvmField
    var first = 0.0
    /**
     * The second member of the pair.
     **********************************************************************/
    @JvmField
    var second = 0.0

    /**
     * Constructor
     * <p>
     *
     * @param first  the first member of the pair.
     * @param second the second member of the pair.
     **********************************************************************/
    constructor(first: Double, second: Double) {
        this.first = first
        this.second = second
    }

    /**
     * No-argument Constructor
     **********************************************************************/
    constructor()
}
