package com.example.yccucapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import java.util.UUID

class BleService : Service() {

    companion object {
        private const val CH_ID = "yccu"
        private const val CH_NAME = "YCCU Capture"
        
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val SERVICE_YAMAHA = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val CHAR_WRITE_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val CHAR_NOTIFY_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

        private const val HANDSHAKE_HEX = "aa017f0035303030303030303030303030303030303030303064383136353639303532383534663339383231613636326337366365353934630000f9"
        private const val KEEPALIVE_HEX = "60050592080000000000000000000593080000000000000000059408fdff00fe5555555505953d00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000059808555555555555555598b1"
    }

    private var gatt: BluetoothGatt? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())
    
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var isHandshakeSent = false
    private var keepaliveRunning = false

    private var mediaSession: MediaSession? = null

    private var lastBtnByte48: Int = -1
    private var lastBtnByte52: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, buildNotification())
        }
        NotificationLogger.info("BUILD: byte[48]/byte[52] media mapping v4")
        setupMediaSession()
        scan()
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    private fun stopAll() {
        keepaliveRunning = false
        isHandshakeSent = false
        handler.removeCallbacksAndMessages(null)
        try { scanner?.stopScan(scanCb) } catch (_: Exception) {}
        gatt?.close()
        gatt = null
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(CH_ID, CH_NAME, NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CH_ID)
            .setContentTitle("YCCU Capture")
            .setContentText("Scanning / connected")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
    }

    private fun setupMediaSession() {
        if (mediaSession != null) return
        mediaSession = MediaSession(this, "YCCUMediaSession").apply {
            setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
                    .setActions(
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    )
                    .build()
            )
            isActive = true
        }
        NotificationLogger.info("MediaSession active")
    }

    private fun getActiveController(): MediaController? {
        return try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val listener = ComponentName(this, YccuNotificationListener::class.java)
            val sessions = msm.getActiveSessions(listener)
            sessions.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING &&
                it.packageName != packageName
            } ?: sessions.firstOrNull { it.packageName != packageName }
        } catch (e: SecurityException) {
            NotificationLogger.info("MediaSession access denied (grant notification listener)")
            null
        } catch (e: Exception) {
            NotificationLogger.info("getActiveSessions failed: ${e.message}")
            null
        }
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val ctrl = getActiveController()
        if (ctrl != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    val state = ctrl.playbackState?.state
                    if (state == PlaybackState.STATE_PLAYING) {
                        ctrl.transportControls.pause()
                    } else {
                        ctrl.transportControls.play()
                    }
                }
                KeyEvent.KEYCODE_MEDIA_NEXT     -> ctrl.transportControls.skipToNext()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> ctrl.transportControls.skipToPrevious()
            }
            NotificationLogger.info("Dispatched via controller (${ctrl.packageName}): $keyCode")
        } else {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            NotificationLogger.info("Dispatched via AudioManager (fallback): $keyCode")
        }
    }

    private fun adjustVolume(up: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
        NotificationLogger.info("Volume ${if (up) "up" else "down"}")
    }

    private fun scan() {
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = mgr.adapter ?: return
        scanner = adapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        NotificationLogger.info("Scanning…")
        scanner?.startScan(null, settings, scanCb)
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: return
            if (!name.uppercase().contains("YCCU")) return
            NotificationLogger.info("Found $name (${result.device.address})")
            scanner?.stopScan(this)
            connect(result.device)
        }
    }

    private fun connect(device: BluetoothDevice) {
        NotificationLogger.info("Connecting to ${device.address}")
        gatt = device.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCb = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            NotificationLogger.info("State change: status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                NotificationLogger.info("Disconnected")
                stopAll()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            NotificationLogger.info("Services discovered, status=$status")
            val yamahaService = g.getService(SERVICE_YAMAHA)
            if (yamahaService == null) {
                NotificationLogger.info("ERROR: Yamaha service not found")
                return
            }
            val wc = yamahaService.getCharacteristic(CHAR_WRITE_UUID)
            val nc = yamahaService.getCharacteristic(CHAR_NOTIFY_UUID)
            if (wc == null || nc == null) {
                NotificationLogger.info("ERROR: Yamaha chars not found")
                return
            }
            writeChar = wc
            notifyChar = nc

            val dummyChar = BluetoothGattCharacteristic(
                UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb"),
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            try {
                val field = BluetoothGattCharacteristic::class.java.getDeclaredField("mInstanceId")
                field.isAccessible = true
                field.setInt(dummyChar, 0x0010)
                dummyChar.value = byteArrayOf(0x01, 0x00)
                dummyChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                val queued = g.writeCharacteristic(dummyChar)
                NotificationLogger.info("Wake-up to 0x0010 queued: $queued")
            } catch (e: Exception) {
                NotificationLogger.info("Reflection failed: ${e.message}")
            }

            handler.postDelayed({
                NotificationLogger.info("Requesting MTU 247 now...")
                g.requestMtu(247)
            }, 300)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            NotificationLogger.info("MTU changed to $mtu, status: $status")
            val nc = notifyChar ?: return
            g.setCharacteristicNotification(nc, true)
            val cccd = nc.getDescriptor(CCCD)
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
                NotificationLogger.info("Subscribing to notifications...")
            }
            handler.postDelayed({ sendHandshake(g) }, 300)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (ch.uuid == CHAR_WRITE_UUID) {
                if (!isHandshakeSent) {
                    NotificationLogger.info("Handshake sent: ${status == BluetoothGatt.GATT_SUCCESS}")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        isHandshakeSent = true
                        handler.postDelayed({ startKeepalive(g) }, 300)
                    }
                }
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic
        ) {
            val v = ch.value ?: return
            NotificationLogger.log(ch.instanceId, v)
        
            if (v.size != 59) return
            if (v[0] != 0x6b.toByte() || v[1] != 0x05.toByte() ||
                v[2] != 0x00.toByte() || v[3] != 0x01.toByte() ||
                v[4] != 0x21.toByte()) return
        
            val b48 = v[48].toInt() and 0xFF
            val b52 = v[52].toInt() and 0xFF
        
            // Play: short press Play button → b48 = 0x05
            if (b48 == 0x05 && lastBtnByte48 != 0x05) {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                lastBtnByte48 = 0x05
            }
        
            // Vol+ (short OR long) → b48 = 0x06
            // Short press = Volume Up, Long press = Next (bike sends same byte; we can't distinguish)
            if (b48 == 0x06 && lastBtnByte48 != 0x06) {
                adjustVolume(up = true)
                lastBtnByte48 = 0x06
            }
        
            // Vol− (short OR long) → b52 = 0x01 or 0x02 depending on bike state
            // We'll map 0x01 → Volume Down, 0x02 → Next, 0x03 → Previous
            if (b52 == 0x01 && lastBtnByte52 != 0x01) {
                adjustVolume(up = false)
                lastBtnByte52 = 0x01
            }
            if (b52 == 0x02 && lastBtnByte52 != 0x02) {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                lastBtnByte52 = 0x02
            }
            if (b52 == 0x03 && lastBtnByte52 != 0x03) {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                lastBtnByte52 = 0x03
            }
        
            if (b48 == 0xff) lastBtnByte48 = -1
            if (b52 == 0xff) lastBtnByte52 = -1
        }
    }

    private fun sendHandshake(g: BluetoothGatt) {
        if (isHandshakeSent) return
        val ch = writeChar ?: return
        ch.value = hexToBytes(HANDSHAKE_HEX)
        val queued = g.writeCharacteristic(ch)
        NotificationLogger.info("Queued handshake: $queued")
    }

    private fun startKeepalive(g: BluetoothGatt) {
        if (keepaliveRunning) return
        keepaliveRunning = true
        val runnable = object : Runnable {
            override fun run() {
                if (!keepaliveRunning) return
                val ch = writeChar ?: return
                ch.value = hexToBytes(KEEPALIVE_HEX)
                g.writeCharacteristic(ch)
                handler.postDelayed(this, 300)
            }
        }
        handler.post(runnable)
        NotificationLogger.info("Keepalive running")
    }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
