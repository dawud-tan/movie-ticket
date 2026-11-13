/**
 * Implementation of the net.sf.geographiclib.PolygonResult class
 * <p>
 * Copyright (c) Charles Karney (2013) <charles@karney.com> and licensed
 * under the MIT/X11 License.  For more information, see
 * https://geographiclib.sourceforge.io/
 **********************************************************************/
package net.sf.geographiclib

/**
 * A container for the results from PolygonArea.
 **********************************************************************/
open class PolygonResult
/**
 * Constructor
 * <p>
 *
 * @param num       the number of vertices in the polygon.
 * @param perimeter the perimeter of the polygon or the length of the
 *                  polyline (meters).
 * @param area      the area of the polygon (meters<sup>2</sup>).
 **********************************************************************/
(
    /**
     * The number of vertices in the polygon
     **********************************************************************/
    @JvmField var num: Int,
    /**
     * The perimeter of the polygon or the length of the polyline (meters).
     **********************************************************************/
    @JvmField var perimeter: Double,
    /**
     * The area of the polygon (meters<sup>2</sup>).
     **********************************************************************/
    @JvmField var area: Double,
)
