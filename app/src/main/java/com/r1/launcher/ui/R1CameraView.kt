package com.r1.launcher.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat

/**
 * Full-screen Camera2 preview + still capture for the Camera app.
 *
 * Adapted from the (file-private) `R1StillCameraView` in
 * [OpenClawCameraPanel]. Copied rather than shared on purpose: OpenClaw's
 * snap-and-ask flow is load-bearing for an existing app and its view is
 * entangled with that panel's capture/retake lifecycle. This one is owned by
 * the Camera app and free to change.
 *
 * The R1 has ONE physical sensor on a stepper-motor gimbal, but the HAL
 * exposes it as two logical cameras (0 = BACK, 1 = FRONT) and rotates the
 * gimbal itself when a session opens. Writing the motor sysfs directly while a
 * session is open is therefore pointless — verified on device: the write moves
 * the lens, and ~0.5 s later the HAL yanks it straight back to its own idea of
 * the position. Opening the matching camera id is what actually turns the lens
 * and keeps it turned.
 */
class R1CameraView(
    context: Context,
    /** Which logical camera to open. The HAL advertises two devices over the
     *  one physical sensor and rotates the gimbal itself when the session
     *  opens, so this is what actually turns the lens. */
    private val wantFront: Boolean = false,
    private val onEvent: (Event) -> Unit,
) : TextureView(context) {

    sealed class Event {
        /** A still frame, JPEG-encoded, ready to hand to PhotoStore. */
        data class Captured(val jpeg: ByteArray) : Event()
        data class Failed(val message: String) : Event()
        /** Preview is live — used to drop the "starting camera…" placeholder. */
        object Ready : Event()
    }

    private val manager = context.getSystemService(CameraManager::class.java)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var sensorOrientation: Int = 0

    @Volatile private var stopped = true
    /** Guards against a second capture landing while the first is in flight. */
    @Volatile private var capturePending = false
    /**
     * `camera != null` is not enough of a guard: `openCamera()` is reachable
     * from both [start] and the SurfaceTexture callback, and the window
     * between calling `manager.openCamera` and `onOpened` assigning `camera`
     * is wide enough that both callers get through. That produced a second
     * CONNECT for camera 0 on every panel open, and the two capture sessions
     * fought over the preview surface — the visible symptom was a preview
     * stuck on a solid fill colour, never showing real frames.
     */
    @Volatile private var opening = false
    private var retries = 0

    private val textureListener = object : SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = openCamera()
        override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean { stop(); return true }
        override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
    }

    fun start() {
        stopped = false
        retries = 0
        if (thread == null) {
            thread = HandlerThread("r1-camera").also { it.start() }
            handler = Handler(thread!!.looper)
        }
        surfaceTextureListener = textureListener
        if (isAvailable) openCamera()
    }

    /**
     * Tear down in the right order. The teardown itself is posted onto the
     * camera handler and the thread is only quit at the end of that runnable,
     * so any Camera2 callback already queued gets to run against a live
     * looper. Quitting the thread inline (the obvious implementation) left
     * the framework posting to a dead Handler —
     * `IllegalStateException: sending message to a Handler on a dead thread`
     * — and wedged the HAL badly enough that the next panel open produced a
     * blank preview.
     */
    fun stop() {
        stopped = true
        capturePending = false
        opening = false
        val h = handler
        val t = thread
        val s = session
        val c = camera
        val ir = imageReader
        handler = null
        thread = null
        session = null
        camera = null
        imageReader = null

        val teardown = Runnable {
            runCatching { s?.stopRepeating() }
            runCatching { s?.close() }
            runCatching { c?.close() }
            runCatching { ir?.close() }
            t?.quitSafely()
        }
        if (h == null || !h.post(teardown)) teardown.run()
    }

    fun capture() {
        if (stopped) return onEvent(Event.Failed("camera not ready"))
        if (capturePending) return
        val device = camera ?: return onEvent(Event.Failed("camera not ready"))
        val activeSession = session ?: return onEvent(Event.Failed("preview not ready"))
        val reader = imageReader ?: return onEvent(Event.Failed("camera buffer not ready"))
        capturePending = true
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
        }.build()
        runCatching { activeSession.capture(request, null, handler) }
            .onFailure {
                capturePending = false
                onEvent(Event.Failed(it.message ?: "capture failed"))
            }
    }

    @Synchronized
    private fun openCamera() {
        if (stopped || camera != null || opening) return
        val selected = selectCamera() ?: return onEvent(Event.Failed("no camera found"))
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return onEvent(Event.Failed("camera permission needed"))

        opening = true
        runCatching {
            manager.openCamera(selected, object : CameraDevice.StateCallback() {
                override fun onOpened(dev: CameraDevice) {
                    opening = false
                    if (stopped) { dev.close(); return }
                    camera = dev
                    startPreview(dev)
                }
                override fun onDisconnected(dev: CameraDevice) {
                    opening = false; dev.close(); camera = null
                }
                override fun onError(dev: CameraDevice, error: Int) {
                    opening = false
                    dev.close(); camera = null
                    // Cameras 0 and 1 are the same physical sensor and the HAL
                    // lists them as conflicting, so a front/back swap races the
                    // previous device's close and lands on IN_USE (1) or
                    // MAX_CAMERAS_IN_USE (2). Both clear on their own within a
                    // few hundred ms, so retry once before telling the user.
                    val transient = error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE
                    if (transient && retries < MAX_RETRIES && !stopped) {
                        retries++
                        Log.i("R1CameraView", "camera error $error, retry $retries")
                        handler?.postDelayed({ if (!stopped) openCamera() }, RETRY_DELAY_MS)
                        return
                    }
                    onEvent(Event.Failed(if (transient) "camera busy" else "camera error $error"))
                }
            }, handler)
        }.onFailure {
            opening = false
            onEvent(Event.Failed(it.message ?: "open camera failed"))
        }
    }

    private fun startPreview(device: CameraDevice) {
        if (stopped || camera !== device) return
        val texture = surfaceTexture ?: return
        texture.setDefaultBufferSize(PREVIEW_W, PREVIEW_H)
        val previewSurface = Surface(texture)
        val reader = ImageReader.newInstance(PREVIEW_W, PREVIEW_H, android.graphics.ImageFormat.JPEG, 1)
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val bytes = image.use {
                val buf = it.planes[0].buffer
                ByteArray(buf.remaining()).also(buf::get)
            }
            capturePending = false
            onEvent(Event.Captured(bytes))
        }, handler)

        runCatching {
            device.createCaptureSession(
                listOf(previewSurface, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (stopped || camera !== device) { runCatching { s.close() }; return }
                        session = s
                        runCatching {
                            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(previewSurface)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }.build()
                            if (!stopped && camera === device) {
                                s.setRepeatingRequest(req, null, handler)
                                onEvent(Event.Ready)
                            }
                        }.onFailure {
                            if (!stopped) onEvent(Event.Failed(it.message ?: "preview failed"))
                            runCatching { s.close() }
                        }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (!stopped) onEvent(Event.Failed("preview failed"))
                    }
                },
                handler,
            )
        }.onFailure { onEvent(Event.Failed(it.message ?: "preview failed")) }
    }

    private fun selectCamera(): String? {
        val ids = runCatching { manager.cameraIdList }.getOrNull() ?: return null
        val want = if (wantFront) CameraCharacteristics.LENS_FACING_FRONT
                   else CameraCharacteristics.LENS_FACING_BACK
        val match = ids.firstOrNull { id ->
            runCatching {
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == want
            }.getOrDefault(false)
        }
        val selected = match ?: ids.firstOrNull()
        if (selected != null) {
            sensorOrientation = runCatching {
                manager.getCameraCharacteristics(selected).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            }.getOrDefault(0)
            Log.i("R1CameraView", "using camera $selected (wantFront=$wantFront) sensorOrientation=$sensorOrientation")
        }
        return selected
    }

    private companion object {
        // The MT6765 HAL advertises larger JPEG sizes but 640x480 is what the
        // OpenClaw panel has shipped with, and it keeps captures small enough
        // to upload over the R1's Wi-Fi without a visible stall.
        const val MAX_RETRIES = 4
        const val RETRY_DELAY_MS = 350L
        const val PREVIEW_W = 640
        const val PREVIEW_H = 480
    }
}
