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
import android.os.IBinder
import java.util.UUID

class BleService : Service() {

    companion object {
        private const val CH_ID = "yccu"
        private const val CH_NAME = "YCCU Capture"
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var gatt: BluetoothGatt? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        scan()
    }

    override fun onDestroy() {
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
        if (!adapter.isEnabled) {
            NotificationLogger.info("BT off")
            return
        }
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

        override fun onScanFailed(errorCode: Int) {
            NotificationLogger.info("Scan failed: $errorCode")
        }
    }

    private fun connect(device: BluetoothDevice) {
        NotificationLogger.info("Connecting to ${device.address}")
        gatt = if (Build.VERSION.SDK_INT >= 23)
            device.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        else
            device.connectGatt(this, false, gattCb)
    }

    private val gattCb = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            NotificationLogger.info("State change: status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                NotificationLogger.info("Disconnected")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            NotificationLogger.info("Services discovered, status=$status")
            for (svc in g.services) {
                NotificationLogger.info("Service ${svc.uuid}")
                for (ch in svc.characteristics) {
                    NotificationLogger.info(
                        "  Char ${ch.uuid} handle=${ch.instanceId} props=0x%02x".format(ch.properties)
                    )
                    if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                        g.setCharacteristicNotification(ch, true)
                        val d = ch.getDescriptor(CCCD) ?: continue
                        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(d)
                        NotificationLogger.info("    Subscribed to ${ch.uuid}")
                    }
                    if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                        // Fire the 01 00 handshake at every write-no-response characteristic.
                        // If this trips the wrong one, we can refine later.
                        ch.value = byteArrayOf(0x01, 0x00)
                        g.writeCharacteristic(ch)
                        NotificationLogger.info("    Wrote 0100 to ${ch.uuid}")
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
        }
    }
}
