package com.example.yccucapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.UUID

class BleService : Service() {

    companion object {
        private const val CH_ID = "yccu"
        private const val CH_NAME = "YCCU Capture"
        
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
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
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(NotificationChannel(CH_ID, CH_NAME, NotificationManager.IMPORTANCE_LOW))
        }
        return Notification.Builder(this, CH_ID)
            .setContentTitle("YCCU Capture")
            .setContentText("Scanning / connected")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
    }

    private fun scan() {
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = mgr.adapter ?: return
        scanner = adapter.bluetoothLeScanner
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
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
            
            // Find ONLY the Yamaha service
            val yamahaService = g.getService(SERVICE_UUID)
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

            // 1. Send the 01 00 wake-up (Time Sync / Session Init)
            var wakeUpSent = false
            for (ch in yamahaService.characteristics) {
                if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ||
                    ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                    
                    if (ch.uuid != CHAR_WRITE_UUID) {
                        ch.value = byteArrayOf(0x01, 0x00)
                        g.writeCharacteristic(ch)
                        NotificationLogger.info("Sent 01 00 wake-up to ${ch.uuid}")
                        wakeUpSent = true
                    }
                }
            }
            
            if (!wakeUpSent) {
                 NotificationLogger.info("Warning: No wake-up characteristic found!")
            }

            // 2. Subscribe to Notifications
            g.setCharacteristicNotification(nc, true)
            val cccd = nc.getDescriptor(CCCD)
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
                NotificationLogger.info("Subscribing to notifications...")
            }
            
            // 3. Handshake fallback timer (in case onDescriptorWrite doesn't fire)
            handler.postDelayed({ sendHandshake(g) }, 1500)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            NotificationLogger.info("Subscribed: ${status == BluetoothGatt.GATT_SUCCESS}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Wait 1 second before handshake to let the bike process the wake-up
                handler.postDelayed({ sendHandshake(g) }, 1000) 
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (ch.uuid == CHAR_WRITE_UUID) {
                if (!isHandshakeSent) {
                    NotificationLogger.info("Handshake sent: ${status == BluetoothGatt.GATT_SUCCESS}")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        isHandshakeSent = true
                        // Wait 500ms after handshake, then start keepalive
                        handler.postDelayed({ startKeepalive(g) }, 500) 
                    }
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val v = ch.value ?: return
            NotificationLogger.log(ch.instanceId, v)
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
                handler.postDelayed(this, 300) // 300ms loop
            }
        }
        handler.post(runnable)
        NotificationLogger.info("Keepalive running")
    }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
