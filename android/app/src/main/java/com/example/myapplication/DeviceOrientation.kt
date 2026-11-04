package com.example.myapplication

import kotlin.math.*

internal data class OrientationQuaternion(
    val w: Double = 1.0, val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0
) {
    fun normalized(): OrientationQuaternion {
        val norm = sqrt(w*w + x*x + y*y + z*z)
        return OrientationQuaternion(w/norm, x/norm, y/norm, z/norm)
    }

    operator fun times(b: OrientationQuaternion) = OrientationQuaternion(
        w*b.w - x*b.x - y*b.y - z*b.z,
        w*b.x + x*b.w + y*b.z - z*b.y,
        w*b.y - x*b.z + y*b.w + z*b.x,
        w*b.z + x*b.y - y*b.x + z*b.w
    )

    fun relativeTo(reference: OrientationQuaternion) =
        (OrientationQuaternion(reference.w, -reference.x, -reference.y, -reference.z) * this).normalized()

    fun eulerDegrees() = Triple(
        Math.toDegrees(atan2(2*(w*x + y*z), 1 - 2*(x*x + y*y))),
        Math.toDegrees(asin((2*(w*y - z*x)).coerceIn(-1.0, 1.0))),
        Math.toDegrees(atan2(2*(w*z + x*y), 1 - 2*(y*y + z*z)))
    )

    fun rotate(px: Double, py: Double, pz: Double): Triple<Double, Double, Double> {
        val tx = 2*(y*pz - z*py)
        val ty = 2*(z*px - x*pz)
        val tz = 2*(x*py - y*px)
        return Triple(px + w*tx + y*tz - z*ty,
            py + w*ty + z*tx - x*tz, pz + w*tz + x*ty - y*tx)
    }
}

internal data class OrientationEstimate(val rotation: OrientationQuaternion, val generation: Int, val stationary: Boolean = false)

/** Six-axis Mahony fusion, using the quaternion convention in dashboard/dashboard.py. */
internal class DeviceOrientation {
    private var q = OrientationQuaternion()
    private var lastMs: Long? = null
    private var generation = 0
    private val integral = DoubleArray(3)
    private val gyroBiasDps = DoubleArray(3)
    private var previousAccel: DoubleArray? = null
    private var quietSinceMs: Long? = null
    private var quietSamples = 0
    private var stationary = false

    fun update(timestampMs: Long, accel: DoubleArray, gyroDps: DoubleArray): OrientationEstimate? {
        if (timestampMs < 0 || accel.size != 3 || gyroDps.size != 3 ||
            accel.any { !it.isFinite() } || gyroDps.any { !it.isFinite() || abs(it) > 2000 }) return null
        val norm = sqrt(accel.sumOf { it*it })
        if (!norm.isFinite()) return null
        val previous = lastMs
        if (previous == null || timestampMs < previous || timestampMs - previous > 1500) {
            if (norm < .2) return null
            val roll = atan2(accel[1], accel[2])
            val pitch = atan2(-accel[0], hypot(accel[1], accel[2]))
            val cr = cos(roll/2); val sr = sin(roll/2)
            val cp = cos(pitch/2); val sp = sin(pitch/2)
            q = OrientationQuaternion(cr*cp, sr*cp, cr*sp, -sr*sp).normalized()
            integral.fill(0.0)
            gyroBiasDps.fill(0.0)
            previousAccel = accel.copyOf()
            quietSinceMs = null
            quietSamples = 0
            stationary = false
            lastMs = timestampMs
            generation++
            return OrientationEstimate(q, generation)
        }
        if (timestampMs == previous) return OrientationEstimate(q, generation, stationary)
        val elapsed = (timestampMs - previous)/1000.0
        lastMs = timestampMs
        val correctedDps = DoubleArray(3) { gyroDps[it] - gyroBiasDps[it] }
        val accelChange = previousAccel?.let { last -> sqrt((0..2).sumOf { (accel[it] - last[it]).pow(2) }) }
            ?: Double.POSITIVE_INFINITY
        // shortcut: six-axis data cannot distinguish very slow yaw below 0.2 deg/s from bias; use a heading reference when that matters.
        val quiet = norm in .85..1.15 && sqrt(correctedDps.sumOf { it*it }) < .2 && accelChange < .015
        if (quiet) {
            if (quietSinceMs == null) quietSinceMs = previous
            quietSamples++
        } else {
            quietSinceMs = null
            quietSamples = 0
        }
        previousAccel = accel.copyOf()
        stationary = quietSamples >= 5 && timestampMs - (quietSinceMs ?: timestampMs) >= 2000
        if (stationary) {
            val alpha = 1 - exp(-elapsed/5)
            for (axis in 0..2) gyroBiasDps[axis] += alpha*(gyroDps[axis] - gyroBiasDps[axis])
            integral.fill(0.0)
        }
        // Integrate the entire reporting interval in small steps, rather than losing time to a dt cap.
        val steps = ceil(elapsed/.02).toInt().coerceAtLeast(1)
        val dt = elapsed/steps
        val gyro = DoubleArray(3) { if (stationary) 0.0 else Math.toRadians(correctedDps[it]) }
        val trustGravity = norm in .85..1.15
        repeat(steps) {
            val w = q.w; val x = q.x; val y = q.y; val z = q.z
            var ex = 0.0; var ey = 0.0; var ez = 0.0
            if (trustGravity) {
                val ax = accel[0]/norm; val ay = accel[1]/norm; val az = accel[2]/norm
                val vx = 2*(x*z - w*y); val vy = 2*(w*x + y*z)
                val vz = w*w - x*x - y*y + z*z
                ex = ay*vz - az*vy; ey = az*vx - ax*vz; ez = ax*vy - ay*vx
                val error = doubleArrayOf(ex, ey, ez)
                if (!stationary) for (axis in 0..2) integral[axis] = (integral[axis] + .02*error[axis]*dt).coerceIn(-.2, .2)
            }
            val gx = gyro[0] + 2*ex + integral[0]
            val gy = gyro[1] + 2*ey + integral[1]
            val gz = gyro[2] + 2*ez + integral[2]
            q = OrientationQuaternion(
                w + .5*(-x*gx - y*gy - z*gz)*dt,
                x + .5*(w*gx + y*gz - z*gy)*dt,
                y + .5*(w*gy - x*gz + z*gx)*dt,
                z + .5*(w*gz + x*gy - y*gx)*dt
            ).normalized()
        }
        return OrientationEstimate(q, generation, stationary)
    }
}
