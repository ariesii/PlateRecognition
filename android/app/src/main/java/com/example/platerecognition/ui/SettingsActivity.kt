package com.example.platerecognition.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.example.platerecognition.R
import com.example.platerecognition.settings.AppSettings
import com.example.platerecognition.settings.PlateCandidate
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {
    private lateinit var settings: AppSettings
    private lateinit var group: RadioGroup
    private lateinit var status: TextView
    private val choices = mutableMapOf<Int, PlateCandidate>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = AppSettings(this)
        group = findViewById(R.id.candidateGroup)
        status = findViewById(R.id.calibrationStatus)
        findViewById<Button>(R.id.backLive).setOnClickListener { finish() }
        findViewById<Button>(R.id.showEvents).setOnClickListener {
            startActivity(Intent(this, EventsActivity::class.java))
        }
        findViewById<SwitchCompat>(R.id.recognitionSoundSwitch).apply {
            isChecked = settings.recognitionSoundEnabled
            setOnCheckedChangeListener { _, checked -> settings.recognitionSoundEnabled = checked }
        }
        findViewById<SwitchCompat>(R.id.adasSwitch).apply {
            isChecked = settings.adasEnabled
            setOnCheckedChangeListener { _, checked -> settings.adasEnabled = checked }
        }
        findViewById<SwitchCompat>(R.id.soundSwitch).apply {
            isChecked = settings.soundEnabled
            setOnCheckedChangeListener { _, checked -> settings.soundEnabled = checked }
        }
        findViewById<SwitchCompat>(R.id.vibrationSwitch).apply {
            isChecked = settings.vibrationEnabled
            setOnCheckedChangeListener { _, checked -> settings.vibrationEnabled = checked }
        }
        val close = findViewById<SeekBar>(R.id.closeSeek)
        val closeLabel = findViewById<TextView>(R.id.closeValue)
        close.progress = (settings.closeMetres - 4f).roundToInt()
        closeLabel.text = "跟車過近提醒：${settings.closeMetres.roundToInt()} 公尺（估計值）"
        close.setOnSeekBarChangeListener(listener { progress ->
            settings.closeMetres = 4f + progress
            closeLabel.text = "跟車過近提醒：${settings.closeMetres.roundToInt()} 公尺（估計值）"
        })
        val centre = findViewById<SeekBar>(R.id.centreSeek)
        val centreLabel = findViewById<TextView>(R.id.centreValue)
        centre.progress = ((settings.laneCenterRatio - 0.35f) * 100f).roundToInt()
        centreLabel.text = "車輛中心在畫面 ${(settings.laneCenterRatio * 100).roundToInt()}%"
        centre.setOnSeekBarChangeListener(listener { progress ->
            settings.laneCenterRatio = 0.35f + progress / 100f
            centreLabel.text = "車輛中心在畫面 ${(settings.laneCenterRatio * 100).roundToInt()}%"
        })
        findViewById<Button>(R.id.calibrateButton).setOnClickListener { requestCalibration() }
        findViewById<Button>(R.id.resetCalibration).setOnClickListener {
            AlertDialog.Builder(this).setTitle("清除車距校正？")
                .setMessage("清除後不再顯示估計公尺，也不發出實驗性前車警示。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除") { _, _ -> settings.clearCalibration(); updateCalibrationStatus() }
                .show()
        }
        updateCalibrationStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::group.isInitialized) refreshCandidates()
    }

    private fun refreshCandidates() {
        group.removeAllViews()
        choices.clear()
        val recent = settings.recentCandidates()
        if (recent.isEmpty()) {
            status.text = "目前沒有五分鐘內的清晰車牌。請停車後返回即時畫面對準前車車牌。"
        } else recent.forEach { candidate ->
            val id = android.view.View.generateViewId()
            choices[id] = candidate
            group.addView(RadioButton(this).apply {
                this.id = id
                text = "${candidate.text} · 信心 ${(candidate.confidence * 100).roundToInt()}%"
                setTextColor(0xFFF4F2EB.toInt())
                minHeight = (48 * resources.displayMetrics.density).toInt()
            })
        }
        if (recent.size == 1) group.check(choices.keys.first())
        updateCalibrationStatus()
    }

    private fun requestCalibration() {
        val chosen = choices[group.checkedRadioButtonId]
        val metres = findViewById<EditText>(R.id.knownMetres).text.toString().toFloatOrNull()
        if (chosen == null || metres == null || metres !in 2f..80f) {
            status.text = "請選擇五分鐘內的車牌，並輸入實測的 2–80 公尺距離。"
            return
        }
        AlertDialog.Builder(this).setTitle("確認已停車並量測距離")
            .setMessage("校正值只適用目前固定的相機位置與相近大小的車牌。更換支架、旋轉手機或不同尺寸車牌會失準。")
            .setNegativeButton("返回", null)
            .setPositiveButton("儲存校正") { _, _ ->
                val result = runCatching { settings.calibrate(chosen, metres) }
                status.text = result.exceptionOrNull()?.message ?: "已用 ${chosen.text} / $metres 公尺校正。"
            }.show()
    }

    private fun updateCalibrationStatus() {
        val calibration = settings.calibrationFor(1280, 720)
        if (calibration != null) {
            status.text = "已校正：${calibration.referencePlate} · ${calibration.referenceMetres} 公尺。結果仍屬估計值。"
        } else if (choices.isNotEmpty()) {
            status.text = "尚未校正，暫不顯示公尺。選一張車牌並輸入實際距離。"
        }
    }

    private fun listener(changed: (Int) -> Unit): SeekBar.OnSeekBarChangeListener =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) changed(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
}
