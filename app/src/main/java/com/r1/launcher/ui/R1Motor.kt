package com.r1.launcher.ui

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

// R1 has a single physical camera mounted on a stepper-motor gimbal
// (kernel driver step_motor_ms35774). The OEM exposes flipping only via a
// Quick Settings tile (com.rabbitescape.stepmotor/.CameraTileService), but
// CarrotOS hides the status bar in kiosk mode, so the QS tile is unreachable.
// We drive /sys/devices/platform/step_motor_ms35774/orientation directly
// through the carroot root shell on TCP 1337.
//
// Empirically observed orientation values (calibrated 2026-04-29):
//   0   -> front camera (lens pointed at the user, selfie position)
//   90  -> idle / rest (boot-default parked angle, lens pointed at neither user nor scene)
//   180 -> back camera (lens pointed away from user, for QR codes / external photos)
// Driver accepts other small ints in between (45, 89, 91, etc.) but clamps to
// 180 max — writes of 270 readback as 180.

const val MOTOR_FACE = 0
const val MOTOR_HOME = 90
const val MOTOR_BACK = 180

// Single-threaded executor so motor writes are FIFO. Earlier code spawned a
// fresh Thread per call; if the panel cycled stop/start (e.g. capture →
// retake), the HOME and BACK writes raced and the lens occasionally settled
// at HOME ("stuck in idle"). Serializing eliminates that race; the most
// recent enqueued value always wins.
private val motorExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "r1-motor").apply { isDaemon = true }
}

// The stepper is open-loop and misses steps on a single large traverse
// (>~45°), leaving the lens partway between the requested and the previous
// angle. We track the last-commanded angle and break moves into ≤30° chunks
// with a short settle delay between them — the driver issues a fresh step
// burst each chunk, which keeps the physical lens in lockstep with the
// logical sysfs value. Initialised to MOTOR_HOME (90) since that's the
// boot-default parked angle per the kernel driver comments above.
@Volatile private var lastTarget: Int = MOTOR_HOME

// Chunk size empirically tuned: ≤45° per write reliably completes on this
// stepper. The inter-chunk settle is just long enough for the previous
// step burst to physically finish — not for the lens to come to a full
// stop. A 90° move (HOME→BACK) ends up around 250 ms vs the old single
// write's 300 ms, while a single small wheel-nudge stays sub-100 ms.
private const val CHUNK_MAX_DEG = 45
private const val CHUNK_SETTLE_MS = 80L

/**
 * [chunked] = false writes the target in one go instead of walking it in 45°
 * hops. The hops exist because the stepper was reported to miss steps on a
 * long traverse, but a single write is what the OEM's own Quick Settings tile
 * does and it is what verifiably moves the lens on this device.
 *
 * [onDone] fires on the motor thread once every write has been issued — the
 * caller needs it to know when it is safe to re-open the camera.
 *
 * NOTE: the lens only physically moves while the camera is NOT streaming.
 * Writes issued with a capture session open are accepted by the driver (it
 * logs `run` / `step:` / `done` exactly as normal) but the lens stays put.
 * Callers that want real movement must stop the camera first — see
 * CameraPanel's flip sequence.
 */
fun setMotorOrientation(value: Int, chunked: Boolean = true, onDone: (() -> Unit)? = null) {
    Log.i("R1Motor", "setMotorOrientation($value, chunked=$chunked) queued")
    motorExecutor.execute {
        if (!chunked) {
            val clamped = value.coerceIn(MOTOR_FACE, MOTOR_BACK)
            if (clamped != lastTarget) {
                writeOrientation(clamped)
                lastTarget = clamped
            }
            onDone?.invoke()
            return@execute
        }
        val clamped = value.coerceIn(MOTOR_FACE, MOTOR_BACK)
        val start = lastTarget
        val total = clamped - start
        if (total == 0) {
            Log.i("R1Motor", "setMotorOrientation($clamped) no-op (already at target)")
            onDone?.invoke()
            return@execute
        }
        // Build the staircase of intermediate angles ending at clamped.
        val steps = ((Math.abs(total) + CHUNK_MAX_DEG - 1) / CHUNK_MAX_DEG).coerceAtLeast(1)
        val sequence = IntArray(steps) { i ->
            val frac = (i + 1).toFloat() / steps.toFloat()
            (start + (total * frac).toInt()).coerceIn(MOTOR_FACE, MOTOR_BACK)
        }.also { it[it.size - 1] = clamped }

        for ((idx, target) in sequence.withIndex()) {
            if (!writeOrientation(target)) {
                Log.e("R1Motor", "setMotorOrientation($clamped) gave up at chunk ${idx+1}/${sequence.size} (target=$target)")
                return@execute
            }
            // Let the physical stepper catch up before issuing the next
            // delta. Skip the settle for the final chunk — the caller's
            // next write (if any) is serialized behind us anyway.
            if (idx < sequence.size - 1) {
                runCatching { Thread.sleep(CHUNK_SETTLE_MS) }
            }
            lastTarget = target
        }
        onDone?.invoke()
    }
}

/**
 * Slow, deliberate re-home sequence — drives the lens to FACE (0°) with
 * small steps and long settles, writes FACE twice to ensure the motor
 * physically reaches the mechanical reference, then walks back to HOME.
 *
 * Mirrors the manual `for v in 60 30 0 0; sleep 0.3; …` carroot routine
 * that we know works for drift recovery. The fast chunked path used by
 * regular `setMotorOrientation` writes are too aggressive — large chunks
 * and short settles trade reliability for speed, which is fine for the
 * normal back/home swing but not for recovering an already-drifted lens.
 *
 * Final state: `lastTarget = MOTOR_HOME`, lens physically parked at idle.
 */
fun calibrateMotorToHome() {
    Log.i("R1Motor", "calibrateMotorToHome() queued")
    motorExecutor.execute {
        // Down-trip: ladder to FACE with 250ms settles. Each individual
        // ~30° hop is easily inside the stepper's reliable single-burst
        // range. The repeated 0 write below acts as a 'soft homing' —
        // the kernel computes delta=0 on the second write but if the
        // motor was still settling we give it more wall time.
        for (v in intArrayOf(60, 30, 0)) {
            if (!writeOrientation(v)) return@execute
            runCatching { Thread.sleep(250) }
        }
        if (!writeOrientation(0)) return@execute
        runCatching { Thread.sleep(800) }
        // Up-trip back to HOME.
        for (v in intArrayOf(30, 60, 90)) {
            if (!writeOrientation(v)) return@execute
            runCatching { Thread.sleep(250) }
        }
        lastTarget = MOTOR_HOME
        Log.i("R1Motor", "calibrateMotorToHome() done")
    }
}

private fun writeOrientation(target: Int): Boolean {
    // One quick retry — carroot is a single-listener nc on :1337 and
    // briefly refuses connections when another caller (toggleWifi,
    // factoryReset, etc.) is in-flight. Without a retry, a coincident
    // toggle silently swallows the motor write and the lens stays put.
    var attempt = 0
    while (attempt < 2) {
        attempt++
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", 1337), 1500)
                s.getOutputStream().apply {
                    write("echo $target > /sys/devices/platform/step_motor_ms35774/orientation\n".toByteArray())
                    flush()
                }
                // Small grace — lets the carroot side flush echo and hand
                // the file write to the kernel driver. The per-chunk
                // physical-motor wait happens in the chunk-loop settle.
                Thread.sleep(60)
                Log.i("R1Motor", "carroot write done for orientation=$target (attempt=$attempt)")
                return true
            }
        } catch (t: Throwable) {
            Log.w("R1Motor", "writeOrientation($target) attempt=$attempt FAILED: ${t.javaClass.simpleName}: ${t.message}")
            runCatching { Thread.sleep(150) }
        }
    }
    return false
}
