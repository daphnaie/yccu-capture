package com.example.yccucapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import java.util.UUID

class BleService : Service() {

    companion object {
        private const val TAG = "YccuBle"
        private const val CHANNEL_ID = "yccu_ble"
        private const val NOTIF_ID = 1

        const val ACTION_START_SCAN = "com.example.yccucapture.action.START_SCAN"
        const val ACTION_STOP = "com.example.yccucapture.action.STOP"

        // TODO: confirm these UUIDs match your working GATT discovery.
        // If the current app already connects, keep the values that are
        // already in your file — these are placeholders only.
        private val SERVICE_UUID: UUID =
            UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        private val NOTIFY_CHAR_UUID: UUID =
            UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        private val WRITE_CHAR_UUID: UUID =
            UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")

        // Standard Client Characteristic Configuration Descriptor
        private val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Frame parsing constants
        private const val FRAME_MIN_LEN = 59
        private const val FRAME_HEADER_0: Byte = 0x6B
        private const val FRAME_HEADER_1: Byte = 0x05
        private const val FRAME_HEADER_2: Byte = 0x00
        private const val FRAME_HEADER_3: Byte = 0x01
        private const val FRAME_HEADER_4: Byte = 0x21
        private const val IDLE: Int = 0xFF
        private const val IDLE_BYTE: Int = -1

        // Debounce window
        private const val DEBOUNCE_MS = 120L
    }

    // BLE state
    private var bluetoothGatt: BluetoothGatt? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    // Media session
    private var mediaSession: MediaSession? = null

    // Button edge detection
    private var lastBtnByte48: Int = -1
    private var lastBtnByte52: Int = -1

    // Audio
    private val audioManager: AudioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    // ---------------------------------------------------------------
    // Service lifecycle
    // ---------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: BUILD byte[48]/byte[52] media mapping v5")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Y-Connect",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
            startForeground(NOTIF_ID, buildNotification())
        }

        setupMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")
        when (intent?.action) {
            ACTION_START_SCAN -> scan()
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
        mediaSession?.release()
        mediaSession = null
    }

    // ---------------------------------------------------------------
    // Notification
    // ---------------------------------------------------------------

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Y-Connect")
            .setContentText("Connected to bike")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    // ---------------------------------------------------------------
    // Media session
    // ---------------------------------------------------------------

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "YccuBle").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                }

                override fun onPause() {
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                }

                override fun onSkipToNext() {
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                }

                override fun onSkipToPrevious() {
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                }
            })
            isActive = true
        }
    }

    // ---------------------------------------------------------------
    // Scanning
    // ---------------------------------------------------------------

    private fun scan() {
        Log.i(TAG, "scan() called")
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = manager.adapter ?: run {
            Log.e(TAG, "scan: no Bluetooth adapter")
            return
        }
        if (!adapter.isEnabled) {
            Log.e(TAG, "scan: Bluetooth disabled")
            return
        }

        // Match the bike by address first (most reliable).
        // Fall back to name filter if address isn't available.
        val filter = ScanFilter.Builder()
            .setDeviceAddress("CE:AC:84:48:51:82")
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        adapter.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
            ?: Log.e(TAG, "scan: bluetoothLeScanner null")
        Log.i(TAG, "scan: started")
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
    // GATT
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
            gatt.services.forEach { svc ->
                Log.d(TAG, "SERVICE ${svc.uuid}")
                svc.characteristics.forEach { ch ->
                    Log.d(TAG, "  CHAR ${ch.uuid} props=${ch.properties}")
                }
            }

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
            sendHandshake(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val v = characteristic.value ?: return
            handleFrame(v)
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt) {
        val char = notifyChar ?: return
        gatt.setCharacteristicNotification(char, true)
        val cccd = char.getDescriptor(CCCD_UUID) ?: run {
            Log.e(TAG, "CCCD not found")
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
            Log.w(TAG, "No write characteristic; handshake skipped")
            return
        }
        // 5a 01 7f 01 01 01 00 23 from snoop
        val payload = byteArrayOf(
            0x5A, 0x01, 0x7F, 0x01, 0x01, 0x01, 0x00, 0x23
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
        Log.i(TAG, "Handshake sent")
    }

    // ---------------------------------------------------------------
    // Frame handling — this is the piece that changed in v5
    // ---------------------------------------------------------------

    private fun handleFrame(v: ByteArray) {
        Log.d(TAG, "rx len=${v.size} ${v.toHex()}")

        if (v.size < FRAME_MIN_LEN) return
        if (v[0] != FRAME_HEADER_0) return
        if (v[1] != FRAME_HEADER_1) return
        if (v[2] != FRAME_HEADER_2) return
        if (v[3] != FRAME_HEADER_3) return
        if (v[4] != FRAME_HEADER_4) return

        // Confirmed offsets from snoop: byte[48] carries Vol+/Vol−,
        // byte[52] carries Next/Prev/PlayPause. Both idle at 0xff.
        val b48 = v[48].toInt() and 0xFF
        val b52 = v[52].toInt() and 0xFF

        val now = SystemClock.elapsedRealtime()
        // (Optional debounce — remove the "if" and lastDebounce if
        //  edge-detection alone is enough for your taste.)
        if (now - lastDebounce < DEBOUNCE_MS) {
            updateEdge(b48, b52)
            return
        }

        var dispatched = false

        // byte[48]: Volume up / Volume down
        if (b48 == 0x05 && lastBtnByte48 != 0x05) {
            adjustVolume(up = true)                              // Vol+
            dispatched = true
        } else if (b48 == 0x06 && lastBtnByte48 != 0x06) {
            adjustVolume(up = false)                             // Vol−
            dispatched = true
        }

        // byte[52]: Next / Previous / Play-Pause
        if (b52 == 0x01 && lastBtnByte52 != 0x01) {
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)        // Hold Vol+
            dispatched = true
        } else if (b52 == 0x02 && lastBtnByte52 != 0x02) {
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)    // Prev
            dispatched = true
        } else if (b52 == 0x03 && lastBtnByte52 != 0x03) {
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)  // Play/Pause
            dispatched = true
        }

        if (dispatched) lastDebounce = now

        // Edge detection: reset when back to idle
        updateEdge(b48, b52)
    }

    private var lastDebounce: Long = 0L

    private fun updateEdge(b48: Int, b52: Int) {
        lastBtnByte48 = if (b48 == IDLE) IDLE_BYTE else b48
        lastBtnByte52 = if (b52 == IDLE) IDLE_BYTE else b52
    }

    // ---------------------------------------------------------------
    // Action helpers
    // ---------------------------------------------------------------

    private fun adjustVolume(up: Boolean) {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
        Log.d(TAG, "adjustVolume up=$up")
    }

    private fun dispatchMediaKey(keyCode: Int) {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        Log.d(TAG, "dispatchMediaKey $keyCode")
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02x".format(it) }
}
