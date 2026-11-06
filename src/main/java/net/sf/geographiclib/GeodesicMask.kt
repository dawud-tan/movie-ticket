/**
 * Implementation of the net.sf.geographiclib.GeodesicMask class
 * <p>
 * Copyright (c) Charles Karney (2013-2014) <charles@karney.com> and licensed
 * under the MIT/X11 License.  For more information, see
 * https://geographiclib.sourceforge.io/
 **********************************************************************/
package net.sf.geographiclib

/**
 * Bit masks for what geodesic calculations to do.
 * <p>
 * These masks do double duty.  They specify (via the <i>outmask</i> parameter)
 * which results to return in the {@link GeodesicData} object returned by the
 * general routines {@link Geodesic#Direct(double, double, double, double, int)
 * Geodesic.Direct} and {@link Geodesic#Inverse(double, double, double, double,
 * int) Geodesic.Inverse} routines.  They also signify (via the <i>caps</i>
 * parameter) to the {@link GeodesicLine#GeodesicLine(Geodesic, double, double,
 * double, int) GeodesicLine.GeodesicLine} constructor and to {@link
 * Geodesic#Line(double, double, double, int) Geodesic.Line} what capabilities
 * should be included in the {@link GeodesicLine} object.
 **********************************************************************/
open class GeodesicMask {
    // The statics live in a companion, as Geodesic's do: the class keeps the public no-argument
    // constructor the Java had, and every constant is still a static final field javac folds.
    companion object {
        /**
         * No capabilities, no output.
         **********************************************************************/
        const val NONE = 0
        /**
         * Unroll <i>lon2</i>.
         **********************************************************************/
        const val LONG_UNROLL = (1 shl 15)
        protected const val CAP_NONE = 0
        /**
         * Calculate latitude <i>lat2</i>.  (It's not necessary to include this as a
         * capability to {@link GeodesicLine} because this is included by default.)
         **********************************************************************/
        const val LATITUDE = (1 shl 7) or CAP_NONE
        /**
         * Calculate azimuths <i>azi1</i> and <i>azi2</i>.  (It's not necessary to
         * include this as a capability to {@link GeodesicLine} because this is
         * included by default.)
         **********************************************************************/
        const val AZIMUTH = (1 shl 9) or CAP_NONE
        protected const val CAP_C1 = (1 shl 0)
        /**
         * Calculate distance <i>s12</i>.
         **********************************************************************/
        const val DISTANCE = (1 shl 10) or CAP_C1
        protected const val CAP_C1p = (1 shl 1)
        /**
         * Allow distance <i>s12</i> to be used as <i>input</i> in the direct
         * geodesic problem.
         **********************************************************************/
        const val DISTANCE_IN = (1 shl 11) or CAP_C1 or CAP_C1p
        protected const val CAP_C2 = (1 shl 2)
        /**
         * Calculate reduced length <i>m12</i>.
         **********************************************************************/
        const val REDUCEDLENGTH = (1 shl 12) or CAP_C1 or CAP_C2
        /**
         * Calculate geodesic scales <i>M12</i> and <i>M21</i>.
         **********************************************************************/
        const val GEODESICSCALE = (1 shl 13) or CAP_C1 or CAP_C2
        protected const val CAP_C3 = (1 shl 3)
        /**
         * Calculate longitude <i>lon2</i>.
         **********************************************************************/
        const val LONGITUDE = (1 shl 8) or CAP_C3
        /**
         * All of the above, the "standard" output and capabilities.
         **********************************************************************/
        const val STANDARD = LATITUDE or LONGITUDE or AZIMUTH or DISTANCE
        protected const val CAP_C4 = (1 shl 4)
        /**
         * Calculate area <i>S12</i>.
         **********************************************************************/
        const val AREA = (1 shl 14) or CAP_C4
        protected const val CAP_ALL = 0x1F
        protected const val CAP_MASK = CAP_ALL
        protected const val OUT_ALL = 0x7F80
        /**
         * All capabilities, calculate everything.  (LONG_UNROLL is not included in
         * this mask.)
         **********************************************************************/
        const val ALL = OUT_ALL or CAP_ALL
        protected const val OUT_MASK = 0xFF80 // Include LONG_UNROLL
    }

    // Kotlin's protected leaves out the package, which the JVM's (and the Java's) includes.
    // Geodesic and GeodesicLine read the protected masks through here; the constants above
    // keep their protected access for a Java subclass, and javap of this class lists no new member.
    internal object Pkg {
        const val CAP_C1 = GeodesicMask.CAP_C1
        const val CAP_C1p = GeodesicMask.CAP_C1p
        const val CAP_C2 = GeodesicMask.CAP_C2
        const val CAP_C3 = GeodesicMask.CAP_C3
        const val CAP_C4 = GeodesicMask.CAP_C4
        const val OUT_ALL = GeodesicMask.OUT_ALL
        const val OUT_MASK = GeodesicMask.OUT_MASK
    }
}
