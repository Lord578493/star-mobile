package com.tgbypass.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusSub: TextView
    private lateinit var statusDot: android.view.View
    private lateinit var radioGroup: RadioGroup
    private lateinit var radioBasic: RadioButton
    private lateinit var radioEnhanced: RadioButton
    private lateinit var radioMax: RadioButton
    private lateinit var switchAutostart: Switch
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView

    private var isRunning = false
    private val logLines = mutableListOf<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (it.resultCode == RESULT_OK) startBypass() }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BypassVpnService.ACTION_STATUS_UPDATE -> {
                    val status = intent.getStringExtra(BypassVpnService.EXTRA_STATUS) ?: return
                    val active = intent.getBooleanExtra(BypassVpnService.EXTRA_IS_ACTIVE, false)
                    isRunning = active
                    tvStatus.text = status
                    if (active) setActiveUI() else setInactiveUI()
                }
                BypassVpnService.ACTION_LOG -> {
                    val msg = intent.getStringExtra(BypassVpnService.EXTRA_LOG) ?: return
                    addLog(msg)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle      = findViewById(R.id.btnToggle)
        tvStatus       = findViewById(R.id.tvStatus)
        tvStatusSub    = findViewById(R.id.tvStatusSub)
        statusDot      = findViewById(R.id.statusDot)
        radioGroup     = findViewById(R.id.radioGroup)
        radioBasic     = findViewById(R.id.radioBasic)
        radioEnhanced  = findViewById(R.id.radioEnhanced)
        radioMax       = findViewById(R.id.radioMax)
        switchAutostart = findViewById(R.id.switchAutostart)
        tvLog          = findViewById(R.id.tvLog)
        scrollLog      = findViewById(R.id.scrollLog)

        loadPrefs()
        addLog("Приложение запущено")
        addLog("Telegram IP диапазонов: 15")

        btnToggle.setOnClickListener {
            if (isRunning) stopBypass() else requestVpn()
        }

        switchAutostart.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences("tgbypass", MODE_PRIVATE).edit()
                .putBoolean("autostart", checked).apply()
        }

        if (BypassVpnService.isRunning) setActiveUI() else setInactiveUI()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(BypassVpnService.ACTION_STATUS_UPDATE)
            addAction(BypassVpnService.ACTION_LOG)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        else
            registerReceiver(statusReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        savePrefs()
    }

    private fun requestVpn() {
        val i = VpnService.prepare(this)
        if (i != null) vpnLauncher.launch(i) else startBypass()
    }

    private fun startBypass() {
        val mode = getSelectedMode()
        addLog("Запуск режима: ${mode.label}")
        ContextCompat.startForegroundService(this,
            Intent(this, BypassVpnService::class.java).apply {
                action = BypassVpnService.ACTION_START
                putExtra(BypassVpnService.EXTRA_MODE, mode.name)
            })
    }

    private fun stopBypass() {
        addLog("Остановка VPN...")
        startService(Intent(this, BypassVpnService::class.java).apply {
            action = BypassVpnService.ACTION_STOP
        })
    }

    // Определяем режим по тегу RadioButton — надёжнее чем по ID
    private fun getSelectedMode(): BypassMode {
        val checked = radioGroup.checkedRadioButtonId
        val btn = radioGroup.findViewById<RadioButton>(checked)
        return when (btn?.tag?.toString()) {
            "BASIC"   -> BypassMode.BASIC
            "MAXIMUM" -> BypassMode.MAXIMUM
            else      -> BypassMode.ENHANCED
        }
    }

    private fun setActiveUI() {
        isRunning = true
        btnToggle.text = "ОСТАНОВИТЬ"
        btnToggle.backgroundTintList = getColorStateList(R.color.red)
        statusDot.backgroundTintList = getColorStateList(R.color.green)
        tvStatus.text = "Активен · ${BypassVpnService.currentMode.label}"
        tvStatusSub.text = "Обход DPI включён ✓"
    }

    private fun setInactiveUI() {
        isRunning = false
        btnToggle.text = "ЗАПУСТИТЬ"
        btnToggle.backgroundTintList = getColorStateList(R.color.accent)
        statusDot.backgroundTintList = getColorStateList(R.color.gray)
        tvStatus.text = "Не активен"
        tvStatusSub.text = "Нажмите ЗАПУСТИТЬ"
    }

    private fun addLog(msg: String) {
        val time = timeFormat.format(Date())
        logLines.add("[$time] $msg")
        if (logLines.size > 100) logLines.removeAt(0)
        tvLog.text = logLines.joinToString("\n")
        scrollLog.post { scrollLog.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun loadPrefs() {
        val p = getSharedPreferences("tgbypass", MODE_PRIVATE)
        switchAutostart.isChecked = p.getBoolean("autostart", false)
        when (p.getString("mode", "ENHANCED")) {
            "BASIC"   -> radioBasic.isChecked = true
            "MAXIMUM" -> radioMax.isChecked = true
            else      -> radioEnhanced.isChecked = true
        }
    }

    private fun savePrefs() {
        getSharedPreferences("tgbypass", MODE_PRIVATE).edit()
            .putBoolean("autostart", switchAutostart.isChecked)
            .putString("mode", getSelectedMode().name)
            .apply()
    }
}
