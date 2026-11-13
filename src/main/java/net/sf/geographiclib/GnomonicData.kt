/**
 * Implementation of the net.sf.geographiclib.GnomonicData class
 * <p>
 * Copyright (c) BMW Car IT GmbH (2014-2016) <sebastian.mattheis@bmw-carit.de>
 * and licensed under the MIT/X11 License. For more information, see
 * https://geographiclib.sourceforge.io/
 **********************************************************************/
package net.sf.geographiclib

/**
 * The results of gnomonic projection.
 * <p>
 * This is used to return the results for a gnomonic projection of a point
 * (<i>lat</i>, <i>lon</i>) given a center point of projection (<i>lat0</i>,
 * <i>lon0</i>). The returned GnomonicData objects always include the
 * parameters provided to
 * {@link Gnomonic#Forward Gnomonic.Forward}
 * and
 * {@link Gnomonic#Reverse Gnomonic.Reverse}
 * and it always includes the fields <i>x</i>, <i>y</i>, <i>azi</i>. and
 * <i>rk</i>.
 **********************************************************************/
// Every field starts at Double.NaN, which the Java set in its no-argument constructor; the
// eight-argument one then overwrites all of them, as the Java's assigned them.
open class GnomonicData {
    /**
     * latitude of center point of projection (degrees).
     **********************************************************************/
    @JvmField
    var lat0 = Double.NaN
    /**
     * longitude of center point of projection (degrees).
     **********************************************************************/
    @JvmField
    var lon0 = Double.NaN
    /**
     * latitude of point (degrees).
     **********************************************************************/
    @JvmField
    var lat = Double.NaN
    /**
     * longitude of point (degrees).
     **********************************************************************/
    @JvmField
    var lon = Double.NaN
    /**
     * easting of point (meters).
     **********************************************************************/
    @JvmField
    var x = Double.NaN
    /**
     * northing of point (meters).
     **********************************************************************/
    @JvmField
    var y = Double.NaN
    /**
     * azimuth of geodesic at point (degrees).
     **********************************************************************/
    @JvmField
    var azi = Double.NaN
    /**
     * reciprocal of azimuthal scale at point.
     **********************************************************************/
    @JvmField
    var rk = Double.NaN

    /**
     * Initialize all the fields to Double.NaN.
     **********************************************************************/
    constructor()

    /**
     * Constructor initializing all the fields for gnomonic projection of a point
     * (<i>lat</i>, <i>lon</i>) given a center point of projection (<i>lat0</i>,
     * <i>lon0</i>).
     * <p>
     *
     * @param lat0 latitude of center point of projection (degrees).
     * @param lon0 longitude of center point of projection (degrees).
     * @param lat  latitude of point (degrees).
     * @param lon  longitude of point (degrees).
     * @param x    easting of point (meters).
     * @param y    northing of point (meters).
     * @param azi  azimuth of geodesic at point (degrees).
     * @param rk   reciprocal of azimuthal scale at point.
     */
    constructor(lat0: Double, lon0: Double, lat: Double, lon: Double,
                x: Double, y: Double, azi: Double, rk: Double) {
        this.lat0 = lat0
        this.lon0 = lon0
        this.lat = lat
        this.lon = lon
        this.x = x
        this.y = y
        this.azi = azi
        this.rk = rk
    }
}
