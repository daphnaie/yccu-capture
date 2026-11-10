package com.example.yccucapture

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NotificationLogger {

    private val sb = StringBuilder()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(handle: Int, bytes: ByteArray) {
        val ts = fmt.format(Date())
        val hex = bytes.joinToString(" ") { "%02x".format(it) }
        sb.append("[$ts] h=0x%04x len=%d %s\n".format(handle, bytes.size, hex))
    }

    @Synchronized
    fun info(msg: String) {
        sb.append("[${fmt.format(Date())}] $msg\n")
    }

    @Synchronized
    fun dump(): String = sb.toString()

    @Synchronized
    fun clear() { sb.setLength(0) }

    @Synchronized
    fun saveToFile(ctx: Context): File {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val f = File(dir, "yccu_capture_${System.currentTimeMillis()}.txt")
        f.writeText(sb.toString())
        return f
    }
}
