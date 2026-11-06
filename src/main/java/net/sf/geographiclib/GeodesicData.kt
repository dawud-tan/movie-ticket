/**
 * Implementation of the net.sf.geographiclib.GeodesicData class
 * <p>
 * Copyright (c) Charles Karney (2013) <charles@karney.com> and licensed
 * under the MIT/X11 License.  For more information, see
 * https://geographiclib.sourceforge.io/
 **********************************************************************/
package net.sf.geographiclib

/**
 * The results of geodesic calculations.
 * <p>
 * This is used to return the results for a geodesic between point 1
 * (<i>lat1</i>, <i>lon1</i>) and point 2 (<i>lat2</i>, <i>lon2</i>).  Fields
 * that have not been set will be filled with Double.NaN.  The returned
 * GeodesicData objects always include the parameters provided to {@link
 * Geodesic#Direct(double, double, double, double) Geodesic.Direct} and {@link
 * Geodesic#Inverse(double, double, double, double) Geodesic.Inverse} and it
 * always includes the field <i>a12</i>.
 **********************************************************************/
// Every field starts at Double.NaN, which the Java set in its no-argument constructor;
// Kotlin's Double.NaN has the same bits (0x7ff8000000000000).
open class GeodesicData {
    /**
     * latitude of point 1 (degrees).
     **********************************************************************/
    @JvmField
    var lat1 = Double.NaN
    /**
     * longitude of point 1 (degrees).
     **********************************************************************/
    @JvmField
    var lon1 = Double.NaN
    /**
     * azimuth at point 1 (degrees).
     **********************************************************************/
    @JvmField
    var azi1 = Double.NaN
    /**
     * latitude of point 2 (degrees).
     **********************************************************************/
    @JvmField
    var lat2 = Double.NaN
    /**
     * longitude of point 2 (degrees).
     **********************************************************************/
    @JvmField
    var lon2 = Double.NaN
    /**
     * azimuth at point 2 (degrees).
     **********************************************************************/
    @JvmField
    var azi2 = Double.NaN
    /**
     * distance between point 1 and point 2 (meters).
     **********************************************************************/
    @JvmField
    var s12 = Double.NaN
    /**
     * arc length on the auxiliary sphere between point 1 and point 2
     * (degrees).
     **********************************************************************/
    @JvmField
    var a12 = Double.NaN
    /**
     * reduced length of geodesic (meters).
     **********************************************************************/
    @JvmField
    var m12 = Double.NaN
    /**
     * geodesic scale of point 2 relative to point 1 (dimensionless).
     **********************************************************************/
    @JvmField
    var M12 = Double.NaN
    /**
     * geodesic scale of point 1 relative to point 2 (dimensionless).
     **********************************************************************/
    @JvmField
    var M21 = Double.NaN
    /**
     * area under the geodesic (meters<sup>2</sup>).
     **********************************************************************/
    @JvmField
    var S12 = Double.NaN
}
