package com.example.exosys

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.d("EXONED_BOOT", "Secuencia de arranque del celular detectada. Levantando motor BLE.")

            val serviceIntent = Intent(context, BleCoreService::class.java)

            // Android 8.0+ exige usar startForegroundService si la app no está en pantalla
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}