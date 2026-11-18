package com.example.platerecognition

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.platerecognition.camera.CameraFrame
import com.example.platerecognition.camera.PlateOverlay
import com.example.platerecognition.recognition.PlateEngine
import com.example.platerecognition.recognition.StabilityGate
import com.example.platerecognition.settings.AppSettings
import com.example.platerecognition.storage.DailyPlateStore
import com.example.platerecognition.ui.EventsActivity
import com.example.platerecognition.ui.SettingsActivity
import com.example.platerecognition.vision.DistanceEstimator
import com.example.platerecognition.vision.DriveScene
import com.example.platerecognition.vision.DriveWarning
import com.example.platerecognition.vision.LaneAnalyzer
import com.example.platerecognition.vision.WarningEngine
import com.example.platerecognition.vision.WarningKind
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private lateinit var overlay: PlateOverlay
    private lateinit var status: TextView
    private lateinit var featureStatus: TextView
    private lateinit var distanceStatus: TextView
    private lateinit var warningBanner: TextView
    private lateinit var saveSwitch: SwitchCompat
    private lateinit var pauseButton: Button
    private lateinit var store: DailyPlateStore
    private lateinit var settings: AppSettings
    private val gate = StabilityGate()
    private val laneAnalyzer = LaneAnalyzer()
    private val distanceEstimator = DistanceEstimator()
    private val warningEngine = WarningEngine()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var engine: PlateEngine? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var today = ""
    private var savedToday = 0
    private var lastSavedText = ""
    private var lastWarning: DriveWarning? = null
    private var warningUntil = 0L
    private var tone: ToneGenerator? = null
    @Volatile private var paused = false

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.setText(R.string.camera_permission)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        status = findViewById(R.id.status)
        featureStatus = findViewById(R.id.featureStatus)
        distanceStatus = findViewById(R.id.distanceStatus)
        warningBanner = findViewById(R.id.warningBanner)
        saveSwitch = findViewById(R.id.autoSaveSwitch)
        pauseButton = findViewById(R.id.pauseButton)
        preview.scaleType = PreviewView.ScaleType.FILL_CENTER
        store = DailyPlateStore(this)
        settings = AppSettings(this)
        saveSwitch.isChecked = settings.autoSave
        saveSwitch.setOnCheckedChangeListener { _, checked ->
            settings.autoSave = checked
            if (!checked) status.text = "自動存照已關閉 · 車牌辨識仍持續"
        }
        pauseButton.setOnClickListener {
            paused = !paused
            pauseButton.text = if (paused) "繼續分析" else "暫停分析"
            if (paused) {
                overlay.show(null)
                warningBanner.visibility = View.GONE
                status.text = "影像分析已暫停 · 相機預覽仍在運作"
            }
        }
        findViewById<Button>(R.id.eventsButton).setOnClickListener {
            startActivity(Intent(this, EventsActivity::class.java))
        }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        worker.execute {
            try {
                store.recoverInterrupted()
                today = DailyPlateStore.localDay(System.currentTimeMillis())
                savedToday = store.countForDay(today)
                engine = PlateEngine(this)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    status.text = "模型就緒 · 今日已保存 $savedToday 個車牌"
                    featureStatus.text = "車牌辨識已啟動 · 車道待偵測"
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        startCamera()
                    } else requestCamera.launch(Manifest.permission.CAMERA)
                }
            } catch (e: Exception) {
                runOnUiThread { if (!isDestroyed) status.text = "模型初始化失敗：${e.message}" }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::settings.isInitialized) {
            saveSwitch.isChecked = settings.autoSave
            if (::store.isInitialized) worker.execute {
                val day = DailyPlateStore.localDay(System.currentTimeMillis())
                today = day
                savedToday = store.countForDay(day)
            }
        }
    }

    private fun startCamera() {
        if (isFinishing || isDestroyed || engine == null) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isDestroyed) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                val cameraPreview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_16_9)
                    .build().also { it.surfaceProvider = preview.surfaceProvider }
                analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().also { it.setAnalyzer(worker, ImageAnalysis.Analyzer { frame -> analyze(frame) }) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, analysis)
            } catch (e: Exception) {
                status.text = "相機啟動失敗：${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        if (paused) {
            image.close()
            gate.observe(emptyList(), DailyPlateStore.localDay(System.currentTimeMillis()), SystemClock.elapsedRealtime())
            warningEngine.resetTracks()
            distanceEstimator.reset()
            return
        }
        val frame = try { CameraFrame.copyRotated(image) }
        catch (e: Exception) {
            runOnUiThread { if (!isDestroyed) status.text = "相機影格錯誤：${e.message}" }
            return
        } finally { image.close() }
        try {
            val started = SystemClock.elapsedRealtime()
            val now = System.currentTimeMillis()
            val elapsedNow = SystemClock.elapsedRealtime()
            val lane = runCatching { laneAnalyzer.analyze(frame, elapsedNow) }.getOrNull()
            val plates = checkNotNull(engine).recognize(frame)
            settings.recordCandidates(plates, frame.width, frame.height, now)
            val calibration = settings.calibrationFor(frame.width, frame.height)
            val readings = distanceEstimator.estimate(plates, lane, calibration,
                frame.width, frame.height, elapsedNow)
            val warning = if (settings.adasEnabled) warningEngine.observe(lane,
                settings.laneCenterRatio, readings, calibration != null,
                settings.closeMetres, elapsedNow) else {
                warningEngine.resetTracks(); null
            }
            if (warning != null) {
                lastWarning = warning
                warningUntil = elapsedNow + 4_000
            }
            val activeWarning = lastWarning?.takeIf { elapsedNow < warningUntil }
            val scene = DriveScene(plates, lane, readings, frame.width, frame.height, activeWarning)
            val day = DailyPlateStore.localDay(now)
            if (day != today) { today = day; savedToday = store.countForDay(day) }
            var saved: List<String> = emptyList()
            var saveError: String? = null
            val candidates = if (settings.autoSave) gate.observe(plates, day, elapsedNow)
                else { gate.observe(emptyList(), day, elapsedNow); emptyList() }
            if (candidates.isNotEmpty()) {
                try {
                    saved = store.saveNew(day, candidates, frame, plates, now, scene)
                    savedToday += saved.size
                    if (saved.isNotEmpty()) lastSavedText = saved.joinToString()
                } catch (e: Exception) {
                    candidates.forEach { gate.forget(it.text, day) }
                    saveError = e.message ?: "無法寫入相簿"
                }
            }
            val millis = SystemClock.elapsedRealtime() - started
            val nearest = readings.filter { it.inOurLane }.minByOrNull { it.metres }
                ?: readings.minByOrNull { it.metres }
            val distanceLabel = when {
                calibration == null -> "距離 · 未校正"
                nearest == null -> "距離 · 不可用"
                else -> "估計 ~${"%.1f".format(Locale.ROOT, nearest.metres)} m"
            }
            val statusLabel = when {
                saveError != null -> "存照失敗：$saveError · 今日 $savedToday 個車牌"
                saved.isNotEmpty() -> "已存照：${saved.joinToString()} · 今日 $savedToday 個車牌"
                else -> "今日 $savedToday 個車牌 · 最近 ${lastSavedText.ifEmpty { "尚無紀錄" }} · ${millis}ms"
            }
            runOnUiThread {
                if (isDestroyed || paused) return@runOnUiThread
                overlay.show(scene)
                status.text = statusLabel
                featureStatus.text = (if (lane != null) "車道已追蹤" else "車道未偵測") +
                    " · ${plates.size} 張可見車牌" + (if (settings.adasEnabled) "" else " · 警示關閉")
                distanceStatus.text = distanceLabel
                warningBanner.visibility = if (activeWarning != null) View.VISIBLE else View.GONE
                if (activeWarning != null) {
                    warningBanner.text = activeWarning.text
                    warningBanner.setTextColor(if (activeWarning.kind == WarningKind.CLOSING)
                        0xFFD85B51.toInt() else 0xFFE9B65E.toInt())
                }
                if (warning != null) alert(warning)
            }
        } catch (e: Exception) {
            analysis?.clearAnalyzer()
            runOnUiThread { if (!isDestroyed) status.text = "辨識停止：${e.message}" }
        } finally { frame.recycle() }
    }

    private fun alert(warning: DriveWarning) {
        if (settings.soundEnabled) {
            if (tone == null) tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65) }.getOrNull()
            runCatching {
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP,
                    if (warning.kind == WarningKind.CLOSING) 280 else 150)
            }
        }
        if (settings.vibrationEnabled) {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
            if (vibrator?.hasVibrator() == true) runCatching {
                vibrator.vibrate(VibrationEffect.createOneShot(
                    if (warning.kind == WarningKind.CLOSING) 280 else 150,
                    VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    override fun onDestroy() {
        analysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
        tone?.release()
        worker.execute { engine?.close(); store.close() }
        worker.shutdown()
        super.onDestroy()
    }
}
