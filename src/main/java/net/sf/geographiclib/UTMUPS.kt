package net.sf.geographiclib

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

// A local port, not upstream's UTMUPS: Qibla.kt calls forward and forwardBPN3DegreeTM. Open, as
// the Java class was, with its statics in a companion (@JvmStatic, so Java still calls
// UTMUPS.forward). The constants javac folded are const vals; the rest initialise in the Java's
// order, which matters because ALPHAS, BETAS and A1 read N. Every int stays an Int, every Math
// call is java.lang.Math's, and every comparison is between non-null Doubles.
open class UTMUPS {

    companion object {
        // ========== WGS84 Constants ==========
        private const val A = 6378137.0                    // Semi-major axis (meters)
        private const val F = 1.0 / 298.257223563          // Flattening
        private const val K0 = 0.9996                      // Central scale factor (UTM)

        // ========== Eccentricity Constants ==========
        private const val E2 = F * (2.0 - F)               // e² = 2f - f²
        private val E = Math.sqrt(E2)                      // e = sqrt(e²)
        private const val E2m = 1 - E2                     // 1 - e²

        // ========== Third Flattening and Derived Constants ==========
        private const val N = F / (2.0 - F)                // n = f / (2 - f)
        private const val N2 = N * N
        private const val N3 = N2 * N
        private const val N4 = N3 * N
        private const val N5 = N4 * N
        private const val N6 = N5 * N

        // ========== Krüger Series Coefficients (Order 6) ==========
        private val ALPHAS = computeAlphas()
        private val BETAS = computeBetas() // Inverse coefficients

        // ========== Rectifying Radius ==========
        private val A1 = computeA1()

        // ========== False Easting/Northing ==========
        private const val FALSE_EASTING_UTM = 500000.0
        private const val FALSE_NORTHING_UTM = 0.0
        private const val FALSE_NORTHING_SOUTH = 10000000.0

        @JvmStatic
        fun forward(lat: Double, lon: Double): UTMResult {
            if (Math.abs(lat) > 90.0) throw IllegalArgumentException("Latitude out of bounds")
            if (lat < -80.0 || lat >= 84.0) throw IllegalArgumentException("Outside UTM limits (use UPS)")

            val zone = getUTMZone(lat, lon)
            val northp = (lat >= 0.0)
            val lon0 = CentralMeridian(zone)

            val xy = performTransverseMercator(lat, lon0, lon, K0)

            val easting = xy[0] + FALSE_EASTING_UTM
            val northing = xy[1] + (if (northp) FALSE_NORTHING_UTM else FALSE_NORTHING_SOUTH)

            val result = UTMResult()
            result.zone = zone.toDouble()
            result.hemisphere = if (northp) "N" else "S"
            result.easting = easting
            result.northing = northing
            return result
        }

        @JvmStatic
        fun forwardBPN3DegreeTM(lat: Double, lon: Double): UTMResult {
            if (Math.abs(lat) > 90.0) throw IllegalArgumentException("Latitude out of bounds")

            // The Java boxed this as a Double; a primitive does the same arithmetic.
            var zone = getUTMZone(lat, lon).toDouble()
            val northp = (lat >= 0.0)
            var lon0 = CentralMeridian(zone.toInt())
            zone = if (lon > lon0) (zone + 0.2) else (zone + 0.1)
            lon0 = if (lon > lon0) (lon0 + 1.5) else (lon0 - 1.5)

            val k0_3deg = 0.9999
            val xy = performTransverseMercator(lat, lon0, lon, k0_3deg)

            val easting = xy[0] + 200000.0
            val northing = xy[1] + (if (northp) 0.0 else 1500000.0)

            val result = UTMResult()
            result.zone = zone
            result.hemisphere = if (northp) "N" else "S"
            result.easting = easting
            result.northing = northing
            return result
        }

        // ========== Public API: Forward ==========
        // (Existing forward methods kept as is...)

        /**
         * Convert UTM coordinates to WGS84 latitude/longitude.
         *
         * @param zone       UTM Zone (1-60)
         * @param hemisphere "N" or "S"
         * @param easting    Easting in meters
         * @param northing   Northing in meters
         * @return GeoResult containing lat, lon
         */
        // hemisphere is nullable so that an invalid zone still throws IllegalArgumentException
        // before a null hemisphere throws NullPointerException, in the Java's order.
        @JvmStatic
        fun reverse(zone: Int, hemisphere: String?, easting: Double, northing: Double): GeoResult {
            if (zone < 1 || zone > 60) throw IllegalArgumentException("Invalid zone")
            val northp = hemisphere!!.equals("N", ignoreCase = true)

            // Remove False Easting/Northing
            val x = easting - FALSE_EASTING_UTM
            val y = northing - (if (northp) FALSE_NORTHING_UTM else FALSE_NORTHING_SOUTH)

            val lon0 = CentralMeridian(zone)

            // Perform Reverse Projection
            return performTransverseMercatorReverse(x, y, lon0, K0)
        }

        /**
         * Convert 3-degree TM (BPN/Indonesia style) to WGS84.
         *
         * @param zone       3-degree Zone (e.g., 49.1, 49.2)
         * @param hemisphere "N" or "S"
         * @param easting    Easting in meters
         * @param northing   Northing in meters
         */
        @JvmStatic
        fun reverseBPN3DegreeTM(zone: Double, hemisphere: String, easting: Double, northing: Double): GeoResult {
            val northp = hemisphere.equals("N", ignoreCase = true)

            // Reconstruct Central Meridian from zone
            // Zone format: 49.1 (left split), 49.2 (right split)
            val baseZone = zone.toInt()
            val remainder = zone - baseZone
            val lon0Base = CentralMeridian(baseZone)

            val lon0: Double
            // Check split (using simple epsilon for double comparison safety)
            if (Math.abs(remainder - 0.1) < 0.01) {
                lon0 = lon0Base - 1.5
            } else if (Math.abs(remainder - 0.2) < 0.01) {
                lon0 = lon0Base + 1.5
            } else {
                throw IllegalArgumentException("Invalid 3-degree zone format (expected X.1 or X.2)")
            }

            // Remove False Easting/Northing (200,000 / 1,500,000)
            val x = easting - 200000.0
            val y = northing - (if (northp) 0.0 else 1500000.0)

            val k0_3deg = 0.9999

            return performTransverseMercatorReverse(x, y, lon0, k0_3deg)
        }

        // ========== Public API: Reverse (UTM/TM -> WGS84) ==========

        private fun performTransverseMercator(lat: Double, lon0: Double, lon: Double, scale: Double): DoubleArray {
            val phi = Math.toRadians(lat)
            val dlon = angleDiff(lon0, lon)
            val lam = Math.toRadians(dlon)

            val tau = Math.tan(phi)
            val tau2_plus_1 = 1.0 + tau * tau
            val sec_phi = Math.sqrt(tau2_plus_1)

            val e_sin_phi = E * (tau / sec_phi)
            val B = E * atanh(e_sin_phi)
            val tau_prime = tau * Math.cosh(B) - sec_phi * Math.sinh(B)

            val tau_prime_2 = tau_prime * tau_prime
            val cos_lam = Math.cos(lam)
            val sin_lam = Math.sin(lam)
            val denom = Math.sqrt(tau_prime_2 + cos_lam * cos_lam)

            val xi_prime = Math.atan2(tau_prime, cos_lam)
            val eta_prime = asinh(sin_lam / denom)

            var xi = xi_prime
            var eta = eta_prime

            for (j in 1..6) {
                val two_j_xi = 2.0 * j * xi_prime
                val two_j_eta = 2.0 * j * eta_prime
                val alpha = ALPHAS[j - 1]
                xi += alpha * Math.sin(two_j_xi) * Math.cosh(two_j_eta)
                eta += alpha * Math.cos(two_j_xi) * Math.sinh(two_j_eta)
            }

            val x = scale * A1 * eta
            val y = scale * A1 * xi
            return doubleArrayOf(x, y)
        }

        /**
         * Perform Transverse Mercator Reverse Projection.
         * Uses Newton's method for tau inversion and Inverse Krüger Series.
         */
        private fun performTransverseMercatorReverse(x: Double, y: Double, lon0: Double, scale: Double): GeoResult {
            // 1. Convert to normalized eta/xi
            val eta = x / (scale * A1)
            val xi = y / (scale * A1)

            // 2. Apply Inverse Krüger Series (using BETAS)
            var xi_prime = xi
            var eta_prime = eta

            for (j in 1..6) {
                val two_j_xi = 2.0 * j * xi
                val two_j_eta = 2.0 * j * eta
                val beta = BETAS[j - 1]

                xi_prime += beta * Math.sin(two_j_xi) * Math.cosh(two_j_eta)
                eta_prime += beta * Math.cos(two_j_xi) * Math.sinh(two_j_eta)
            }

            // 3. Convert conformal (xi', eta') to conformal latitude (tau') and longitude difference
            val sin_xi_p = Math.sin(xi_prime)
            val cos_xi_p = Math.cos(xi_prime)
            val sinh_eta_p = Math.sinh(eta_prime)
            @Suppress("UNUSED_VARIABLE")
            val cosh_eta_p = Math.cosh(eta_prime)

            // phi' = asin(sin(xi') / cosh(eta'))
            // tau' = tan(phi') = sin(xi') / sqrt(cosh^2(eta') - sin^2(xi'))
            // Using identity: denom = sqrt(sinh^2(eta') + cos^2(xi'))
            val denom = Math.hypot(sinh_eta_p, cos_xi_p)
            val tau_prime = sin_xi_p / denom

            val lam = Math.atan2(sinh_eta_p, cos_xi_p) // Longitude difference in radians

            // 4. Convert conformal lat (tau') to geodetic lat (tau) using Newton's method
            val tau = taupinv(tau_prime, E2)

            val phi = Math.atan(tau)

            val result = GeoResult()
            result.lat = Math.toDegrees(phi)
            result.lon = lon0 + Math.toDegrees(lam)
            return result
        }

        // ========== Internal Projection Logic ==========

        private fun getUTMZone(lat: Double, lon: Double): Int {
            // toInt() is the Java's (int) cast: NaN to 0, saturating at the Int range.
            var ilon = Math.floor(lon).toInt()
            if (ilon == 180) ilon = -180
            var zone = (ilon + 186) / 6
            if (lat >= 56.0 && lat < 64.0 && lon >= 3.0 && lon < 12.0) zone = 32
            if (lat >= 72.0 && lat < 84.0) {
                if (lon >= 0.0 && lon < 9.0) zone = 31
                else if (lon >= 9.0 && lon < 21.0) zone = 33
                else if (lon >= 21.0 && lon < 33.0) zone = 35
                else if (lon >= 33.0 && lon < 42.0) zone = 37
            }
            return Math.max(1, Math.min(60, zone))
        }

        private fun CentralMeridian(zone: Int): Double {
            return zone * 6.0 - 183.0
        }

        // ========== Math Helpers ==========

        private fun angleDiff(lon0: Double, lon: Double): Double {
            var dlon = lon - lon0
            if (dlon > 180.0) dlon -= 360.0
            else if (dlon <= -180.0) dlon += 360.0
            return dlon
        }

        private fun asinh(x: Double): Double {
            return Math.log(x + Math.sqrt(x * x + 1.0))
        }

        private fun atanh(x: Double): Double {
            return if (Math.abs(x) < 0.5) 0.5 * Math.log((1.0 + x) / (1.0 - x)) else 0.5 * Math.log1p(2.0 * x / (1.0 - x))
        }

        // Newton's method to solve for tau given tau' (taup)
        // tau' = sinh(asinh(tau) - e * atanh(e * sin(phi)))
        private fun taupinv(taup: Double, e2: Double): Double {
            var tau = taup // Initial guess
            val e = Math.sqrt(e2)

            // 5 iterations is usually sufficient for machine precision
            for (i in 0 until 5) {
                val tau2 = tau * tau
                @Suppress("UNUSED_VARIABLE")
                val sigma = Math.sqrt(1.0 + 1.0 / (1.0 + tau2)) // sigma = sqrt(1 + 1/(1+tau^2)) = sec(phi)/tau ? No.
                // Actually let's use simpler terms:
                // sin(phi) = tau / sqrt(1+tau^2)
                val hypot_tau = Math.sqrt(1.0 + tau2)
                val sin_phi = tau / hypot_tau

                // Function f(tau) = taup(tau) - target_taup
                // taup(tau) = sinh( asinh(tau) - e * atanh(e * sin_phi) )
                val term = asinh(tau) - e * atanh(e * sin_phi)
                val f_val = Math.sinh(term) - taup

                // Derivative f'(tau)
                // d(term)/dtau = 1/sqrt(1+tau^2) - e * (e * cos(phi) / sqrt(1+tau^2) / (1 - e^2 sin^2(phi)) ) * d(sin)/dtau ?
                // GeographicLib simplifies this:
                // f'(tau) = cosh(term) * (1/hypot_tau) * (1 - e^2 * cos^2(phi) / (1 - e^2 sin^2(phi)) ) ...

                // Let's use the simplified ratio from C++ implementation (Math::taupf derivative part)
                // But for robustness, simple numerical derivative or exact formula:
                // d(sinh(psi))/dtau = cosh(psi) * d(psi)/dtau
                // d(psi)/dtau = (1 - e^2)/( (1 - e^2 sin^2 phi) * sqrt(1+tau^2) )
                val cosh_psi = Math.cosh(term)
                val one_minus_e2_sin2 = 1.0 - e2 * sin_phi * sin_phi
                val d_psi_d_tau = (1.0 - e2) / (one_minus_e2_sin2 * hypot_tau)
                val f_prime = cosh_psi * d_psi_d_tau

                val delta = f_val / f_prime
                tau -= delta

                if (Math.abs(delta) < 1e-12 * Math.max(1.0, Math.abs(tau))) break
            }
            return tau
        }

        private fun computeA1(): Double {
            val series = 1.0 + N2 * (0.25 + N2 * (1.0 / 64.0 + N2 * (1.0 / 256.0)))
            return (A / (1.0 + N)) * series
        }

        private fun computeAlphas(): DoubleArray {
            val alphas = DoubleArray(6)
            alphas[0] = N * (0.5 + N * (-2.0 / 3.0 + N * (5.0 / 16.0 + N * (41.0 / 180.0 + N * (-127.0 / 288.0 + N * (7891.0 / 37800.0))))))
            alphas[1] = N2 * (13.0 / 48.0 + N * (-3.0 / 5.0 + N * (557.0 / 1440.0 + N * (281.0 / 630.0 + N * (-1983433.0 / 1935360.0)))))
            alphas[2] = N3 * (61.0 / 240.0 + N * (-103.0 / 140.0 + N * (15061.0 / 26880.0 + N * (167603.0 / 181440.0))))
            alphas[3] = N4 * (49561.0 / 161280.0 + N * (-179.0 / 168.0 + N * (6601661.0 / 7257600.0)))
            alphas[4] = N5 * (34729.0 / 80640.0 + N * (-3418889.0 / 1995840.0))
            alphas[5] = N6 * (212378941.0 / 319334400.0)
            return alphas
        }

        // ========== Coefficients Setup ==========

        private fun computeBetas(): DoubleArray {
            // Inverse coefficients (Beta) derived from n
            // These mimic the C++ betcoeff logic for Order 6
            val betas = DoubleArray(6)

            // β₁
            betas[0] = N * (-0.5 + N * (2.0 / 3.0 + N * (-37.0 / 96.0 + N * (1.0 / 360.0 + N * (81.0 / 512.0 + N * (-96199.0 / 604800.0))))))
            // β₂
            betas[1] = N2 * (-1.0 / 48.0 + N * (-1.0 / 15.0 + N * (437.0 / 1440.0 + N * (-46.0 / 105.0 + N * (1118711.0 / 3870720.0)))))
            // β₃
            betas[2] = N3 * (-17.0 / 480.0 + N * (37.0 / 840.0 + N * (209.0 / 4480.0 + N * (-5569.0 / 90720.0))))
            // β₄
            betas[3] = N4 * (-4397.0 / 161280.0 + N * (11.0 / 504.0 + N * (830251.0 / 7257600.0)))
            // β₅
            betas[4] = N5 * (-4583.0 / 161280.0 + N * (108847.0 / 3991680.0))
            // β₆
            betas[5] = N6 * (-20648693.0 / 638668800.0)

            return betas
        }
    }

    // ========== Result Classes ==========
    // zone is a boxed Double, null until set, as in the Java; toString throws on a null zone there
    // too (the Java unboxed it), and prints a null hemisphere as "null".
    open class UTMResult {
        @JvmField
        var zone: Double? = null
        @JvmField
        var hemisphere: String? = null
        @JvmField
        var easting = 0.0
        @JvmField
        var northing = 0.0

        override fun toString(): String {
            val symbols = DecimalFormatSymbols(Locale.US)
            val df = DecimalFormat("#.##", symbols)
            df.roundingMode = RoundingMode.HALF_EVEN
            val zone = zone!!
            return String.format(Locale.US, "zone %s, E %s, N %s",
                    (if (zone % 1 != 0.0) zone else (zone.toInt().toString() + hemisphere)), df.format(easting), df.format(northing))
        }
    }

    open class GeoResult {
        @JvmField
        var lat = 0.0
        @JvmField
        var lon = 0.0

        override fun toString(): String {
            return String.format(Locale.US, "Lat %10.9f, Lon %11.9f", lat, lon)
        }
    }
}
