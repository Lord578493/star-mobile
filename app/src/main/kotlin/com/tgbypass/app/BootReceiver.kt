package com.tgbypass.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("tgbypass", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("autostart", false)) return
        val mode = prefs.getString("mode", BypassMode.ENHANCED.name) ?: BypassMode.ENHANCED.name
        ContextCompat.startForegroundService(context,
            Intent(context, BypassVpnService::class.java).apply {
                action = BypassVpnService.ACTION_START
                putExtra(BypassVpnService.EXTRA_MODE, mode)
            })
    }
}
