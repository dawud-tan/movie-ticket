/**
 * Implementation of the net.sf.geographiclib.PolygonArea class
 * <p>
 * Copyright (c) Charles Karney (2013-2022) <charles@karney.com> and licensed
 * under the MIT/X11 License.  For more information, see
 * https://geographiclib.sourceforge.io/
 **********************************************************************/
package net.sf.geographiclib

/**
 * Polygon areas.
 * <p>
 * This computes the area of a geodesic polygon using the method given
 * Section 6 of
 * <ul>
 * <li>
 *   C. F. F. Karney,
 *   <a href="https://doi.org/10.1007/s00190-012-0578-z">
 *   Algorithms for geodesics</a>,
 *   J. Geodesy <b>87</b>, 43&ndash;55 (2013)
 *   (<a href="https://geographiclib.sourceforge.io/geod-addenda.html">
 *   addenda</a>).
 * </ul>
 * <p>
 * Arbitrarily complex polygons are allowed.  In the case self-intersecting of
 * polygons the area is accumulated "algebraically", e.g., the areas of the 2
 * loops in a figure-8 polygon will partially cancel.
 * <p>
 * This class lets you add vertices one at a time to the polygon.  The area
 * and perimeter are accumulated at two times the standard floating point
 * precision to guard against the loss of accuracy with many-sided polygons.
 * At any point you can ask for the perimeter and area so far.  There's an
 * option to treat the points as defining a polyline instead of a polygon; in
 * that case, only the perimeter is computed.
 * <p>
 * Example of use:
 * <pre>
 * {@code
 * // Compute the area of a geodesic polygon.
 *
 * // This program reads lines with lat, lon for each vertex of a polygon.
 * // At the end of input, the program prints the number of vertices,
 * // the perimeter of the polygon and its area (for the WGS84 ellipsoid).
 *
 * import java.util.*;
 * import net.sf.geographiclib.*;
 *
 * public class Planimeter {
 *   public static void main(String[] args) {
 *     PolygonArea p = new PolygonArea(Geodesic.WGS84, false);
 *     try {
 *       Scanner in = new Scanner(System.in);
 *       while (true) {
 *         double lat = in.nextDouble(), lon = in.nextDouble();
 *         p.AddPoint(lat, lon);
 *       }
 *     }
 *     catch (Exception e) {}
 *     PolygonResult r = p.Compute();
 *     System.out.println(r.num + " " + r.perimeter + " " + r.area);
 *   }
 * }}</pre>
 **********************************************************************/
// Open, with open members, as the Java's were. The private statics and ComputeCartesianArea sit
// in a companion, the last @JvmStatic so Java still calls PolygonArea.ComputeCartesianArea.
// _areasum stays null for a polyline, as the Java's did, and is only read when it is not one.
@Suppress("NAME_SHADOWING")
open class PolygonArea {

    private val _earth: Geodesic
    private val _area0: Double        // Full ellipsoid area
    private val _polyline: Boolean    // Assume polyline (don't close and skip area)
    private val _mask: Int
    private val _perimetersum: Accumulator
    private var _num = 0
    private var _crossings = 0
    private var _areasum: Accumulator? = null
    private var _lat0 = 0.0
    private var _lon0 = 0.0
    private var _lat1 = 0.0
    private var _lon1 = 0.0

    /**
     * Constructor for PolygonArea.
     * <p>
     *
     * @param earth    the Geodesic object to use for geodesic calculations.
     * @param polyline if true that treat the points as defining a polyline
     *                 instead of a polygon.
     **********************************************************************/
    constructor(earth: Geodesic, polyline: Boolean) {
        _earth = earth
        _area0 = _earth.EllipsoidArea()
        _polyline = polyline
        _mask = GeodesicMask.LATITUDE or GeodesicMask.LONGITUDE or
                GeodesicMask.DISTANCE or
                (if (_polyline) GeodesicMask.NONE else
                    GeodesicMask.AREA or GeodesicMask.LONG_UNROLL)
        _perimetersum = Accumulator(0.0)
        if (!_polyline)
            _areasum = Accumulator(0.0)
        Clear()
    }

    companion object {
        private fun transit(lon1: Double, lon2: Double): Int {
            var lon1 = lon1
            var lon2 = lon2
            // Return 1 or -1 if crossing prime meridian in east or west direction.
            // Otherwise return zero.
            // Compute lon12 the same way as Geodesic.Inverse.
            val p = Pair()
            GeoMath.AngDiff(p, lon1, lon2)
            lon1 = GeoMath.AngNormalize(lon1)
            lon2 = GeoMath.AngNormalize(lon2)
            val lon12 = p.first
            return if (lon12 > 0.0 && ((lon1 < 0.0 && lon2 >= 0.0) ||
                            (lon1 > 0.0 && lon2 == 0.0))) 1 else
                (if (lon12 < 0.0 && lon1 >= 0.0 && lon2 < 0.0) -1 else 0)
        }

        // an alternate version of transit to deal with longitudes in the direct
        // problem.
        private fun transitdirect(lon1: Double, lon2: Double): Int {
            // We want to compute exactly
            //   int(floor(lon2 / 360)) - int(floor(lon1 / 360))
            val lon1 = Math.IEEEremainder(lon1, 720.0)
            val lon2 = Math.IEEEremainder(lon2, 720.0)
            return ((if (lon2 >= 0.0 && lon2 < 360.0) 0 else 1) -
                    (if (lon1 >= 0.0 && lon1 < 360.0) 0 else 1))
        }

        // reduce Accumulator area to allowed range
        private fun AreaReduceA(area: Accumulator, area0: Double,
                                crossings: Int,
                                reverse: Boolean, sign: Boolean): Double {
            area.Remainder(area0)
            if ((crossings and 1) != 0)
                area.Add((if (area.Sum() < 0.0) 1 else -1) * area0 / 2)
            // area is with the clockwise sense.  If !reverse convert to
            // counter-clockwise convention.
            if (!reverse)
                area.Negate()
            // If sign put area in (-area0/2, area0/2], else put area in [0, area0)
            if (sign) {
                if (area.Sum() > area0 / 2)
                    area.Add(-area0)
                else if (area.Sum() <= -area0 / 2)
                    area.Add(+area0)
            } else {
                if (area.Sum() >= area0)
                    area.Add(-area0)
                else if (area.Sum() < 0.0)
                    area.Add(+area0)
            }
            return 0.0 + area.Sum()
        }

        // reduce double area to allowed range
        private fun AreaReduceB(area: Double, area0: Double,
                                crossings: Int,
                                reverse: Boolean, sign: Boolean): Double {
            var area = Math.IEEEremainder(area, area0)
            if ((crossings and 1) != 0)
                area += (if (area < 0.0) 1 else -1) * area0 / 2
            // area is with the clockwise sense.  If !reverse convert to
            // counter-clockwise convention.
            if (!reverse)
                area *= -1.0
            // If sign put area in (-area0/2, area0/2], else put area in [0, area0)
            if (sign) {
                if (area > area0 / 2)
                    area -= area0
                else if (area <= -area0 / 2)
                    area += area0
            } else {
                if (area >= area0)
                    area -= area0
                else if (area < 0.0)
                    area += area0
            }
            return 0.0 + area
        }

        /**
         * Calculates the planar area of a polygon using the Shoelace Formula.
         * Suitable for UTM or 3-degree TM coordinates.
         *
         * @param xCoords Array of Easting coordinates (x)
         * @param yCoords Array of Northing coordinates (y)
         * @return Area in square meters (always positive)
         * @throws IllegalArgumentException if arrays sizes mismatch or length < 3
         */
        @JvmStatic
        fun ComputeCartesianArea(xCoords: DoubleArray, yCoords: DoubleArray): Double {
            if (xCoords.size != yCoords.size)
                throw IllegalArgumentException("Coordinate arrays must match in length")
            if (xCoords.size < 3) return 0.0

            var area = 0.0
            val n = xCoords.size

            // Shoelace formula: (x1*y2 - y1*x2) + (x2*y3 - y2*x3) ...
            for (i in 0 until n) {
                val j = (i + 1) % n // Next vertex, wrapping to 0
                area += (xCoords[i] * yCoords[j]) - (xCoords[j] * yCoords[i])
            }

            return Math.abs(area) / 2.0
        }
    }

    /**
     * Clear PolygonArea, allowing a new polygon to be started.
     **********************************************************************/
    open fun Clear() {
        _num = 0
        _crossings = 0
        _perimetersum.Set(0.0)
        if (!_polyline) _areasum!!.Set(0.0)
        _lat0 = Double.NaN
        _lon0 = Double.NaN
        _lat1 = Double.NaN
        _lon1 = Double.NaN
    }

    /**
     * Add a point to the polygon or polyline.
     * <p>
     *
     * @param lat the latitude of the point (degrees).
     * @param lon the latitude of the point (degrees).
     *            <p>
     *            <i>lat</i> should be in the range [&minus;90&deg;, 90&deg;].
     **********************************************************************/
    open fun AddPoint(lat: Double, lon: Double) {
        if (_num == 0) {
            _lat1 = lat
            _lat0 = _lat1
            _lon1 = lon
            _lon0 = _lon1
        } else {
            val g = _earth.Inverse(_lat1, _lon1, lat, lon, _mask)
            _perimetersum.Add(g.s12)
            if (!_polyline) {
                _areasum!!.Add(g.S12)
                _crossings += transit(_lon1, lon)
            }
            _lat1 = lat
            _lon1 = lon
        }
        ++_num
    }

    /**
     * Add an edge to the polygon or polyline.
     * <p>
     *
     * @param azi azimuth at current point (degrees).
     * @param s   distance from current point to next point (meters).
     *            <p>
     *            This does nothing if no points have been added yet.  Use
     *            PolygonArea.CurrentPoint to determine the position of the new vertex.
     **********************************************************************/
    open fun AddEdge(azi: Double, s: Double) {
        if (_num > 0) {             // Do nothing if _num is zero
            val g = _earth.Direct(_lat1, _lon1, azi, s, _mask)
            _perimetersum.Add(g.s12)
            if (!_polyline) {
                _areasum!!.Add(g.S12)
                _crossings += transitdirect(_lon1, g.lon2)
            }
            _lat1 = g.lat2
            _lon1 = g.lon2
            ++_num
        }
    }

    /**
     * Return the results so far.
     * <p>
     *
     * @return PolygonResult(<i>num</i>, <i>perimeter</i>, <i>area</i>) where
     * <i>num</i> is the number of vertices, <i>perimeter</i> is the perimeter
     * of the polygon or the length of the polyline (meters), and <i>area</i>
     * is the area of the polygon (meters<sup>2</sup>) or Double.NaN of
     * <i>polyline</i> is true in the constructor.
     * <p>
     * Counter-clockwise traversal counts as a positive area.
     **********************************************************************/
    open fun Compute(): PolygonResult {
        return Compute(false, true)
    }

    /**
     * Return the results so far.
     * <p>
     *
     * @param reverse if true then clockwise (instead of counter-clockwise)
     *                traversal counts as a positive area.
     * @param sign    if true then return a signed result for the area if
     *                the polygon is traversed in the "wrong" direction instead of returning
     *                the area for the rest of the earth.
     * @return PolygonResult(<i>num</i>, <i>perimeter</i>, <i>area</i>) where
     * <i>num</i> is the number of vertices, <i>perimeter</i> is the perimeter
     * of the polygon or the length of the polyline (meters), and <i>area</i>
     * is the area of the polygon (meters<sup>2</sup>) or Double.NaN of
     * <i>polyline</i> is true in the constructor.
     * <p>
     * More points can be added to the polygon after this call.
     **********************************************************************/
    open fun Compute(reverse: Boolean, sign: Boolean): PolygonResult {
        if (_num < 2)
            return PolygonResult(_num, 0.0, if (_polyline) Double.NaN else 0.0)
        if (_polyline)
            return PolygonResult(_num, _perimetersum.Sum(), Double.NaN)

        val g = _earth.Inverse(_lat1, _lon1, _lat0, _lon0, _mask)
        val tempsum = Accumulator(_areasum!!)
        tempsum.Add(g.S12)

        return PolygonResult(_num, _perimetersum.Sum(g.s12),
                AreaReduceA(tempsum, _area0,
                        _crossings + transit(_lon1, _lon0),
                        reverse, sign))
    }

    /**
     * Return the results assuming a tentative final test point is added;
     * however, the data for the test point is not saved.  This lets you report
     * a running result for the perimeter and area as the user moves the mouse
     * cursor.  Ordinary floating point arithmetic is used to accumulate the
     * data for the test point; thus the area and perimeter returned are less
     * accurate than if AddPoint and Compute are used.
     * <p>
     *
     * @param lat     the latitude of the test point (degrees).
     * @param lon     the longitude of the test point (degrees).
     * @param reverse if true then clockwise (instead of counter-clockwise)
     *                traversal counts as a positive area.
     * @param sign    if true then return a signed result for the area if
     *                the polygon is traversed in the "wrong" direction instead of returning
     *                the area for the rest of the earth.
     * @return PolygonResult(<i>num</i>, <i>perimeter</i>, <i>area</i>) where
     * <i>num</i> is the number of vertices, <i>perimeter</i> is the perimeter
     * of the polygon or the length of the polyline (meters), and <i>area</i>
     * is the area of the polygon (meters<sup>2</sup>) or Double.NaN of
     * <i>polyline</i> is true in the constructor.
     * <p>
     * <i>lat</i> should be in the range [&minus;90&deg;, 90&deg;].
     **********************************************************************/
    open fun TestPoint(lat: Double, lon: Double,
                       reverse: Boolean, sign: Boolean): PolygonResult {
        if (_num == 0)
            return PolygonResult(1, 0.0, if (_polyline) Double.NaN else 0.0)

        var perimeter = _perimetersum.Sum()
        var tempsum = if (_polyline) 0.0 else _areasum!!.Sum()
        var crossings = _crossings
        val num = _num + 1
        for (i in 0 until (if (_polyline) 1 else 2)) {
            val g = _earth.Inverse(if (i == 0) _lat1 else lat, if (i == 0) _lon1 else lon,
                    if (i != 0) _lat0 else lat, if (i != 0) _lon0 else lon,
                    _mask)
            perimeter += g.s12
            if (!_polyline) {
                tempsum += g.S12
                crossings += transit(if (i == 0) _lon1 else lon,
                        if (i != 0) _lon0 else lon)
            }
        }

        if (_polyline)
            return PolygonResult(num, perimeter, Double.NaN)

        return PolygonResult(num, perimeter,
                AreaReduceB(tempsum, _area0, crossings,
                        reverse, sign))
    }

    /**
     * Return the results assuming a tentative final test point is added via an
     * azimuth and distance; however, the data for the test point is not saved.
     * This lets you report a running result for the perimeter and area as the
     * user moves the mouse cursor.  Ordinary floating point arithmetic is used
     * to accumulate the data for the test point; thus the area and perimeter
     * returned are less accurate than if AddPoint and Compute are used.
     * <p>
     *
     * @param azi     azimuth at current point (degrees).
     * @param s       distance from current point to final test point (meters).
     * @param reverse if true then clockwise (instead of counter-clockwise)
     *                traversal counts as a positive area.
     * @param sign    if true then return a signed result for the area if
     *                the polygon is traversed in the "wrong" direction instead of returning
     *                the area for the rest of the earth.
     * @return PolygonResult(<i>num</i>, <i>perimeter</i>, <i>area</i>) where
     * <i>num</i> is the number of vertices, <i>perimeter</i> is the perimeter
     * of the polygon or the length of the polyline (meters), and <i>area</i>
     * is the area of the polygon (meters<sup>2</sup>) or Double.NaN of
     * <i>polyline</i> is true in the constructor.
     **********************************************************************/
    open fun TestEdge(azi: Double, s: Double,
                      reverse: Boolean, sign: Boolean): PolygonResult {
        if (_num == 0)              // we don't have a starting point!
            return PolygonResult(0, Double.NaN, Double.NaN)

        val num = _num + 1
        var perimeter = _perimetersum.Sum() + s
        if (_polyline)
            return PolygonResult(num, perimeter, Double.NaN)

        var tempsum = _areasum!!.Sum()
        var crossings = _crossings
        // The Java's block here also declared lat, lon, s12, S12 and t, and used none of them.
        var g = _earth.Direct(_lat1, _lon1, azi, false, s, _mask)
        tempsum += g.S12
        crossings += transitdirect(_lon1, g.lon2)
        crossings += transit(g.lon2, _lon0)
        g = _earth.Inverse(g.lat2, g.lon2, _lat0, _lon0, _mask)
        perimeter += g.s12
        tempsum += g.S12

        return PolygonResult(num, perimeter,
                AreaReduceB(tempsum, _area0, crossings,
                        reverse, sign))
    }

    /**
     * @return <i>a</i> the equatorial radius of the ellipsoid (meters).  This is
     * the value inherited from the Geodesic object used in the constructor.
     **********************************************************************/
    open fun EquatorialRadius(): Double {
        return _earth.EquatorialRadius()
    }

    /**
     * @return <i>f</i> the flattening of the ellipsoid.  This is the value
     * inherited from the Geodesic object used in the constructor.
     **********************************************************************/
    open fun Flattening(): Double {
        return _earth.Flattening()
    }

    // ==========================================================
    // 1. SHOELACE ALGORITHM (Cartesian Area for UTM/TM)
    // ==========================================================

    /**
     * Report the previous vertex added to the polygon or polyline.
     * <p>
     *
     * @return Pair(<i>lat</i>, <i>lon</i>), the current latitude and longitude.
     * <p>
     * If no points have been added, then Double.NaN is returned.  Otherwise,
     * <i>lon</i> will be in the range [&minus;180&deg;, 180&deg;].
     **********************************************************************/
    open fun CurrentPoint(): Pair {
        return Pair(_lat1, _lon1)
    }
}
