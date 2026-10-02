package com.bluewhale.android.find

import java.nio.ByteBuffer
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A one-shot GPS position shared privately with one contact. */
data class SharedPosition(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Float,
    val fixTimeMs: Long
) {
    companion object {
        private const val VERSION: Byte = 1
        private const val SIZE = 1 + 8 + 8 + 4 + 8

        /** Returns null for malformed or impossible positions. */
        fun decode(bytes: ByteArray): SharedPosition? {
            if (bytes.size != SIZE) return null
            val buf = ByteBuffer.wrap(bytes)
            if (buf.get() != VERSION) return null
            val p = SharedPosition(buf.double, buf.double, buf.float, buf.long)
            return p.takeIf { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 && it.accuracyMetres >= 0f }
        }
    }

    fun encode(): ByteArray = ByteBuffer.allocate(SIZE).apply {
        put(VERSION)
        putDouble(latitude)
        putDouble(longitude)
        putFloat(accuracyMetres)
        putLong(fixTimeMs)
    }.array()
}

object FindGeo {
    private const val EARTH_RADIUS_M = 6_371_000.0
    private val COMPASS = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")

    /** Great-circle distance in metres. */
    fun distanceMetres(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val dLat = Math.toRadians(toLat - fromLat)
        val dLon = Math.toRadians(toLon - fromLon)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(fromLat)) * cos(Math.toRadians(toLat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing from the first point to the second, degrees clockwise from north. */
    fun bearingDegrees(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val phi1 = Math.toRadians(fromLat)
        val phi2 = Math.toRadians(toLat)
        val dLon = Math.toRadians(toLon - fromLon)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun compassName(bearing: Double): String = COMPASS[(((bearing % 360) + 360) % 360 / 45.0).roundToInt() % 8]

    /** "120 m north-east" style description; very short distances lose the direction. */
    fun describe(distanceMetres: Double, bearing: Double): String = when {
        distanceMetres < 5 -> "within a few metres"
        distanceMetres < 1_000 -> "${distanceMetres.roundToInt()} m ${compassName(bearing)}"
        else -> "${"%.1f".format(java.util.Locale.US, distanceMetres / 1000)} km ${compassName(bearing)}"
    }
}

/** Chat lines for what finding produces. Pure, so they can be tested. */
object FindNotices {
    private const val OWN_FIX_MAX_AGE_MS = 10 * 60 * 1000L

    fun positionShared(
        nickname: String,
        theirs: SharedPosition,
        myLatitude: Double?,
        myLongitude: Double?,
        myFixTimeMs: Long?,
        nowMs: Long
    ): String {
        val age = describeAge(nowMs - theirs.fixTimeMs)
        val accuracy = "±${theirs.accuracyMetres.toInt()} m"
        val haveOwnFix = myLatitude != null && myLongitude != null && myFixTimeMs != null &&
            nowMs - myFixTimeMs <= OWN_FIX_MAX_AGE_MS
        return if (haveOwnFix) {
            val d = FindGeo.distanceMetres(myLatitude!!, myLongitude!!, theirs.latitude, theirs.longitude)
            val b = FindGeo.bearingDegrees(myLatitude, myLongitude, theirs.latitude, theirs.longitude)
            "$nickname shared their gps position: ${FindGeo.describe(d, b)} of you ($accuracy, $age)."
        } else {
            "$nickname shared their gps position: ${"%.5f".format(java.util.Locale.US, theirs.latitude)}, " +
                "${"%.5f".format(java.util.Locale.US, theirs.longitude)} ($accuracy, $age). " +
                "turn on location to see how far away they are."
        }
    }

    fun ringReceived(nickname: String, rang: Boolean): String =
        if (rang) "$nickname is looking for you nearby. your phone rang so they can find you."
        else "$nickname tried to ring your phone. only favourites can."

    private fun describeAge(ms: Long): String = when {
        ms < 60_000 -> "just now"
        ms < 60 * 60_000 -> "${ms / 60_000} min old"
        else -> "${ms / 3_600_000} h old"
    }
}
