package com.davacada.camera

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Bluetooth remote for Blackmagic cameras (Pocket Cinema Camera 4K and similar), using
 * Blackmagic's published camera-control service. Bluetooth carries settings and status only,
 * never the picture.
 */
@SuppressLint("MissingPermission")
class BmdRemote(private val ctx: Context, var listener: Listener) {

    interface Listener {
        fun onRemoteChanged()
        fun onRemoteFound(devices: List<BluetoothDevice>)
        fun onRemoteMessage(msg: String)
    }

    // live state reported by the camera
    var connected = false; private set
    var connecting = false; private set
    var cameraName = ""; private set
    var iso = 0; private set
    var shutterAngle = 0f; private set      // degrees
    var whiteBalance = 0; private set       // kelvin
    var tint = 0; private set
    var recording = false; private set
    var fps = 0; private set
    var fStop = 0f; private set
    var timecode = ""; private set
    private var fmtW = 0; private var fmtH = 0; private var fmtFlags = 0; private var fmtSensorFps = 0

    private val main = Handler(Looper.getMainLooper())
    private val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var outChar: BluetoothGattCharacteristic? = null
    private val found = LinkedHashMap<String, BluetoothDevice>()
    private var scanning = false
    private var pendingDevice: BluetoothDevice? = null

    // Bluetooth allows one operation at a time, so writes and subscriptions go through a queue.
    private val ops = ArrayDeque<() -> Boolean>()
    private var opBusy = false

    val available: Boolean get() = adapter != null
    val enabled: Boolean get() = adapter?.isEnabled == true

    // ------------------------------------------------------------------ scanning & connecting

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device ?: return
            if (found.put(d.address, d) == null) main.post { listener.onRemoteFound(found.values.toList()) }
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            main.post { listener.onRemoteMessage("Bluetooth scan failed (code $errorCode). Turn Bluetooth off and on and try again.") }
        }
    }

    fun scan() {
        val a = adapter ?: return
        if (!a.isEnabled) { listener.onRemoteMessage("Turn Bluetooth on first."); return }
        val scanner = a.bluetoothLeScanner ?: return
        found.clear()
        // Cameras already paired before are offered straight away.
        a.bondedDevices?.filter { it.name?.contains("Pocket", true) == true || it.name?.startsWith("A:") == true }?.forEach { found[it.address] = it }
        if (found.isNotEmpty()) listener.onRemoteFound(found.values.toList())
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try { scanner.startScan(listOf(filter), settings, scanCb); scanning = true } catch (e: Exception) {
            listener.onRemoteMessage("Bluetooth scan couldn't start: ${e.message}")
        }
        main.postDelayed({ stopScan() }, 15_000)
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (e: Exception) { }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d = deviceFrom(i) ?: return
            if (d.address != pendingDevice?.address) return
            when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                BluetoothDevice.BOND_BONDED -> { unregisterBond(); openGatt(d) }
                BluetoothDevice.BOND_NONE -> {
                    unregisterBond(); connecting = false; listener.onRemoteChanged()
                    listener.onRemoteMessage("Pairing didn't finish. Check the code shown on the camera's screen and try again.")
                }
            }
        }
    }
    private var bondRegistered = false
    private fun unregisterBond() { if (bondRegistered) { try { ctx.unregisterReceiver(bondReceiver) } catch (e: Exception) { }; bondRegistered = false } }

    private fun deviceFrom(i: Intent): BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
        i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    /** Pairs if needed (Android asks for the 6-digit code shown on the camera), then connects. */
    fun connect(device: BluetoothDevice) {
        stopScan()
        disconnect()
        connecting = true
        pendingDevice = device
        cameraName = device.name ?: "Blackmagic"
        listener.onRemoteChanged()
        if (device.bondState == BluetoothDevice.BOND_BONDED) { openGatt(device); return }
        if (!bondRegistered) {
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED)
            else ctx.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
            bondRegistered = true
        }
        listener.onRemoteMessage("Enter the 6-digit code shown on the camera's screen when your phone asks for it.")
        if (!device.createBond()) { unregisterBond(); openGatt(device) }
    }

    private fun openGatt(device: BluetoothDevice) {
        gatt = device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        unregisterBond()
        ops.clear(); opBusy = false
        try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) { }
        gatt = null; outChar = null
        val was = connected || connecting
        connected = false; connecting = false
        recording = false; timecode = ""
        if (was) main.post { listener.onRemoteChanged() }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                main.postDelayed({ try { g.discoverServices() } catch (e: Exception) { } }, 600)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasConnected = connected
                main.post {
                    disconnect()
                    listener.onRemoteMessage(if (wasConnected) "Lost the connection to the camera." else "Couldn't connect to the camera. Make sure its Bluetooth is on (Setup menu) and try again.")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE)
            if (svc == null) { main.post { disconnect(); listener.onRemoteMessage("That device isn't a Blackmagic camera.") }; return }
            outChar = svc.getCharacteristic(OUT_CONTROL)
            listOfNotNull(svc.getCharacteristic(IN_CONTROL), svc.getCharacteristic(TIMECODE), svc.getCharacteristic(STATUS))
                .forEach { ch -> enqueue { subscribe(g, ch) } }
            main.post {
                connected = outChar != null; connecting = false
                listener.onRemoteChanged()
                if (connected) listener.onRemoteMessage("Connected to $cameraName.")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") handleIncoming(ch.uuid, ch.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleIncoming(ch.uuid, value)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { nextOp() }
        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) { nextOp() }
    }

    private fun subscribe(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(CCCD) ?: return false
        val v = if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == BluetoothGatt.GATT_SUCCESS
        else @Suppress("DEPRECATION") run { d.value = v; g.writeDescriptor(d) }
    }

    private fun enqueue(op: () -> Boolean) {
        main.post { ops.addLast(op); if (!opBusy) nextOp() }
    }

    private fun nextOp() {
        main.post {
            opBusy = false
            while (ops.isNotEmpty()) {
                val op = ops.removeFirst()
                val started = try { op() } catch (e: Exception) { false }
                if (started) { opBusy = true; main.postDelayed({ if (opBusy) nextOp() }, 1500); return@post }
            }
        }
    }

    private fun send(packet: ByteArray) {
        if (!connected) return
        enqueue {
            val g = gatt ?: return@enqueue false
            val ch = outChar ?: return@enqueue false
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(ch, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            else @Suppress("DEPRECATION") run { ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; ch.value = packet; g.writeCharacteristic(ch) }
        }
    }

    // ------------------------------------------------------------------ commands

    fun toggleRecord() = setRecording(!recording)

    fun setRecording(on: Boolean) {
        // Media › Transport mode: [mode (0 preview, 2 record), speed, flags, slot 1, slot 2]
        send(packet(10, 1, TYPE_INT8, byteArrayOf((if (on) 2 else 0).toByte(), 0, 0, 0, 0)))
    }

    fun setIso(value: Int) = send(packet(1, 14, TYPE_INT32, le32(value)))
    fun setShutterAngle(degrees: Float) = send(packet(1, 11, TYPE_INT32, le32((degrees * 100).roundToInt())))
    fun setWhiteBalance(kelvin: Int, tint: Int = 0) = send(packet(1, 2, TYPE_INT16, le16(kelvin) + le16(tint)))
    fun autoWhiteBalance() = send(packet(1, 3, TYPE_VOID, ByteArray(0)))
    fun autoFocus() = send(packet(0, 1, TYPE_VOID, ByteArray(0)))
    /** 0 = closest, 1 = infinity. */
    fun setFocus(normalised: Float) = send(packet(0, 0, TYPE_FIXED16, fixed16(normalised.coerceIn(0f, 1f))))
    fun setFStop(f: Float) = send(packet(0, 2, TYPE_FIXED16, fixed16(log2(f * f))))

    /** Changes the frame rate, keeping the camera's current resolution. */
    fun setFps(value: Int): Boolean {
        if (fmtW == 0 || fmtH == 0) return false
        val sensor = if (fmtSensorFps > 0 && fmtSensorFps != fps) fmtSensorFps else value
        send(packet(1, 9, TYPE_INT16, le16(value) + le16(sensor) + le16(fmtW) + le16(fmtH) + le16(fmtFlags)))
        return true
    }

    private fun packet(category: Int, parameter: Int, type: Int, data: ByteArray, op: Int = 0): ByteArray {
        val body = byteArrayOf(category.toByte(), parameter.toByte(), type.toByte(), op.toByte()) + data
        val raw = byteArrayOf(0xFF.toByte(), body.size.toByte(), 0, 0) + body
        val padded = ByteArray((raw.size + 3) / 4 * 4)
        raw.copyInto(padded)
        return padded
    }

    private fun le16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
    private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun fixed16(v: Float) = le16((v * 2048f).roundToInt().coerceIn(-32768, 32767))

    // ------------------------------------------------------------------ status from the camera

    private fun handleIncoming(uuid: UUID, v: ByteArray) {
        when (uuid) {
            TIMECODE -> parseTimecode(v)
            IN_CONTROL -> parseControl(v)
        }
        main.post { listener.onRemoteChanged() }
    }

    private fun parseTimecode(v: ByteArray) {
        if (v.size < 4) return
        val o = v.size - 4
        fun bcd(b: Byte) = ((b.toInt() shr 4) and 0x0F) * 10 + (b.toInt() and 0x0F)
        timecode = String.format("%02d:%02d:%02d:%02d", bcd(v[o + 3]), bcd(v[o + 2]), bcd(v[o + 1]), bcd(v[o]))
    }

    private fun parseControl(v: ByteArray) {
        if (v.size < 8) return
        val bb = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN)
        val len = v[1].toInt() and 0xFF
        if (len < 4) return
        val cat = v[4].toInt() and 0xFF
        val par = v[5].toInt() and 0xFF
        val dataStart = 8
        val dataLen = (len - 4).coerceAtMost(v.size - dataStart)
        fun i16(k: Int) = if (dataLen >= (k + 1) * 2) bb.getShort(dataStart + k * 2).toInt() else 0
        fun i32() = if (dataLen >= 4) bb.getInt(dataStart) else 0
        when (cat to par) {
            1 to 14 -> iso = i32()
            1 to 11 -> shutterAngle = i32() / 100f
            1 to 2 -> { whiteBalance = i16(0); tint = i16(1) }
            10 to 1 -> if (dataLen >= 1) recording = v[dataStart].toInt() == 2
            1 to 9 -> { fps = i16(0); fmtSensorFps = i16(1); fmtW = i16(2); fmtH = i16(3); fmtFlags = i16(4) }
            0 to 2 -> { val av = i16(0) / 2048f; fStop = sqrt(2f.pow(av)) }
        }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("291d567a-6d75-11e6-8b77-86f30ca893d3")
        val OUT_CONTROL: UUID = UUID.fromString("5dd3465f-1aee-4299-8493-d2eca2f8e1bb")
        val IN_CONTROL: UUID = UUID.fromString("b864e140-76a0-416a-bf30-5876504537d9")
        val TIMECODE: UUID = UUID.fromString("6d8f2110-86f1-41bf-9afb-451d87e976c8")
        val STATUS: UUID = UUID.fromString("7fe8691d-95dc-4fc5-8abd-ca74339b51b9")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val TYPE_VOID = 0
        const val TYPE_INT8 = 1
        const val TYPE_INT16 = 2
        const val TYPE_INT32 = 3
        const val TYPE_FIXED16 = 128
    }
}
