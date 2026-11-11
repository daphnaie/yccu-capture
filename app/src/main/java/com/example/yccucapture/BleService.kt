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
    private var keepaliveRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        scan()
    }

    override fun onDestroy() {
        keepaliveRunning = false
        handler.removeCallbacksAndMessages(null)
        try { scanner?.stopScan(scanCb) } catch (_: Exception) {}
        gatt?.close()
        super.onDestroy()
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
                keepaliveRunning = false
                handler.removeCallbacksAndMessages(null)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            NotificationLogger.info("Services discovered, status=$status")
            
            // Wait 500ms before writing to let the bike settle
            handler.postDelayed({
                val writeChar = g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_WRITE_UUID)
                val notifyChar = g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_NOTIFY_UUID)

                if (writeChar == null || notifyChar == null) {
                    NotificationLogger.info("ERROR: Yamaha characteristics not found")
                    return@postDelayed
                }

                // 1. Subscribe
                g.setCharacteristicNotification(notifyChar, true)
                val cccd = notifyChar.getDescriptor(CCCD)
                if (cccd != null) {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    val ok = g.writeDescriptor(cccd)
                    NotificationLogger.info("Subscribed: $ok")
                }

                // 2. Handshake
                writeChar.value = hexToBytes(HANDSHAKE_HEX)
                val hOk = g.writeCharacteristic(writeChar)
                NotificationLogger.info("Handshake sent: $hOk")

                // 3. Keepalive
                startKeepalive(g, writeChar)
            }, 500) // 500ms delay
        }

        override fun onCharacteristicWrite(g: BluetoothGatt?, ch: BluetoothGattCharacteristic?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                NotificationLogger.info("Write FAILED on ${ch?.uuid}: status=$status")
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val v = ch.value ?: return
            NotificationLogger.log(ch.instanceId, v)
        }
    }

    private fun startKeepalive(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        if (keepaliveRunning) return
        keepaliveRunning = true
        
        val runnable = object : Runnable {
            override fun run() {
                if (!keepaliveRunning) return
                ch.value = hexToBytes(KEEPALIVE_HEX)
                g.writeCharacteristic(ch)
                handler.postDelayed(this, 300) // 300ms
            }
        }
        handler.post(runnable)
        NotificationLogger.info("Keepalive running")
    }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
