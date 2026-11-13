/**
 * Implementation of the net.sf.geographiclib.GeoMath class
 * <p>
 * Copyright (c) Charles Karney (2013-2020) <charles@karney.com> and licensed
 * under the MIT/X11 License.  For more information, see
 * https://geographiclib.sourceforge.io/
 **********************************************************************/
package net.sf.geographiclib

/**
 * Mathematical functions needed by GeographicLib.
 * <p>
 * Define mathematical functions and constants so that any version of Java
 * can be used.
 **********************************************************************/
// A class with a companion, like Constants (see there), so javap matches the Java's but for
// Companion; every function is @JvmStatic, so Java still calls GeoMath.sq(x). Every Math call is
// java.lang.Math's, as the Java's were, and every comparison is between non-null Doubles with a
// Double literal, so it stays IEEE: -0.0 == 0.0, NaN != NaN.
@Suppress("NAME_SHADOWING")
open class GeoMath private constructor() {
    companion object {
        /**
         * The number of binary digits in the fraction of a double precision
         * number (equivalent to C++'s {@code numeric_limits<double>::digits}).
         **********************************************************************/
        const val digits = 53

        /**
         * Square a number.
         * <p>
         *
         * @param x the argument.
         * @return <i>x</i><sup>2</sup>.
         **********************************************************************/
        @JvmStatic
        fun sq(x: Double): Double {
            return x * x
        }

        /**
         * The inverse hyperbolic tangent function.  This is defined in terms of
         * Math.log1p(<i>x</i>) in order to maintain accuracy near <i>x</i> = 0.
         * In addition, the odd parity of the function is enforced.
         * <p>
         *
         * @param x the argument.
         * @return atanh(<i>x</i>).
         **********************************************************************/
        @JvmStatic
        fun atanh(x: Double): Double {
            var y = Math.abs(x)     // Enforce odd parity
            y = Math.log1p(2 * y / (1 - y)) / 2
            return if (x > 0.0) y else (if (x < 0.0) -y else x)
        }

        /**
         * Normalize a sine cosine pair.
         * <p>
         *
         * @param p    return parameter for normalized quantities with sinx<sup>2</sup>
         *             + cosx<sup>2</sup> = 1.
         * @param sinx the sine.
         * @param cosx the cosine.
         **********************************************************************/
        @JvmStatic
        fun norm(p: Pair, sinx: Double, cosx: Double) {
            val r = Math.hypot(sinx, cosx)
            p.first = sinx / r
            p.second = cosx / r
        }

        /**
         * The error-free sum of two numbers.
         * <p>
         *
         * @param u the first number in the sum.
         * @param v the second number in the sum.
         * @param p output Pair(<i>s</i>, <i>t</i>) with <i>s</i> = round(<i>u</i> +
         *          <i>v</i>) and <i>t</i> = <i>u</i> + <i>v</i> - <i>s</i>.
         *          <p>
         *          See D. E. Knuth, TAOCP, Vol 2, 4.2.2, Theorem B.
         **********************************************************************/
        @JvmStatic
        fun sum(p: Pair, u: Double, v: Double) {
            val s = u + v
            var up = s - v
            var vpp = s - up
            up -= u
            vpp -= v
            val t = if (s != 0.0) 0.0 - (up + vpp) else s
            // u + v =       s      + t
            //       = round(u + v) + t
            p.first = s
            p.second = t
        }

        /**
         * Evaluate a polynomial.
         * <p>
         *
         * @param N the order of the polynomial.
         * @param p the coefficient array (of size <i>N</i> + <i>s</i> + 1 or more).
         * @param s starting index for the array.
         * @param x the variable.
         * @return the value of the polynomial.
         * <p>
         * Evaluate <i>y</i> = &sum;<sub><i>n</i>=0..<i>N</i></sub>
         * <i>p</i><sub><i>s</i>+<i>n</i></sub>
         * <i>x</i><sup><i>N</i>&minus;<i>n</i></sup>.  Return 0 if <i>N</i> &lt; 0.
         * Return <i>p</i><sub><i>s</i></sub>, if <i>N</i> = 0 (even if <i>x</i> is
         * infinite or a nan).  The evaluation uses Horner's method.
         **********************************************************************/
        // p is nullable: with N < 0 the Java never reads it, so it returned 0 for a null array.
        @JvmStatic
        fun polyval(N: Int, p: DoubleArray?, s: Int, x: Double): Double {
            var N = N
            var s = s
            var y = if (N < 0) 0.0 else p!![s++]
            while (--N >= 0) y = y * x + p!![s++]
            return y
        }

        /**
         * Coarsen a value close to zero.
         * <p>
         *
         * @param x the argument
         * @return the coarsened value.
         * <p>
         * This makes the smallest gap in <i>x</i> = 1/16 &minus; nextafter(1/16, 0)
         * = 1/2<sup>57</sup> for reals = 0.7 pm on the earth if <i>x</i> is an angle
         * in degrees.  (This is about 1000 times more resolution than we get with
         * angles around 90 degrees.)  We use this to avoid having to deal with near
         * singular cases when <i>x</i> is non-zero but tiny (e.g.,
         * 10<sup>&minus;200</sup>).  This converts &minus;0 to +0; however tiny
         * negative numbers get converted to &minus;0.
         **********************************************************************/
        @JvmStatic
        fun AngRound(x: Double): Double {
            val z = 1 / 16.0
            var y = Math.abs(x)
            // The compiler mustn't "simplify" z - (z - y) to y
            y = if (y < z) z - (z - y) else y
            return Math.copySign(y, x)
        }

        /**
         * Normalize an angle.
         * <p>
         *
         * @param x the angle in degrees.
         * @return the angle reduced to the range [&minus;180&deg;, 180&deg;).
         * <p>
         * The range of <i>x</i> is unrestricted.
         **********************************************************************/
        @JvmStatic
        fun AngNormalize(x: Double): Double {
            val y = Math.IEEEremainder(x, 360.0)
            return if (Math.abs(y) == 180.0) Math.copySign(180.0, x) else y
        }

        /**
         * Normalize a latitude.
         * <p>
         *
         * @param x the angle in degrees.
         * @return x if it is in the range [&minus;90&deg;, 90&deg;], otherwise
         * return NaN.
         **********************************************************************/
        @JvmStatic
        fun LatFix(x: Double): Double {
            return if (Math.abs(x) > 90.0) Double.NaN else x
        }

        /**
         * The exact difference of two angles reduced to [&minus;180&deg;, 180&deg;].
         * <p>
         *
         * @param x the first angle in degrees.
         * @param y the second angle in degrees.
         * @param p output Pair(<i>d</i>, <i>e</i>) with <i>d</i> being the rounded
         *          difference and <i>e</i> being the error.
         *          <p>
         *          This computes <i>z</i> = <i>y</i> &minus; <i>x</i> exactly, reduced to
         *          [&minus;180&deg;, 180&deg;]; and then sets <i>z</i> = <i>d</i> + <i>e</i>
         *          where <i>d</i> is the nearest representable number to <i>z</i> and
         *          <i>e</i> is the truncation error.  If <i>z</i> = &plusmn;0&deg; or
         *          &plusmn;180&deg;, then the sign of <i>d</i> is given by the sign of
         *          <i>y</i> &minus; <i>x</i>.  The maximum absolute value of <i>e</i> is
         *          2<sup>&minus;26</sup> (for doubles).
         **********************************************************************/
        @JvmStatic
        fun AngDiff(p: Pair, x: Double, y: Double) {
            sum(p, Math.IEEEremainder(-x, 360.0), Math.IEEEremainder(y, 360.0))
            sum(p, Math.IEEEremainder(p.first, 360.0), p.second)
            if (p.first == 0.0 || Math.abs(p.first) == 180.0)
                // p = [d, e]...
                // If e == 0, take sign from y - x
                // else (e != 0, implies d = +/-180), d and e must have opposite signs
                p.first = Math.copySign(p.first, if (p.second == 0.0) y - x else -p.second)
        }

        /**
         * Evaluate the sine and cosine function with the argument in degrees
         *
         * @param p return Pair(<i>s</i>, <i>t</i>) with <i>s</i> = sin(<i>x</i>) and
         *          <i>c</i> = cos(<i>x</i>).
         * @param x in degrees.
         *          <p>
         *          The results obey exactly the elementary properties of the trigonometric
         *          functions, e.g., sin 9&deg; = cos 81&deg; = &minus; sin 123456789&deg;.
         **********************************************************************/
        @JvmStatic
        fun sincosd(p: Pair, x: Double) {
            // In order to minimize round-off errors, this function exactly reduces
            // the argument to the range [-45, 45] before converting it to radians.
            var d = x % 360.0
            // Math.round, not kotlin.math.round: half-up to a long, as the Java had it.
            val q = Math.round(d / 90).toInt() // If r is NaN this returns 0
            d -= (90 * q).toDouble()
            // now abs(r) <= 45
            val r = Math.toRadians(d)
            // Possibly could call the gnu extension sincos
            var s = Math.sin(r)
            var c = Math.cos(r)
            if (Math.abs(d) == 45.0) {
                c = Math.sqrt(0.5)
                s = Math.copySign(c, r)
            } else if (Math.abs(d) == 30.0) {
                c = Math.sqrt(0.75)
                s = Math.copySign(0.5, r)
            }
            var sinx: Double
            val cosx: Double
            when (q and 3) {
                0 -> { sinx = s; cosx = c }
                1 -> { sinx = c; cosx = -s }
                2 -> { sinx = -s; cosx = -c }
                else -> { sinx = -c; cosx = s } // case 3
            }
            if (sinx == 0.0) sinx = Math.copySign(sinx, x)
            p.first = sinx
            p.second = 0.0 + cosx
        }

        /**
         * Evaluate the sine and cosine function with reduced argument plus correction
         *
         * @param p return Pair(<i>s</i>, <i>t</i>) with <i>s</i> =
         *          sin(<i>x</i> +  <i>t</i>) and <i>c</i> = cos(<i>x</i> + <i>t</i>).
         * @param x reduced angle in degrees.
         * @param t correction in degrees.
         *          <p>
         *          This is a variant of GeoMath.sincosd allowing a correction to the angle to
         *          be supplied.  <i>x</i> x must be in [&minus;180&deg;, 180&deg;] and
         *          <i>t</i> is assumed to be a <i>small</i> correction.  GeoMath.AngRound is
         *          applied to the reduced angle to prevent problems with <i>x</i> + <i>t</i>
         *          being extremely close but not exactly equal to one of the four cardinal
         *          directions.
         **********************************************************************/
        @JvmStatic
        fun sincosde(p: Pair, x: Double, t: Double) {
            // In order to minimize round-off errors, this function exactly reduces
            // the argument to the range [-45, 45] before converting it to radians.
            var d = x % 360.0
            val q = Math.round(d / 90).toInt() // If r is NaN this returns 0
            d -= (90 * q).toDouble()
            // now abs(r) <= 45
            val r = Math.toRadians(AngRound(d + t))
            // Possibly could call the gnu extension sincos
            var s = Math.sin(r)
            var c = Math.cos(r)
            if (Math.abs(d) == 45.0) {
                c = Math.sqrt(0.5)
                s = Math.copySign(c, r)
            } else if (Math.abs(d) == 30.0) {
                c = Math.sqrt(0.75)
                s = Math.copySign(0.5, r)
            }
            var sinx: Double
            val cosx: Double
            when (q and 3) {
                0 -> { sinx = s; cosx = c }
                1 -> { sinx = c; cosx = -s }
                2 -> { sinx = -s; cosx = -c }
                else -> { sinx = -c; cosx = s } // case 3
            }
            if (sinx == 0.0) sinx = Math.copySign(sinx, x)
            p.first = sinx
            p.second = 0.0 + cosx
        }

        /**
         * Evaluate the atan2 function with the result in degrees
         *
         * @param y the sine of the angle
         * @param x the cosine of the angle
         * @return atan2(<i>y</i>, <i>x</i>) in degrees.
         * <p>
         * The result is in the range [&minus;180&deg; 180&deg;].  N.B.,
         * atan2d(&plusmn;0, &minus;1) = &plusmn;180&deg;.
         **********************************************************************/
        @JvmStatic
        fun atan2d(y: Double, x: Double): Double {
            var y = y
            var x = x
            // In order to minimize round-off errors, this function rearranges the
            // arguments so that result of atan2 is in the range [-pi/4, pi/4] before
            // converting it to degrees and mapping the result to the correct
            // quadrant.
            var q = 0
            if (Math.abs(y) > Math.abs(x)) {
                val t = x
                x = y
                y = t
                q = 2
            }
            if (x < 0.0) {
                x = -x
                ++q
            }
            // here x >= 0 and x >= abs(y), so angle is in [-pi/4, pi/4]
            var ang = Math.toDegrees(Math.atan2(y, x))
            when (q) {
                // Note that atan2d(-0.0, 1.0) will return -0.  However, we expect that
                // atan2d will not be called with y = -0.  If need be, include
                //
                //   case 0: ang = 0 + ang; break;
                //
                // and handle mpfr as in AngRound.
                1 -> ang = Math.copySign(180.0, y) - ang
                2 -> ang = 90 - ang
                3 -> ang = -90 + ang
                else -> {}
            }
            return ang
        }
    }
}
