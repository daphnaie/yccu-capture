package com.example.yccucapture

import android.app.*
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import java.io.ByteArrayOutputStream
import java.util.UUID

class BleService : Service() {

    private val TAG = "YCCU_BLE"

    private val SERVICE_YAMAHA = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val CHAR_WRITE_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    private val CHAR_NOTIFY_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    
    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundService()
        setupMediaSession()
    }

    private fun startForegroundService() {
        val notification = Notification.Builder(this, "YCCU_CHANNEL")
            .setContentTitle("YCCU Active")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("YCCU_CHANNEL", "YCCU Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "YCCUMediaSession").apply {
            setPlaybackState(PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .build())
            isActive = true
        }
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        }
        sendBroadcast(intent)
        Log.d(TAG, "Dispatched Media Key: $keyCode")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                for (service in gatt.services) {
                    for (char in service.characteristics) {
                        if (char.uuid == CHAR_WRITE_UUID) writeCharacteristic = char
                        if (char.uuid == CHAR_NOTIFY_UUID) {
                            gatt.setCharacteristicNotification(char, true)
                            val descriptor = char.getDescriptor(CCCD)
                            descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        }
                    }
                }
                // Send initial handshake (from Frame 482)
                sendHandshake()
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value
            val hexString = data.joinToString("") { "%02x".format(it) }
            Log.d(TAG, "RECV: $hexString")

            // ==========================================
            // BUTTON PARSER (Placeholder - update with Snoop 3)
            // ==========================================
            if (data.size < 50) {
                // This is likely a button press or status packet.
                // We will update this mapping once you capture Snoop 3.
                when (data[0]) {
                    0x01.toByte() -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    0x02.toByte() -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                    0x03.toByte() -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                }
            }
        }
    }

    private fun sendHandshake() {
        // Frame 482: Write Request, Value 0100 to Handle 0x0010
        val handshakeData = byteArrayOf(0x01, 0x00)
        writeCharacteristic?.let {
            it.value = handshakeData
            bluetoothGatt?.writeCharacteristic(it)
        }
        // Frame 490: Write Command, Value "aa 01 7f 00 35 ..." (the token)
        val tokenData = hexStringToByteArray("aa017f0035303030303030303030303030303030303030303064383136353639303532383534663339383231613636326337366365353934630002f7")
        writeCharacteristic?.let {
            it.value = tokenData
            bluetoothGatt?.writeCharacteristic(it)
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    // ==========================================
    // METADATA PARSER (Exact match from Snoop 1)
    // ==========================================
    fun sendMetadata(title: String, artist: String, album: String) {
        if (writeCharacteristic == null) return

        val buffer = ByteArrayOutputStream()
        // Prefix from Frame 523
        val prefix = byteArrayOf(
            0x62, 0x07, 0x05, 0x8a, 0x08, 0x07, 0xea, 0x0b, 
            0x0a, 0x04, 0x0f, 0x38, 0x8a, 0x05, 0x8b, 0x08, 
            0x00, 0x00, 0x00, 0x55, 0x55, 0x55, 0x55, 0x30
        )
        buffer.write(prefix)
        
        // Artist
        buffer.write(artist.toByteArray(Charsets.UTF_16LE))
        buffer.write(byteArrayOf(0x00, 0x00))
        // Title
        buffer.write(title.toByteArray(Charsets.UTF_16LE))
        buffer.write(byteArrayOf(0x00, 0x00))
        // Album
        buffer.write(album.toByteArray(Charsets.UTF_16LE))
        buffer.write(byteArrayOf(0x00, 0x00))
        // Suffix
        buffer.write(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x05, 0x9c, 0x1e, 0x00, 0x42, 0x00, 0x69, 0x00, 0x67, 0x00, 0x20, 0x00, 0x59, 0x00, 0x65, 0x00, 0x6c, 0x00, 0x6c, 0x00, 0x6f, 0x00, 0x77))

        writeCharacteristic?.let {
            it.value = buffer.toByteArray()
            bluetoothGatt?.writeCharacteristic(it)
            Log.d(TAG, "Sent Metadata: $artist - $title")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}
