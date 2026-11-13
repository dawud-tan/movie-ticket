/**
 * Implementation of the net.sf.geographiclib.Constants class
 * <p>
 * Copyright (c) Charles Karney (2013) <charles@karney.com> and licensed
 * under the MIT/X11 License.  For more information, see
 * https://geographiclib.sourceforge.io/
 **********************************************************************/
package net.sf.geographiclib

/**
 * Constants needed by GeographicLib.
 * <p>
 * Define constants specifying the WGS84 ellipsoid.
 ***********************************************************************/
// A class with a companion, not the object a Kotlin conversion would usually make it, so javap of
// it matches the Java's but for Companion: not final, no INSTANCE, the same private constructor.
// Java still reads Constants.WGS84_a, a public static final field, and javac still folds it.
open class Constants private constructor() {
    companion object {
        /**
         * The equatorial radius of WGS84 ellipsoid (6378137 m).
         **********************************************************************/
        const val WGS84_a = 6378137.0
        /**
         * The flattening of WGS84 ellipsoid (1/298.257223563).
         **********************************************************************/
        const val WGS84_f = 1 / 298.257223563
    }
}
