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
import kotlin.math.abs
import kotlin.math.sqrt

/** A video resolution the app offers. */
data class Res(val label: String, val size: Size)

/** One back camera the user can pick (ultrawide, main…), plus optional digital crops of it. */
class Lens(val id: String, val chars: CameraCharacteristics, val zoom: Float, val crop: Float = 1f) {
    val label: String
        get() = if (abs(zoom * crop - 1f) < 0.05f) "1×"
        else if (zoom * crop < 1f) String.format("%.1f", zoom * crop).trimEnd('0').trimEnd('.')
        else String.format("%.0f×", zoom * crop)
}

/** Everything one camera can do, read from Android's camera characteristics. */
class Caps(val chars: CameraCharacteristics) {
    private val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
    private val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)

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
        if (Encoders.best(size, fps) == null) return null
        val normalOk = fps <= maxNormalFps(size) && (aeSupports(fps) || manualSensor)
        if (normalOk) return Plan(highSpeed = false, aeLocked = aeSupports(fps))
        if (fps > 30 && highSpeedFps(size).contains(fps)) return Plan(highSpeed = true, aeLocked = true)
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
        val FPS = listOf(24, 25, 30, 50, 60, 100, 120)
        val CANDIDATES = listOf(
            "8K" to Size(7680, 4320),
            "4K" to Size(3840, 2160),
            "1080p" to Size(1920, 1080),
            "720p" to Size(1280, 720)
        )

        /** Back cameras sorted wide → tele, with a 2× crop of the main one; duplicates removed. */
        fun lenses(ctx: Context): List<Lens> {
            val mgr = ctx.getSystemService(CameraManager::class.java)
            val ids = LinkedHashSet<String>()
            try { ids.addAll(mgr.cameraIdList) } catch (e: Exception) { }
            for (i in 0..9) ids.add(i.toString()) // some phones hide extra lenses from the list
            val found = ArrayList<Pair<String, CameraCharacteristics>>()
            for (id in ids) {
                try {
                    val c = mgr.getCameraCharacteristics(id)
                    if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                    val caps = Caps(c)
                    if (caps.resolutions.none { it.label == "1080p" || it.label == "4K" }) continue // skip macro / depth
                    found.add(id to c)
                } catch (e: Exception) { }
            }
            if (found.isEmpty()) return emptyList()
            val main = found.first()
            val mainF = Caps(main.second).equivFocal().takeIf { it > 0 } ?: 1f
            val out = ArrayList<Lens>()
            found.forEach { (id, c) ->
                val z = (Caps(c).equivFocal().takeIf { it > 0 } ?: mainF) / mainF
                if (out.none { abs(it.zoom - z) < 0.08f }) out.add(Lens(id, c, z))
            }
            out.sortBy { it.zoom }
            val mainLens = out.minByOrNull { abs(it.zoom - 1f) } ?: out.first()
            if (Caps(mainLens.chars).maxZoom >= 2f && out.none { it.zoom in 1.8f..2.4f }) {
                out.add(out.indexOf(mainLens) + 1, Lens(mainLens.id, mainLens.chars, mainLens.zoom, crop = 2f))
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
