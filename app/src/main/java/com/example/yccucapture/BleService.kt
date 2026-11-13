package com.example.yccucapture

import android.app.Service
import android.bluetooth.BluetoothAdapter
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
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import java.util.UUID

class BleService : Service() {

    companion object {
        private const val TAG = "YccuBle"

        // Service action names for startService()
        const val ACTION_START_SCAN = "com.example.yccucapture.action.START_SCAN"
        const val ACTION_STOP       = "com.example.yccucapture.action.STOP"

        // TODO: replace with the real UUIDs from your GATT discovery log.
        // These are placeholders that match a common Yamaha CCU layout.
        private val SERVICE_UUID: UUID =
            UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        private val NOTIFY_CHAR_UUID: UUID =
            UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        private val WRITE_CHAR_UUID: UUID =
            UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")

        // Standard Client Characteristic Configuration Descriptor
        private val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Frame parsing offsets (0-indexed into the raw notification payload)
        private const val FRAME_HEADER_0: Byte = 0x6B
        private const val FRAME_HEADER_1: Byte = 0x05
        private const val FRAME_HEADER_3: Byte = 0x01
        private const val FRAME_HEADER_4: Byte = 0x21
        private const val FRAME_MIN_LEN = 59
        private const val OFF_REG_1D = 48
        private const val OFF_REG_17 = 52
        private const val OFF_REG_19 = 56
        private const val IDLE: Int = 0xFF

        // Debounce window for button events
        private const val DEBOUNCE_MS = 120L
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    private val audioManager: AudioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private var lastButtonAt: Long = 0L

    // ---------------------------------------------------------------
    // Service lifecycle
    // ---------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SCAN -> startScan()
            ACTION_STOP -> shutdown()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        bluetoothGatt?.let { gatt ->
            notifyChar?.let { gatt.setCharacteristicNotification(it, false) }
            gatt.close()
        }
        bluetoothGatt = null
        notifyChar = null
        writeChar = null
    }

    // ---------------------------------------------------------------
    // Scanning
    // ---------------------------------------------------------------

    private fun startScan() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = manager.adapter ?: run {
            Log.e(TAG, "No Bluetooth adapter")
            return
        }
        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth is disabled")
            return
        }

        // TODO: adjust the name filter if your bike's advertised name differs.
        val filter = ScanFilter.Builder()
            .setDeviceName("YCCU_")
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        adapter.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.d(TAG, "Found ${result.device.name} (${result.device.address})")
            val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            manager.adapter.bluetoothLeScanner?.stopScan(this)
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
        }
    }

    // ---------------------------------------------------------------
    // GATT connection
    // ---------------------------------------------------------------

    private fun connect(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to ${device.address}")
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            Log.d(TAG, "State change: status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    gatt.close()
                    bluetoothGatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "Services discovered, status=$status")
            val service = gatt.getService(SERVICE_UUID) ?: run {
                Log.e(TAG, "Service $SERVICE_UUID not found")
                return
            }
            notifyChar = service.getCharacteristic(NOTIFY_CHAR_UUID)
            writeChar = service.getCharacteristic(WRITE_CHAR_UUID)

            if (notifyChar == null) {
                Log.e(TAG, "Notify characteristic not found")
                return
            }

            gatt.requestMtu(247)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU=$mtu status=$status")
            enableNotifications(gatt)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d(TAG, "Descriptor write status=$status")
            // Handshake with the CCU once notifications are live.
            sendHandshake(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value ?: return
            routeFrame(data)
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt) {
        val char = notifyChar ?: return
        gatt.setCharacteristicNotification(char, true)
        val cccd = char.getDescriptor(CCCD_UUID) ?: run {
            Log.e(TAG, "CCCD not found on notify characteristic")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
    }

    private fun sendHandshake(gatt: BluetoothGatt) {
        val char = writeChar ?: run {
            Log.w(TAG, "No write characteristic, skipping handshake")
            return
        }
        // From your log: "5a 01 7f 01 01 01 00 23" was the handshake payload.
        val payload = byteArrayOf(
            0x5A, 0x01, 0x7F.toByte(), 0x01,
            0x01, 0x01, 0x00, 0x23
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                char, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        } else {
            @Suppress("DEPRECATION")
            char.value = payload
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

    // ---------------------------------------------------------------
    // Frame routing
    // ---------------------------------------------------------------

    private fun routeFrame(data: ByteArray) {
        // Log everything so we can capture the play/pause register value.
        Log.d(TAG, "rx len=${data.size} ${data.toHex()}")

        if (data.size < FRAME_MIN_LEN) return
        if (data[0] != FRAME_HEADER_0) return
        if (data[1] != FRAME_HEADER_1) return
        if (data[3] != FRAME_HEADER_3 || data[4] != FRAME_HEADER_4) return

        val reg1D = data[OFF_REG_1D].toInt() and 0xFF
        val reg17 = data[OFF_REG_17].toInt() and 0xFF
        val reg19 = data[OFF_REG_19].toInt() and 0xFF

        if (reg1D == IDLE && reg17 == IDLE && reg19 == IDLE) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastButtonAt < DEBOUNCE_MS) return
        lastButtonAt = now

        when {
            reg1D != IDLE -> onVolumeButton(reg1D)
            reg17 != IDLE -> onTrackButton(reg17)
            reg19 != IDLE -> onTransportButton(reg19)
        }
    }

    // ---------------------------------------------------------------
    // Button mapping
    // ---------------------------------------------------------------

    private fun onVolumeButton(value: Int) {
        when (value) {
            0x05 -> adjustVolume(AudioManager.ADJUST_RAISE)
            0x06 -> adjustVolume(AudioManager.ADJUST_LOWER)
            else -> Log.w(TAG, "Unmapped vol 1d=0x%02x".format(value))
        }
    }

    private fun onTrackButton(value: Int) {
        when (value) {
            0x01 -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            0x02 -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            else -> Log.w(TAG, "Unmapped track 17=0x%02x".format(value))
        }
    }

    private fun onTransportButton(value: Int) {
        // TODO: confirm once the play/pause frame is captured. Based on
        // the CCU's pattern of 0x01/0x02 on other registers, play/pause
        // is most likely 0x19=0x01.
        when (value) {
            0x01 -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            else -> Log.w(TAG, "Unmapped transport 19=0x%02x".format(value))
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private fun adjustVolume(direction: Int) {
        // Volume must go through AudioManager, not dispatchMediaKeyEvent,
        // because KEYCODE_VOLUME_* are system keys.
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun dispatchMediaKey(keyCode: Int) {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02x".format(it) }
}
