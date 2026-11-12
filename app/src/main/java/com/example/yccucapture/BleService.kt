package com.example.yccucapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class BleService : Service() {

    private val TAG = "YCCU_BLE"
    
    // YCCU UUIDs (from your logs, handle 0x000d is write, 0x000f is notify)
    // Note: Android requires UUIDs to subscribe. Since your logs show raw handles,
    // we assume the standard Yamaha YCCU service UUIDs. 
    // If your Phase 1 code used a specific UUID, keep it here.
    private val SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb") // Placeholder if unknown
    private val WRITE_CHAR_UUID = UUID.fromString("00002a39-0000-1000-8000-00805f9b34fb") // Placeholder
    
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    
    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundService()
        setupMediaSession()
    }

    private fun startForegroundService() {
        val notification = Notification.Builder(this, "YCCU_CHANNEL")
            .setContentTitle("YCCU Connected")
            .setContentText("Media bridge is active")
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

    // ==========================================
    // 1. MEDIA SESSION (Tells the bike a player is active)
    // ==========================================
    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "YCCUMediaSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val keyEvent = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                        dispatchMediaKey(keyEvent.keyCode)
                    }
                    return true
                }
            })
            // Set a dummy active state so the bike enables its buttons
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

    // ==========================================
    // 2. BLUETOOTH CALLBACKS
    // ==========================================
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server.")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server.")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered")
                // Find characteristics based on your Phase 1 code
                // (Update these UUIDs if your Phase 1 used specific ones)
                for (service in gatt.services) {
                    for (char in service.characteristics) {
                        if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                            writeCharacteristic = char
                        }
                        if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                            notifyCharacteristic = char
                            gatt.setCharacteristicNotification(char, true)
                            // Enable local notifications (CCCD descriptor)
                            val descriptor = char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                            descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        }
                    }
                }
                // Send initial handshake (from your logs Frame 482/490)
                sendHandshake()
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value
            val hexString = data.joinToString("") { "%02x".format(it) }
            Log.d(TAG, "RECV: $hexString")

            // ==========================================
            // 3. BUTTON PARSING (PLACEHOLDER)
            // ==========================================
            // Once you capture Snoop 3, replace this with the actual byte check.
            // Example: if (data[0] == 0x01.toByte()) dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            
            if (data.size < 50) {
                // This is likely a button or status packet
                Log.d(TAG, "Short packet received. Possible button press.")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            Log.d(TAG, "WRITE status: $status")
        }
    }

    // ==========================================
    // 4. SENDING DATA TO BIKE (Handshake & Metadata)
    // ==========================================
    private fun sendHandshake() {
        // From Frame 482 (Write Request, Handle 0x0010, Value 0100)
        // Note: Android uses UUIDs, not handles. Assuming this is the write characteristic.
        val handshakeData = byteArrayOf(0x01, 0x00)
        writeCharacteristic?.let {
            it.value = handshakeData
            bluetoothGatt?.writeCharacteristic(it)
        }
    }

    /**
     * Sends track metadata to the bike.
     * Based on Frame 523 from Snoop 1.txt:
     * Hex: 62 07 05 8a 08 07 ea 0b 0a 04 0f 38 8a 05 8b 08 00 00 00 55 55 55 55 30
     *      [UTF-16LE "Counting Crows"] 00 00 
     *      [UTF-16LE "Hard Candy"] 00 00
     *      [UTF-16LE "Big Yellow"] ...
     */
    fun sendMetadata(title: String, artist: String, album: String) {
        if (writeCharacteristic == null) return

        val buffer = ByteArrayOutputStream()
        
        // The fixed prefix observed in Frame 523
        val prefix = byteArrayOf(
            0x62, 0x07, 0x05, 0x8a, 0x08, 0x07, 0xea, 0x0b, 
            0x0a, 0x04, 0x0f, 0x38, 0x8a, 0x05, 0x8b, 0x08, 
            0x00, 0x00, 0x00, 0x55, 0x55, 0x55, 0x55, 0x30
        )
        buffer.write(prefix)
        
        // Append Artist (UTF-16LE)
        buffer.write(artist.toByteArray(Charsets.UTF_16LE))
        buffer.write(byteArrayOf(0x00, 0x00))
        
        // Append Title (UTF-16LE)
        buffer.write(title.toByteArray(Charsets.UTF_16LE))
        buffer.write(byteArrayOf(0x00, 0x00))
        
        // Append Album (UTF-16LE)
        buffer.write(album.toByteArray(Charsets.UTF_16LE))
        buffer.write(byteArrayOf(0x00, 0x00))
        
        // Append a fixed suffix (looks like a checksum or terminator)
        buffer.write(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x05, 0x9c, 0x1e, 0x00, 0x42, 0x00, 0x69, 0x00, 0x67, 0x00, 0x20, 0x00, 0x59, 0x00, 0x65, 0x00, 0x6c, 0x00, 0x6c, 0x00, 0x6f, 0x00, 0x77))

        val finalData = buffer.toByteArray()
        writeCharacteristic?.let {
            it.value = finalData
            bluetoothGatt?.writeCharacteristic(it)
            Log.d(TAG, "Sent Metadata: $artist - $title")
        }
    }

    // ==========================================
    // 5. SERVICE LIFECYCLE
    // ==========================================
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // You would call connectToDevice(macAddress) here based on your Phase 1 code
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mediaSession.isActive = false
        mediaSession.release()
        bluetoothGatt?.close()
        super.onDestroy()
    }
}
