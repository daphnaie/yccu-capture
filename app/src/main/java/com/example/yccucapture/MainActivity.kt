package com.example.yccucapture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startCaptureService()
        else tvStatus.text = "Status: permissions denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            ensurePermsAndStart()
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            NotificationLogger.clear()
            tvLog.text = ""
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val f = NotificationLogger.saveToFile(this)
            tvStatus.text = "Status: saved to ${f.absolutePath}"
        }

        // Poll the shared logger buffer every 500 ms
        val handler = android.os.Handler(mainLooper)
        val updater = object : Runnable {
            override fun run() {
                tvLog.text = NotificationLogger.dump()
                handler.postDelayed(this, 500)
            }
        }
        handler.post(updater)
    }

    private fun ensurePermsAndStart() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startCaptureService()
        else permLauncher.launch(missing.toTypedArray())
    }

    private fun startCaptureService() {
        tvStatus.text = "Status: starting service..."
        val i = Intent(this, BleService::class.java)
        ContextCompat.startForegroundService(this, i)
    }
}
