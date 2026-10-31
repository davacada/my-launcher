package com.davacada.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO
import android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING
import android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
import android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
import android.hardware.camera2.CameraManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.util.Range
import android.util.Size
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/** A video resolution the app offers. */
data class Res(val label: String, val size: Size)

/**
 * One focal-length button (16, 25, 35, 50, 78 mm, full-frame equivalent).
 * [id] is the real camera used, [ratio] the zoom sent to it (below 1 lets the phone switch to its
 * ultrawide by itself, above 1 crops the sensor). [available] is false when no lens on the phone can
 * reach that focal length; [reason] says why. [main] marks the button closest to the main camera.
 */
class Lens(
    val id: String,
    val chars: CameraCharacteristics,
    val mm: Int,
    val ratio: Float,
    val available: Boolean = true,
    val main: Boolean = false,
    val hidden: Boolean = false,
    val reason: String = "",
    val front: Boolean = false
) {
    val isMain: Boolean get() = main
    val label: String get() = if (front) "FRONT" else mm.toString()
    /** True when this focal length is a crop beyond the lens's optical view (some softness). */
    val digital: Boolean get() = ratio > 1.05f
}

/** Everything one camera can do, read from Android's camera characteristics. */
class Caps(val chars: CameraCharacteristics) {
    private val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
    private val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)

    val isFront = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
    val manualSensor = REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities
    val manualPost = REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities
    val raw = REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities
    val highSpeed = REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO in capabilities
    val isoRange: Range<Int> = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 3200)
    val expRange: Range<Long> = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 100_000_000L)
    val minFocus: Float = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    val sensorOrientation: Int = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    val aeRanges: Array<Range<Int>> = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
    val ois = (chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0))
        .contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON)
    val eis = (chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0))
        .contains(CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON)
    val maxZoom: Float = if (Build.VERSION.SDK_INT >= 30)
        chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 1f
    else chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f

    private val videoSizes: List<Size> = map.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList()
    private val hsSizes: List<Size> = if (highSpeed) (map.highSpeedVideoSizes?.toList() ?: emptyList()) else emptyList()

    /** Resolutions we offer, best first, limited to what this camera and the phone's encoders can record. */
    val resolutions: List<Res> = CANDIDATES.filter { (_, s) ->
        (videoSizes.contains(s) || hsSizes.contains(s)) && Encoders.best(s, 24) != null
    }.map { (label, s) -> Res(label, s) }

    fun maxNormalFps(size: Size): Int {
        if (!videoSizes.contains(size)) return 0
        val d = map.getOutputMinFrameDuration(MediaRecorder::class.java, size)
        return if (d > 0) (1_000_000_000L / d).toInt() else 30
    }

    /** Fixed frame rates the camera's own auto-exposure can lock to. */
    fun aeSupports(fps: Int) = aeRanges.any { it.lower == fps && it.upper == fps }

    fun highSpeedFps(size: Size): List<Int> = if (!hsSizes.contains(size)) emptyList() else try {
        map.getHighSpeedVideoFpsRangesFor(size).filter { it.lower == it.upper }.map { it.upper }.distinct()
    } catch (e: Exception) {
        emptyList()
    }

    /** How a given resolution + frame rate would be recorded, or null if it can't be. */
    fun plan(size: Size, fps: Int): Plan? {
        val normalOk = fps <= maxNormalFps(size) && (aeSupports(fps) || manualSensor) && Encoders.best(size, fps) != null
        if (normalOk) return Plan(highSpeed = false, aeLocked = aeSupports(fps))
        // The camera's own slow-motion mode: encoders often under-report what they manage here, so only require the size.
        if (fps > 30 && highSpeedFps(size).contains(fps) && Encoders.best(size, 30) != null) return Plan(highSpeed = true, aeLocked = true)
        return null
    }

    fun previewSize(): Size {
        val choices = map.getOutputSizes(SurfaceTexture::class.java) ?: return Size(1920, 1080)
        return choices.filter { it.width * 9 == it.height * 16 && it.width <= 1920 }.maxByOrNull { it.width } ?: Size(1920, 1080)
    }

    fun analysisSize(): Size? {
        val choices = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return null
        return choices.filter { it.width * 9 == it.height * 16 && it.width in 320..960 }.minByOrNull { it.width }
    }

    fun jpegSize(): Size = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height } ?: Size(4000, 3000)
    fun rawSize(): Size? = if (!raw) null else map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }

    /** 35mm-equivalent focal length, for labelling lenses relative to the main camera. */
    fun equivFocal(): Float {
        val f = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 0f
        val s = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 0f
        val diag = sqrt(s.width * s.width + s.height * s.height)
        return if (diag > 0) f * 43.27f / diag else 0f
    }

    /** Plain-language summary for the capabilities screen. */
    fun describe(): String {
        val sb = StringBuilder()
        resolutions.forEach { r ->
            val rates = FPS.filter { plan(r.size, it) != null }
            sb.append(r.label).append(": ").append(if (rates.isEmpty()) "—" else rates.joinToString(", ")).append(" fps")
            val hs = FPS.filter { plan(r.size, it)?.highSpeed == true }
            if (hs.isNotEmpty()) sb.append(" (").append(hs.joinToString(", ")).append(" as slow motion)")
            sb.append("  ·  ").append(Encoders.best(r.size, 24)?.let { if (it == MediaFormat.MIMETYPE_VIDEO_HEVC) "H.265" else "H.264" } ?: "").append('\n')
        }
        sb.append("Manual ISO & shutter: ").append(if (manualSensor) "yes" else "no").append('\n')
        sb.append("Manual white balance: ").append(if (manualPost) "yes" else "presets only").append('\n')
        sb.append("RAW photos: ").append(if (raw) "yes" else "no").append('\n')
        sb.append("ISO ").append(isoRange.lower).append("–").append(isoRange.upper)
            .append("  ·  OIS ").append(if (ois) "yes" else "no")
            .append("  ·  EIS ").append(if (eis) "yes" else "no")
        return sb.toString()
    }

    companion object {
        val FPS = listOf(24, 25, 30, 50, 60, 100, 120, 240)
        val CANDIDATES = listOf(
            "8K" to Size(7680, 4320),
            "4K" to Size(3840, 2160),
            "1080p" to Size(1920, 1080),
            "720p" to Size(1280, 720)
        )

        val FOCALS = listOf(16, 25, 35, 50, 78)

        /** Native full-frame-equivalent focal lengths of the phone's back cameras, for the info screen. */
        var nativeSummary = ""
            private set

        /**
         * One button per focal length in [FOCALS]. Each picks the widest real lens that can reach it
         * and zooms it: the main camera's own zoom range (which reaches the ultrawide on many phones),
         * another listed back camera, or, as a last resort, an unlisted ultrawide.
         */
        fun lenses(ctx: Context): List<Lens> {
            val mgr = ctx.getSystemService(CameraManager::class.java)
            val listed = try { mgr.cameraIdList.toList() } catch (e: Exception) { emptyList() }
            fun usableBack(id: String): CameraCharacteristics? = try {
                val c = mgr.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) null
                else if (Caps(c).resolutions.none { it.label == "1080p" || it.label == "4K" }) null // skip macro / depth
                else c
            } catch (e: Exception) { null }

            val back = listed.mapNotNull { id -> usableBack(id)?.let { id to it } }
            if (back.isEmpty()) return emptyList()
            val (mainId, mainChars) = back.first()
            val mainCaps = Caps(mainChars)
            val mainMm = mainCaps.equivFocal().takeIf { it > 5f } ?: 24f
            val mainLower = if (Build.VERSION.SDK_INT >= 30) mainChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f else 1f
            val mainUpper = mainCaps.maxZoom

            // Other real lenses: listed first, then (only if nothing wider exists) an unlisted one.
            class Real(val id: String, val chars: CameraCharacteristics, val mm: Float, val hidden: Boolean)
            val others = ArrayList<Real>()
            back.drop(1).forEach { (id, c) -> others.add(Real(id, c, Caps(c).equivFocal().takeIf { it > 5f } ?: mainMm, false)) }
            val widestReachable = minOf(mainMm * mainLower, others.minOfOrNull { it.mm } ?: mainMm)
            if (widestReachable > 17f) {
                for (i in 0..9) {
                    val id = i.toString()
                    if (id in listed) continue
                    val c = usableBack(id) ?: continue
                    val mm = Caps(c).equivFocal().takeIf { it > 5f } ?: continue
                    if (mm < mainMm * 0.9f) { others.add(Real(id, c, mm, true)); break }
                }
            }

            nativeSummary = buildString {
                append("Main camera ≈ ").append(mainMm.toInt()).append(" mm")
                if (mainLower < 0.95f) append(" (zooms out to ≈ ").append((mainMm * mainLower).toInt()).append(" mm)")
                others.forEach { append(", ").append(if (it.mm < mainMm) "ultrawide" else "other lens").append(" ≈ ").append(it.mm.toInt()).append(" mm") }
            }

            val out = ArrayList<Lens>()
            val mainButton = FOCALS.minByOrNull { abs(it - mainMm) } ?: 25
            for (mm in FOCALS) {
                val onMain = mm / mainMm
                if (onMain >= mainLower - 0.01f && onMain <= mainUpper) {
                    out.add(Lens(mainId, mainChars, mm, onMain.coerceAtLeast(mainLower), main = mm == mainButton))
                    continue
                }
                // Wider than the main camera reaches: use a wider real lens, zoomed in to the exact focal length.
                val wide = others.filter { it.mm <= mm + 0.5f }.maxByOrNull { it.mm }
                if (wide != null) {
                    out.add(Lens(wide.id, wide.chars, mm, (mm / wide.mm).coerceAtLeast(1f), hidden = wide.hidden))
                } else {
                    val widest = minOf(mainMm * mainLower, others.minOfOrNull { it.mm } ?: 999f)
                    out.add(Lens(mainId, mainChars, mm, 1f, available = false,
                        reason = "$mm mm is wider than this phone lets apps go (≈ ${widest.toInt()} mm)."))
                }
            }
            // The front (selfie) camera, as its own button after the focal lengths.
            listed.firstNotNullOfOrNull { id ->
                try {
                    val c = mgr.getCameraCharacteristics(id)
                    if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT &&
                        Caps(c).resolutions.isNotEmpty()) id to c else null
                } catch (e: Exception) { null }
            }?.let { (id, c) ->
                val mm = Caps(c).equivFocal().takeIf { it > 5f }?.toInt() ?: 24
                out.add(Lens(id, c, mm, 1f, front = true))
                nativeSummary += ", front camera ≈ $mm mm"
            }
            return out
        }
    }
}

data class Plan(val highSpeed: Boolean, val aeLocked: Boolean)

/** Which video encoder (HEVC preferred) can handle a size and frame rate on this phone. */
object Encoders {
    private val list by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder } }
    private val cache = HashMap<String, String?>()

    fun best(size: Size, fps: Int): String? = cache.getOrPut("${size.width}x${size.height}@$fps") {
        listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC).firstOrNull { mime -> supports(mime, size, fps) }
    }

    private fun supports(mime: String, size: Size, fps: Int): Boolean = list.any { info ->
        info.supportedTypes.any { it.equals(mime, true) } && try {
            val vc = info.getCapabilitiesForType(mime).videoCapabilities
            vc.isSizeSupported(size.width, size.height) &&
                (vc.areSizeAndRateSupported(size.width, size.height, fps.toDouble()) ||
                    vc.getSupportedFrameRatesFor(size.width, size.height).upper >= fps - 1)
        } catch (e: Exception) {
            false
        }
    }

    fun bitrate(size: Size, fps: Int): Int {
        val px = size.width.toLong() * size.height
        val base = when {
            px >= 7680L * 4320 -> 120_000_000
            px >= 3840L * 2160 -> 80_000_000
            px >= 1920L * 1080 -> 30_000_000
            else -> 14_000_000
        }
        return (base * (fps / 30f).coerceIn(1f, 3f)).toInt().coerceAtMost(200_000_000)
    }
}
