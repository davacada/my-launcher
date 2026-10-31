package com.davacada.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs

/** What the user has chosen. Null ISO / shutter means automatic. */
class Settings {
    var video = true
    var res: Res? = null
    var fps = 25
    var shutterAngle: Int? = 180
    var photoShutterNs: Long? = null
    var iso: Int? = null
    var wb = 0            // 0 = auto, otherwise kelvin on the app's scale (4000K = daylight neutral)
    var slowMo = false    // recorded at fps (120 or more), saved to play back at slowPlay fps
    var slowPlay = 24     // 24, 30 or 60
    var af = true
    var focus = 1f        // 0 = closest, 1 = infinity (manual focus)
    var stab = true
    var raw = false
}

interface EngineListener {
    fun onReady(highSpeed: Boolean, analysis: Boolean)
    fun onMeter(iso: Int, exposureNs: Long)
    fun onAnalysis(hist: IntArray, overlay: IntArray?, w: Int, h: Int)
    fun onRecording(recording: Boolean)
    fun onSaved(uri: Uri, video: Boolean)
    fun onProblem(message: String)
    fun onOpenFailed(lens: Lens, error: Int)
}

/** Owns the camera: sessions, preview, recording, photos and manual controls. */
class CamEngine(private val ctx: Context, private val listener: EngineListener) {

    val settings = Settings()
    var overlays = OverlayFlags()

    private val mgr = ctx.getSystemService(CameraManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    private val bgExecutor = Executor { r -> bg?.post(r) }

    private var lens: Lens? = null
    var caps: Caps? = null
        private set
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var texture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var recSurface: Surface? = null
    private var analysisReader: ImageReader? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var recPfd: ParcelFileDescriptor? = null
    private var recUri: Uri? = null
    private var recTmp: java.io.File? = null   // slow motion records here first, then gets retimed
    private var highSpeed = false
    private var aeLocked = true
    private var configToken = 0
    var recording = false
        private set
    var displayRotation = Surface.ROTATION_90

    // Safe-mode ladder: each camera hardware error drops to a simpler setup.
    //  0 = preview + recording + analysis, 1 = no analysis stream,
    //  2 = recording stream only added while recording, 3 = also 1080p 30 fps
    var safeLevel = 0
        private set
    private var opened = false
    private var openRetries = 0
    private var recOwnedByRecorder = false
    private var pendingStart: MediaRecorder? = null
    private val logLines = ArrayDeque<String>()
    private val logTime = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun log(msg: String) {
        synchronized(logLines) {
            logLines.addLast(logTime.format(Date()) + "  " + msg)
            while (logLines.size > 24) logLines.removeFirst()
        }
    }

    fun debugLog(): String = synchronized(logLines) { logLines.joinToString("\n") }

    // auto-exposure we run ourselves when the camera's own can't (fixed 24/25 fps, one manual value)
    private var meteredIso = 400
    private var meteredExp = 10_000_000L
    private var softIso = 400
    private var softExp = 10_000_000L
    private var lastSoftUpdate = 0L
    private var lastMeter = 0L
    private var lastAnalysis = 0L

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        if (thread != null) return
        thread = HandlerThread("camera").also { it.start(); bg = Handler(it.looper) }
    }

    fun stop() {
        val h = bg
        val t = thread
        if (h != null) {
            // Finish any recording and close the camera on the camera thread, then end it.
            h.post { if (recording) stopRecording(); closeCamera() }
            t?.quitSafely()
        } else closeCamera()
        thread = null
        bg = null
    }

    @SuppressLint("MissingPermission")
    fun open(lens: Lens, surfaceTexture: SurfaceTexture) {
        val h = bg ?: return
        h.post {
            val sameDevice = device != null && this.lens?.id == lens.id
            this.lens = lens
            caps = Caps(lens.chars)
            texture = surfaceTexture
            pickDefaults()
            if (sameDevice) {
                // Same camera, different focal length: just change the zoom, no restart.
                if (session != null) repeat() else configure()
                return@post
            }
            closeCamera()
            opened = false
            log("open camera ${lens.id} (${lens.label}), safe mode $safeLevel")
            try {
                mgr.openCamera(lens.id, bgExecutor, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        log("opened"); opened = true; openRetries = 0
                        device = camera; configure()
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        log("disconnected"); camera.close(); if (device == camera) device = null
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        log("error $error " + if (opened) "after opening" else "while opening")
                        camera.close(); if (device == camera) device = null
                        session = null
                        val wasOpened = opened
                        if (wasOpened && error == ERR_DEVICE && safeLevel < 3) {
                            safeLevel++
                            log("retrying in safe mode $safeLevel")
                            if (safeLevel >= 3) settings.res?.let { pickSafeRes() }
                            bg?.postDelayed({ open(lens, surfaceTexture) }, 500)
                            main.post { listener.onProblem(SAFE_TEXT[safeLevel]) }
                        } else if (!wasOpened && openRetries < 2 && error != ERR_DISABLED) {
                            openRetries++
                            log("retry open ($openRetries)")
                            bg?.postDelayed({ open(lens, surfaceTexture) }, 700)
                        } else {
                            main.post { listener.onOpenFailed(lens, error) }
                        }
                    }
                })
            } catch (e: Exception) {
                log("openCamera threw: ${e.message}")
                main.post { listener.onOpenFailed(lens, -1) }
            }
        }
    }

    /** Re-applies settings that need a new session (resolution, frame rate, mode, RAW). */
    fun reconfigure() { bg?.post { if (!recording) configure() } }

    /** Re-applies settings that only change the repeating request (ISO, shutter, WB, focus…). */
    fun update() { bg?.post { repeat() } }

    private fun closeCamera() {
        try { session?.close() } catch (e: Exception) { }
        session = null
        try { device?.close() } catch (e: Exception) { }
        device = null
        releaseSurfaces()
    }

    private fun releaseSurfaces() {
        analysisReader?.close(); analysisReader = null
        jpegReader?.close(); jpegReader = null
        rawReader?.close(); rawReader = null
        if (!recOwnedByRecorder) recSurface?.release()
        recSurface = null; recOwnedByRecorder = false
        previewSurface?.release(); previewSurface = null
    }

    /** Makes sure the chosen resolution and frame rate are ones this camera can record. */
    fun pickDefaults() {
        val c = caps ?: return
        val s = settings
        if (s.res == null || c.resolutions.none { it.size == s.res!!.size }) {
            s.res = c.resolutions.firstOrNull { it.label == "4K" } ?: c.resolutions.firstOrNull()
        }
        val r = s.res ?: return
        if (s.slowMo && (s.fps < 100 || c.plan(r.size, s.fps) == null)) s.slowMo = false
        if (c.plan(r.size, s.fps) == null) {
            s.fps = Caps.FPS.firstOrNull { it in 24..30 && c.plan(r.size, it) != null }
                ?: Caps.FPS.firstOrNull { c.plan(r.size, it) != null } ?: 30
        }
    }

    // ------------------------------------------------------------------ sessions

    private fun configure(withAnalysis: Boolean = true) {
        val dev = device ?: return
        val c = caps ?: return
        val tex = texture ?: return
        val token = ++configToken
        try { session?.close() } catch (e: Exception) { }
        session = null
        releaseSurfaces()

        val s = settings
        val res = s.res
        val plan = if (s.video && res != null) planFor(c, res.size) else null
        highSpeed = plan?.highSpeed == true
        aeLocked = plan?.aeLocked ?: true

        val preview = if (highSpeed && res != null) res.size else c.previewSize()
        tex.setDefaultBufferSize(preview.width, preview.height)
        val pv = Surface(tex).also { previewSurface = it }
        val outputs = ArrayList<Surface>()
        outputs.add(pv)

        val deferred = safeLevel >= 2
        if (s.video && res != null && deferred) {
            // Safe mode: the recording stream only exists while recording.
            val r = recorder
            if (recording && r != null) {
                recSurface = r.surface; recOwnedByRecorder = true
                outputs.add(r.surface)
            }
        } else if (s.video && res != null) {
            val mime = Encoders.best(res.size, s.fps)
            if (mime == null) { problem("This phone can't encode ${res.label} at ${s.fps} fps."); return }
            val surface = MediaCodec.createPersistentInputSurface()
            try {
                // A throw-away recorder gives the surface its size before the camera uses it.
                val dummy = File(ctx.cacheDir, "probe.mp4")
                newRecorder().apply {
                    setupRecorder(this, surface, res.size, s.fps, mime, withAudio = false) { setOutputFile(dummy.absolutePath) }
                    prepare()
                    release()
                }
            } catch (e: Exception) {
                surface.release()
                problem("Recording at ${res.label} ${s.fps} fps isn't possible on this phone.")
                return
            }
            recSurface = surface
            outputs.add(surface)
        } else {
            val jr = c.jpegSize().let { ImageReader.newInstance(it.width, it.height, ImageFormat.JPEG, 2) }
            jr.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { saveJpeg(it) } }, bg)
            jpegReader = jr
            outputs.add(jr.surface)
            if (s.raw) c.rawSize()?.let { size ->
                val rr = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2)
                rr.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { onRawImage(it) } }, bg)
                rawReader = rr
                outputs.add(rr.surface)
            }
        }

        // Histogram and overlays now come from the on-screen picture, so no extra camera stream is needed.
        val analysis = false && withAnalysis
        if (analysis) c.analysisSize()?.let { size ->
            val ar = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
            ar.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.let { analyse(it) } }, bg)
            analysisReader = ar
            outputs.add(ar.surface)
        }

        val type = if (highSpeed) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR
        log("session: preview ${preview.width}x${preview.height}" +
            (if (s.video && res != null) ", video ${res.label} ${s.fps} fps" + (if (highSpeed) " high-speed" else "") +
                (if (deferred) (if (recSurface != null) " (recording)" else " (deferred)") else "") else ", photo") +
            (if (analysisReader != null) ", analysis" else "") + ", ${outputs.size} streams")
        val config = SessionConfiguration(type, outputs.map { OutputConfiguration(it) }, bgExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(sess: CameraCaptureSession) {
                    if (token != configToken) { sess.close(); return }
                    session = sess
                    log("session ready")
                    if (!aeLocked || s.iso != null || s.shutterAngle != null) seedSoftAe()
                    repeat()
                    pendingStart?.let { r ->
                        pendingStart = null
                        try {
                            r.start(); log("recording started (safe mode)")
                            main.post { listener.onRecording(true) }
                        } catch (e: Exception) {
                            log("recorder start failed: ${e.message}")
                            abortRecording("Recording couldn't start: ${e.message}")
                        }
                    }
                    val hasAnalysis = analysisReader != null
                    main.post { listener.onReady(highSpeed, hasAnalysis) }
                }

                override fun onConfigureFailed(sess: CameraCaptureSession) {
                    if (token != configToken) return
                    log("session configure failed")
                    // Big resolutions sometimes can't run the extra analysis stream: retry without it.
                    if (analysisReader != null) configure(withAnalysis = false)
                    else problem("The camera couldn't start with these settings. Try a lower resolution or frame rate.")
                }
            })
        try {
            dev.createCaptureSession(config)
        } catch (e: Exception) {
            log("createCaptureSession threw: ${e.message}")
            if (analysis && analysisReader != null) configure(withAnalysis = false)
            else problem("The camera couldn't start: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ requests

    private fun manualActive(): Boolean {
        val c = caps ?: return false
        val s = settings
        if (highSpeed || !c.manualSensor) return false
        return s.iso != null || (s.video && s.shutterAngle != null) || (!s.video && s.photoShutterNs != null) || (s.video && !aeLocked)
    }

    private fun seedSoftAe() {
        softIso = meteredIso; softExp = meteredExp
    }

    private fun frameNs(): Long = 1_000_000_000L / settings.fps.coerceAtLeast(1)

    private fun targetExposure(): Long {
        val c = caps ?: return softExp
        val s = settings
        val angle = s.shutterAngle
        val exp = if (s.video && angle != null) (frameNs() * angle / 360L) else if (!s.video && s.photoShutterNs != null) s.photoShutterNs!! else softExp
        val maxExp = if (s.video) frameNs() else c.expRange.upper
        return exp.coerceIn(c.expRange.lower, minOf(c.expRange.upper, maxExp))
    }

    private fun targetIso(): Int {
        val c = caps ?: return softIso
        return (settings.iso ?: softIso).coerceIn(c.isoRange.lower, c.isoRange.upper)
    }

    private fun buildRequest(template: Int, targets: List<Surface>): CaptureRequest.Builder? {
        val dev = device ?: return null
        val c = caps ?: return null
        val s = settings
        val b = dev.createCaptureRequest(template)
        targets.forEach { b.addTarget(it) }
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        if (manualActive()) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, targetIso())
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, targetExposure())
            if (s.video) b.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs())
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            if (s.video) {
                val fps = s.fps
                val range = c.aeRanges.firstOrNull { it.lower == fps && it.upper == fps }
                    ?: (if (highSpeed) Range(fps, fps) else c.aeRanges.filter { it.upper == fps }.maxByOrNull { it.lower })
                if (range != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            }
        }

        if (!highSpeed) {
            applyWhiteBalance(b, c)

            if (s.af || c.minFocus <= 0f) {
                b.set(CaptureRequest.CONTROL_AF_MODE,
                    if (s.video) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            } else {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, c.minFocus * (1f - s.focus))
            }
            if (s.video && c.eis) b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (s.stab) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            if (c.ois) b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (s.stab || !s.video) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        }

        val crop = lens?.ratio ?: 1f
        if (Build.VERSION.SDK_INT >= 30 && abs(crop - 1f) > 0.01f) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, crop)
        } else if (crop > 1f) {
            c.chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { a ->
                val w = (a.width() / crop).toInt(); val h = (a.height() / crop).toInt()
                b.set(CaptureRequest.SCALER_CROP_REGION, Rect((a.width() - w) / 2, (a.height() - h) / 2, (a.width() + w) / 2, (a.height() + h) / 2))
            }
        }
        return b
    }

    // ------------------------------------------------------------------ white balance (slider in kelvin)

    /** The camera's own daylight colour gains and matrix, learned from a frame shot in daylight mode. */
    private var dayGains: RggbChannelVector? = null
    private var dayMatrix: ColorSpaceTransform? = null
    private var wbCaps: Caps? = null

    private fun manualWbOk(c: Caps): Boolean = c.manualPost

    private fun applyWhiteBalance(b: CaptureRequest.Builder, c: Caps) {
        val k = settings.wb
        if (k == 0) { b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO); return }
        val real = WhiteBalance.realKelvin(k)
        val g = dayGains; val m = dayMatrix
        if (manualWbOk(c) && g != null && m != null && wbCaps === c) {
            val (fr, fb) = WhiteBalance.factors(real)
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, m)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(g.red * fr, g.greenEven, g.greenOdd, g.blue * fb))
            return
        }
        // Not learned yet (or the lens can't take exact values): use the nearest built-in mode.
        // Daylight mode is also what teaches us the daylight gains on the next frame.
        val mode = if (manualWbOk(c)) CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT else when {
            real < 3500 -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
            real < 4600 -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
            real < 6200 -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
            real < 7500 -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            else -> CameraMetadata.CONTROL_AWB_MODE_SHADE
        }
        b.set(CaptureRequest.CONTROL_AWB_MODE, mode)
    }

    private fun learnDaylight(result: TotalCaptureResult) {
        if (result.get(CaptureResult.CONTROL_AWB_MODE) != CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT) return
        val c = caps ?: return
        val g = result.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: return
        val m = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM) ?: return
        val first = dayGains == null || wbCaps !== c
        dayGains = g; dayMatrix = m; wbCaps = c
        if (first && settings.wb != 0 && manualWbOk(c)) bg?.post { repeat() }
    }

    private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            learnDaylight(result)
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
            val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            if (!manualActive()) { meteredIso = iso; meteredExp = exp }
            val now = System.currentTimeMillis()
            if (now - lastMeter > 250) { lastMeter = now; main.post { listener.onMeter(iso, exp) } }
        }
    }

    private fun repeat() {
        val sess = session ?: return
        val pv = previewSurface ?: return
        val targets = ArrayList<Surface>()
        targets.add(pv)
        if (recording) recSurface?.let { targets.add(it) }
        analysisReader?.let { if (!highSpeed) targets.add(it.surface) }
        val template = if (settings.video) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        val req = buildRequest(template, targets)?.build() ?: return
        try {
            if (highSpeed && sess is CameraConstrainedHighSpeedCaptureSession) {
                sess.setRepeatingBurst(sess.createHighSpeedRequestList(req), null, bg)
            } else {
                sess.setRepeatingRequest(req, resultCallback, bg)
            }
        } catch (e: Exception) {
            log("repeating request failed: ${e.message}")
            problem("The camera stopped responding: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ analysis (histogram, zebras, peaking, false colour, auto exposure)

    private fun analyse(img: Image) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastAnalysis < 66) return
            lastAnalysis = now
            val plane = img.planes[0]
            val buf = plane.buffer
            val rs = plane.rowStride; val ps = plane.pixelStride
            val gw = 160; val gh = 90
            val sx = img.width / gw.toFloat(); val sy = img.height / gh.toFloat()
            val luma = IntArray(gw * gh)
            val hist = IntArray(64)
            var sum = 0L
            for (y in 0 until gh) {
                val row = (y * sy).toInt() * rs
                for (x in 0 until gw) {
                    val v = buf.get(row + (x * sx).toInt() * ps).toInt() and 0xFF
                    luma[y * gw + x] = v
                    hist[v shr 2]++
                    sum += v
                }
            }
            val mean = sum / (gw * gh).toFloat() / 255f
            softAutoExposure(mean)

            val o = overlays
            var overlay: IntArray? = null
            if (o.zebras || o.peaking || o.falseColor) {
                overlay = IntArray(gw * gh)
                for (y in 0 until gh) for (x in 0 until gw) {
                    val i = y * gw + x
                    val v = luma[i]
                    var color = 0
                    if (o.falseColor) color = falseColor(v)
                    if (o.peaking && x in 1 until gw - 1 && y in 1 until gh - 1) {
                        val g = abs(luma[i + 1] - luma[i - 1]) + abs(luma[i + gw] - luma[i - gw])
                        if (g > 70) color = 0xFF33E07A.toInt()
                    }
                    if (o.zebras && v >= 242 && ((x + y) / 2) % 3 == 0) color = 0xD0FFFFFF.toInt()
                    overlay[i] = color
                }
            }
            main.post { listener.onAnalysis(hist, overlay, gw, gh) }
        } catch (e: Exception) {
        } finally {
            img.close()
        }
    }

    private fun falseColor(v: Int): Int = when {
        v < 10 -> 0xC0702090.toInt()      // crushed blacks: purple
        v < 40 -> 0xA02F6FD6.toInt()      // shadows: blue
        v in 110..135 -> 0xA04CAF50.toInt() // mid grey: green (skin/grey card)
        v in 165..185 -> 0xA0F48FB1.toInt() // a stop over: pink
        v > 248 -> 0xC0E0453A.toInt()     // clipped: red
        v > 230 -> 0xA0F2C94C.toInt()     // near clipping: yellow
        else -> 0
    }

    /** Keeps exposure right when the phone's own auto exposure can't be used. */
    /** Average brightness of the picture (0–1), used to keep exposure right when the app runs it. */
    fun feedLuma(mean: Float) { bg?.post { softAutoExposure(mean) } }

    private fun softAutoExposure(mean: Float) {
        if (!manualActive()) return
        val s = settings
        val isoAuto = s.iso == null
        val shutterAuto = if (s.video) s.shutterAngle == null else s.photoShutterNs == null
        if (!isoAuto && !shutterAuto) return
        val now = System.currentTimeMillis()
        if (now - lastSoftUpdate < 250) return
        val target = 0.42f
        if (abs(mean - target) < 0.03f) return
        val c = caps ?: return
        val k = (target / mean.coerceAtLeast(0.02f)).coerceIn(0.8f, 1.25f)
        if (shutterAuto) {
            val maxExp = if (s.video) frameNs() else 66_000_000L
            val newExp = (softExp * k).toLong().coerceIn(c.expRange.lower, minOf(maxExp, c.expRange.upper))
            val used = newExp.toFloat() / softExp
            softExp = newExp
            if (isoAuto && abs(used - k) > 0.02f) softIso = (softIso * k / used).toInt().coerceIn(c.isoRange.lower, c.isoRange.upper)
        } else {
            softIso = (softIso * k).toInt().coerceIn(c.isoRange.lower, c.isoRange.upper)
        }
        lastSoftUpdate = now
        repeat()
    }

    // ------------------------------------------------------------------ recording

    private fun newRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()

    private fun orientationHint(): Int {
        val c = caps ?: return 0
        val deg = when (displayRotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_270 -> 270; Surface.ROTATION_180 -> 180; else -> 0 }
        // The front camera faces the other way, so its rotation adds instead of subtracting.
        return if (c.isFront) (c.sensorOrientation + deg) % 360 else (c.sensorOrientation - deg + 360) % 360
    }

    private fun setupRecorder(r: MediaRecorder, surface: Surface?, size: Size, fps: Int, mime: String, withAudio: Boolean, output: MediaRecorder.() -> Unit) {
        if (withAudio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.output()
        r.setVideoEncodingBitRate(Encoders.bitrate(size, fps))
        r.setVideoFrameRate(fps)
        r.setVideoSize(size.width, size.height)
        r.setVideoEncoder(if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
        if (withAudio) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(48_000)
            r.setAudioEncodingBitRate(256_000)
            r.setAudioChannels(2)
        }
        if (surface != null) r.setInputSurface(surface)
        r.setOrientationHint(orientationHint())
    }

    fun startRecording() {
        bg?.post {
            if (recording || !settings.video) return@post
            val res = settings.res ?: return@post
            val deferred = safeLevel >= 2
            val surface = if (deferred) null else (recSurface ?: return@post)
            val mime = Encoders.best(res.size, settings.fps) ?: Encoders.best(res.size, 30) ?: return@post
            val slow = settings.slowMo
            try {
                if (slow) {
                    val tmp = java.io.File(ctx.cacheDir, "slowmo_${System.currentTimeMillis()}.mp4")
                    val r = newRecorder()
                    setupRecorder(r, surface, res.size, settings.fps, mime, withAudio = false) { setOutputFile(tmp.absolutePath) }
                    r.prepare()
                    recorder = r; recTmp = tmp; recUri = null; recPfd = null
                    recording = true
                    if (deferred) { pendingStart = r; configure() }
                    else { repeat(); r.start(); log("slow-motion recording started"); main.post { listener.onRecording(true) } }
                    return@post
                }
                val name = "PRO_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ProCamera")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                    ?: throw IllegalStateException("no storage")
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "w") ?: throw IllegalStateException("no file")
                val r = newRecorder()
                setupRecorder(r, surface, res.size, settings.fps, mime, withAudio = true) { setOutputFile(pfd.fileDescriptor) }
                r.prepare()
                recorder = r; recPfd = pfd; recUri = uri
                recording = true
                if (deferred) {
                    pendingStart = r
                    configure()   // new session with the recorder's stream; starts in onConfigured
                } else {
                    repeat()
                    r.start()
                    log("recording started")
                    main.post { listener.onRecording(true) }
                }
            } catch (e: Exception) {
                log("recording setup failed: ${e.message}")
                abortRecording("Recording couldn't start: ${e.message}")
            }
        }
    }

    private fun abortRecording(msg: String) {
        recording = false
        pendingStart = null
        try { recorder?.release() } catch (e: Exception) { }
        recorder = null
        recPfd?.close(); recPfd = null
        recUri?.let { ctx.contentResolver.delete(it, null, null) }; recUri = null
        recTmp?.delete(); recTmp = null
        if (safeLevel >= 2) configure() else repeat()
        problem(msg)
        main.post { listener.onRecording(false) }
    }

    fun stopRecording() {
        val h = bg
        val work = Runnable {
            if (!recording) return@Runnable
            recording = false
            repeat()
            val ok = try { recorder?.stop(); true } catch (e: Exception) { log("recorder stop failed: ${e.message}"); false }
            recorder?.release(); recorder = null
            if (safeLevel >= 2 && device != null) configure()
            recPfd?.close(); recPfd = null
            val uri = recUri; recUri = null
            val tmp = recTmp; recTmp = null
            if (tmp != null) {
                if (ok) {
                    val saved = try { SlowMo.save(ctx, tmp, settings.slowPlay) } catch (e: Exception) { log("slow-motion save failed: ${e.message}"); null }
                    if (saved != null) {
                        log("slow motion: camera delivered ${saved.second} fps")
                        main.post { listener.onSaved(saved.first, true) }
                        if (saved.second < settings.fps * 3 / 4) problem("Asked for ${settings.fps} fps but the camera delivered ${saved.second} fps, so this clip is ${"%.1f".format(saved.second.toFloat() / settings.slowPlay)}x slower instead of ${"%.0f".format(settings.fps.toFloat() / settings.slowPlay)}x.")
                    } else problem("The slow-motion clip couldn't be saved.")
                }
                tmp.delete()
            }
            if (uri != null) {
                if (ok) {
                    ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                    main.post { listener.onSaved(uri, true) }
                } else ctx.contentResolver.delete(uri, null, null)
            }
            main.post { listener.onRecording(false) }
        }
        if (h != null && Looper.myLooper() != h.looper) h.post(work) else work.run()
    }

    /** Microphone level 0..1 while recording. */
    fun audioLevel(): Float = try { (recorder?.maxAmplitude ?: 0) / 32767f } catch (e: Exception) { 0f }

    // ------------------------------------------------------------------ photos

    private var pendingRawResult: TotalCaptureResult? = null
    private var pendingRawImage: Image? = null

    fun capturePhoto() {
        bg?.post {
            val sess = session ?: return@post
            val jr = jpegReader ?: return@post
            val targets = ArrayList<Surface>()
            targets.add(jr.surface)
            rawReader?.let { targets.add(it.surface) }
            val b = buildRequest(CameraDevice.TEMPLATE_STILL_CAPTURE, targets) ?: return@post
            b.set(CaptureRequest.JPEG_ORIENTATION, orientationHint())
            b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            pendingRawResult = null
            pendingRawImage?.close(); pendingRawImage = null
            try {
                sess.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                        if (rawReader != null) { pendingRawResult = result; maybeSaveRaw() }
                    }
                }, bg)
            } catch (e: Exception) {
                problem("The photo couldn't be taken: ${e.message}")
            }
        }
    }

    private fun onRawImage(img: Image) { pendingRawImage?.close(); pendingRawImage = img; maybeSaveRaw() }

    private fun maybeSaveRaw() {
        val img = pendingRawImage ?: return
        val result = pendingRawResult ?: return
        val c = caps ?: return
        pendingRawImage = null; pendingRawResult = null
        try {
            val uri = insertImage("dng", "image/x-adobe-dng") ?: return
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                DngCreator(c.chars, result).use { it.writeImage(out, img) }
            }
            finishImage(uri)
        } catch (e: Exception) {
            problem("The RAW file couldn't be saved: ${e.message}")
        } finally {
            img.close()
        }
    }

    private fun saveJpeg(img: Image) {
        try {
            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            val uri = insertImage("jpg", "image/jpeg") ?: return
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            finishImage(uri)
            main.post { listener.onSaved(uri, false) }
        } catch (e: Exception) {
            problem("The photo couldn't be saved: ${e.message}")
        } finally {
            img.close()
        }
    }

    private fun insertImage(ext: String, mime: String): Uri? {
        val name = "PRO_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + "." + ext
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ProCamera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        return ctx.contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
    }

    private fun finishImage(uri: Uri) {
        ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }

    // ------------------------------------------------------------------ helpers

    fun currentPlan(): Plan? {
        val c = caps ?: return null
        val r = settings.res ?: return null
        return planFor(c, r.size)
    }

    /** Slow motion always uses the camera's real 120 fps high-speed mode when it has one, so every frame is a new one. */
    private fun planFor(c: Caps, size: Size): Plan? {
        if (settings.slowMo && settings.fps in c.highSpeedFps(size)) return Plan(highSpeed = true, aeLocked = true)
        return c.plan(size, settings.fps)
    }

    fun previewBufferSize(): Size {
        val c = caps ?: return Size(1920, 1080)
        val r = settings.res
        return if (settings.video && r != null && currentPlan()?.highSpeed == true) r.size else c.previewSize()
    }

    private fun problem(msg: String) { log("problem: $msg"); main.post { listener.onProblem(msg) } }

    private fun pickSafeRes() {
        val c = caps ?: return
        c.resolutions.firstOrNull { it.label == "1080p" }?.let { settings.res = it }
        settings.fps = if (c.plan(settings.res!!.size, 30) != null) 30 else settings.fps
    }

    companion object {
        const val ERR_DEVICE = CameraDevice.StateCallback.ERROR_CAMERA_DEVICE
        const val ERR_DISABLED = CameraDevice.StateCallback.ERROR_CAMERA_DISABLED
        val SAFE_TEXT = arrayOf(
            "",
            "The camera hiccuped, so it's restarting with a simpler setup.",
            "The camera hiccuped again, so it's using a simpler recording setup.",
            "Still having trouble, so it's using 1080p at 30 fps for now. Tap (i) to see details."
        )
    }
}

class OverlayFlags(var zebras: Boolean = false, var peaking: Boolean = false, var falseColor: Boolean = false)
