package com.example.platerecognition

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.core.content.ContextCompat
import com.example.platerecognition.camera.CameraFrame
import com.example.platerecognition.camera.GrayFrame
import com.example.platerecognition.camera.PlateOverlay
import com.example.platerecognition.camera.PlateTracker
import com.example.platerecognition.recognition.DetectorMath
import com.example.platerecognition.recognition.PlateEngine
import com.example.platerecognition.recognition.PlateResult
import com.example.platerecognition.recognition.RecognitionCue
import com.example.platerecognition.recognition.StabilityGate
import com.example.platerecognition.settings.AppSettings
import com.example.platerecognition.storage.DailyPlateStore
import com.example.platerecognition.ui.EventsActivity
import com.example.platerecognition.ui.SettingsActivity
import com.example.platerecognition.vision.*
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Camera tracking, inference, and UI have separate ownership. No unbounded frame queues. */
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
    private val cameraWorker = Executors.newSingleThreadExecutor()
    private val mlWorker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val tracker = PlateTracker() // cameraWorker only
    private val laneAnalyzer = LaneAnalyzer() // cameraWorker only
    private val distanceEstimator = DistanceEstimator() // mlWorker only
    private val warningEngine = WarningEngine() // mlWorker only
    private val gate = StabilityGate() // fresh OCR only, not cached/tracked strings
    private val cue = RecognitionCue()
    private var engine: PlateEngine? = null
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var cameraPreview: Preview? = null
    private var lane: LaneEstimate? = null
    private var laneTime = 0L
    private var today = ""
    private var savedToday = 0
    private var tone: ToneGenerator? = null
    @Volatile private var ready = false
    @Volatile private var stopped = false
    @Volatile private var paused = false
    @Volatile private var session = 0
    @Volatile private var known = emptyList<PlateTracker.Known>()
    @Volatile private var latestLane: LaneEstimate? = null
    @Volatile private var readings = emptyList<DistanceReading>()
    @Volatile private var warning: DriveWarning? = null
    @Volatile private var warningUntil = 0L
    @Volatile private var statusLabel = "正在載入離線模型…"
    @Volatile private var inferenceLabel = ""
    @Volatile private var lastInferenceTime = 0L
    private var lastRendered = 0L
    private val transforms = ImageProxyTransformFactory().apply {
        isUsingCropRect = true
        isUsingRotationDegrees = true
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (::preview.isInitialized && preview.display?.displayId == displayId) {
                val rotation = preview.display.rotation
                if (analysis?.targetRotation != rotation) {
                    session++
                    analysis?.targetRotation = rotation
                    cameraPreview?.targetRotation = rotation
                    cameraWorker.execute { tracker.reset(); known = emptyList(); lane = null }
                    overlay.show(null)
                }
            }
        }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) preview.post { startCamera() } else status.setText(R.string.camera_permission)
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
        settings = AppSettings(this)
        store = DailyPlateStore(this)
        saveSwitch.isChecked = settings.autoSave
        saveSwitch.setOnCheckedChangeListener { _, enabled -> settings.autoSave = enabled }
        pauseButton.setOnClickListener {
            paused = !paused
            session++
            pauseButton.text = if (paused) "繼續分析" else "暫停分析"
            overlay.show(null)
            warningBanner.visibility = View.GONE
            cameraWorker.execute { tracker.reset(); known = emptyList(); lane = null }
            mlWorker.execute { gate.observe(emptyList(), day(), SystemClock.elapsedRealtime()); cue.reset() }
            status.text = if (paused) "分析已暫停 · 相機預覽仍開啟" else "辨識中…"
        }
        findViewById<Button>(R.id.eventsButton).setOnClickListener {
            startActivity(Intent(this, EventsActivity::class.java))
        }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        mlWorker.execute {
            try {
                store.recoverInterrupted()
                today = day(); savedToday = store.countForDay(today)
                engine = PlateEngine(this)
                ready = true
                statusLabel = "模型就緒 · 今日 $savedToday 個車牌"
                runOnUiThread {
                    if (!stopped) {
                        status.text = statusLabel
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                            preview.post { startCamera() }
                        else permission.launch(Manifest.permission.CAMERA)
                    }
                }
            } catch (e: Exception) {
                statusLabel = "模型初始化失敗：${e.message}"
                runOnUiThread { if (!stopped) status.text = statusLabel }
                Log.e("PlatePipeline", "init failed", e)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, null)
    }
    override fun onStop() {
        session++ // Discard results belonging to a screen the user has left.
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        overlay.show(null)
        super.onStop()
    }
    override fun onResume() {
        super.onResume()
        if (::settings.isInitialized) saveSwitch.isChecked = settings.autoSave
    }

    private fun startCamera() {
        if (stopped || !ready || preview.width == 0) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (stopped) return@addListener
            try {
                val value = future.get()
                provider = value
                val rotation = preview.display.rotation
                val p = Preview.Builder().setTargetRotation(rotation).build()
                p.surfaceProvider = preview.surfaceProvider
                val a = ImageAnalysis.Builder().setTargetResolution(Size(1280, 720))
                    .setTargetRotation(rotation)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                a.setAnalyzer(cameraWorker, ImageAnalysis.Analyzer { analyze(it) })
                val viewport = checkNotNull(preview.viewPort) { "相機預覽尚未完成版面配置" }
                val group = UseCaseGroup.Builder().setViewPort(viewport).addUseCase(p).addUseCase(a).build()
                value.unbindAll()
                value.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group)
                analysis = a; cameraPreview = p
            } catch (e: Exception) { status.text = "相機啟動失敗：${e.message}" }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        if (stopped || paused || !ready) { image.close(); return }
        val token = session
        val now = SystemClock.elapsedRealtime()
        var acquired = false
        try {
            val sample = CameraFrame.sampleGray(image, now)
            val source = transforms.getOutputTransform(image)
            val current = tracker.update(sample)
            known = tracker.known()
            if (now - laneTime > 250L) {
                lane = runCatching { laneAnalyzer.analyze(sample, now) }.getOrNull()
                latestLane = lane; laneTime = now
            }
            if (now - lastRendered >= 45L) {
                lastRendered = now
                render(current, sample, source, token)
            }
            if (busy.compareAndSet(false, true)) {
                acquired = true
                val frame = CameraFrame.copyRotated(image)
                val hints = known
                val laneSnapshot = lane
                mlWorker.execute {
                    try { infer(frame, sample, source, hints, laneSnapshot, token) }
                    catch (e: Exception) {
                        Log.e("PlatePipeline", "inference failed", e)
                        statusLabel = "辨識失敗：${e.message}"
                    } finally { frame.recycle(); busy.set(false) }
                }
                acquired = false // Bitmap ownership passed to ML worker.
            }
        } catch (e: Exception) {
            if (acquired) busy.set(false)
            Log.e("PlatePipeline", "frame failed", e)
        } finally { image.close() }
    }

    private fun infer(frame: android.graphics.Bitmap, sample: GrayFrame, source: OutputTransform,
                      hints: List<PlateTracker.Known>, laneSnapshot: LaneEstimate?, token: Int) {
        if (stopped || token != session || paused) return
        val start = SystemClock.elapsedRealtime()
        val model = checkNotNull(engine)
        val detections = model.detect(frame)
        val detectedAt = SystemClock.elapsedRealtime()
        cameraWorker.execute {
            if (!stopped && token == session && !paused) {
                val snapshot = tracker.reconcile(sample, detections, emptyList(), sample.observedAt)
                known = tracker.known()
                render(snapshot, sample, source, token)
            }
        }
        val cached = mutableListOf<PlateResult>()
        val needsReading = detections.filter { d ->
            val hint = hints.firstOrNull { it.confirmed && start - it.verifiedAt < 5_000L &&
                it.detection.doubleRow == d.doubleRow && DetectorMath.iou(it.detection.box, d.box) > 0.65f }
            if (hint != null) { cached += PlateResult(d, hint.text); false } else true
        }.sortedByDescending { it.confidence }
        // A bounded OCR budget keeps a busy multi-plate frame from freezing all subsequent detections.
        val fresh = needsReading.take(2).mapNotNull { model.readPlate(frame, it) }
        val done = SystemClock.elapsedRealtime()
        if (stopped || token != session || paused) return
        val now = System.currentTimeMillis()
        val todayNow = DailyPlateStore.localDay(now)
        val stable = gate.observe(fresh, todayNow, done)
        val all = cached + fresh
        val confirmedTexts = stable.map { it.text }.toSet() + cached.map { it.text }
        cameraWorker.execute {
            if (!stopped && token == session && !paused) {
                val snapshot = tracker.reconcile(sample, detections, fresh, done, confirmedTexts)
                known = tracker.known()
                render(snapshot, sample, source, token)
            }
        }
        settings.recordCandidates(fresh, frame.width, frame.height, now)
        val calibration = settings.calibrationFor(frame.width, frame.height)
        val measured = distanceEstimator.estimate(fresh, laneSnapshot, calibration, frame.width, frame.height, done)
        readings = measured
        val nextWarning = if (settings.adasEnabled) warningEngine.observe(laneSnapshot,
            settings.laneCenterRatio, measured, calibration != null, settings.closeMetres, done)
            else { warningEngine.resetTracks(); null }
        if (nextWarning != null) { warning = nextWarning; warningUntil = done + 4_000L }
        val activeWarning = warning?.takeIf { done < warningUntil && calibration != null && settings.adasEnabled }
        val scene = DriveScene(all, laneSnapshot, measured, frame.width, frame.height, activeWarning)
        val shouldSound = cue.observe(all.map { it.text }, stable.map { it.text }, done)
        if (today != todayNow) { today = todayNow; savedToday = store.countForDay(today) }
        val saved = if (settings.autoSave && stable.isNotEmpty()) {
            try { store.saveNew(today, stable, frame, fresh, now, scene) }
            catch (e: Exception) {
                stable.forEach { gate.forget(it.text, today) }
                statusLabel = "存照失敗：${e.message}"
                Log.e("PlatePipeline", "save failed", e)
                emptyList()
            }
        } else emptyList()
        savedToday += saved.size
        if (saved.isNotEmpty()) statusLabel = "已存照：${saved.joinToString()} · 今日 $savedToday 個車牌"
        else if (!statusLabel.startsWith("存照失敗")) statusLabel = "今日 $savedToday 個車牌 · ${all.joinToString { it.text }.ifBlank { "尋找車牌中" }}"
        inferenceLabel = "偵測 ${detectedAt - start}ms / OCR ${done - detectedAt}ms"
        lastInferenceTime = done
        Log.i("PlatePipeline", "detect_ms=${detectedAt - start} ocr_ms=${done - detectedAt} total_ms=${done - start} plates=${detections.size} fresh=${fresh.size} cached=${cached.size} age_ms=${done - sample.observedAt}")
        runOnUiThread {
            if (!stopped && token == session && !paused) {
                if (shouldSound && settings.recognitionSoundEnabled) recognitionBeep()
                if (nextWarning != null) alert(nextWarning)
            }
        }
    }

    private fun render(snapshot: PlateTracker.Snapshot, sample: GrayFrame, source: OutputTransform, token: Int) {
        val visibleNames = snapshot.confirmed.map { it.text }.toSet()
        val recentReadings = if (sample.observedAt - lastInferenceTime in 0L..1_500L)
            readings.filter { it.text in visibleNames } else emptyList()
        val scene = DriveScene(snapshot.confirmed, lane, recentReadings, sample.imageWidth, sample.imageHeight,
            provisional = snapshot.pending)
        runOnUiThread {
            if (stopped || paused || token != session) return@runOnUiThread
            val target = preview.outputTransform
            val matrix = if (target != null) runCatching {
                Matrix().also { CoordinateTransform(source, target).transform(it) }
            }.getOrNull() else null
            overlay.show(scene, matrix)
            status.text = statusLabel
            featureStatus.text = "${snapshot.confirmed.size} 張車牌 · 追蹤中 · $inferenceLabel"
            val currentCalibration = settings.calibrationFor(sample.imageWidth, sample.imageHeight)
            val closest = readings.takeIf { SystemClock.elapsedRealtime() - lastInferenceTime < 1_500L }
                ?.minByOrNull { it.metres }
            distanceStatus.text = when {
                currentCalibration == null -> "距離 · 此方向未校正"
                closest == null -> "距離 · 不可用"
                else -> "估計 ~${"%.1f".format(Locale.ROOT, closest.metres)} m"
            }
            val active = warning?.takeIf { SystemClock.elapsedRealtime() < warningUntil &&
                currentCalibration != null && settings.adasEnabled }
            warningBanner.visibility = if (active == null) View.GONE else View.VISIBLE
            warningBanner.text = active?.text ?: ""
        }
    }

    private fun recognitionBeep() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        if (tone == null) tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65) }.getOrNull()
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_ACK, 160) }
        Log.i("PlatePipeline", "recognition_cue requested")
    }
    private fun alert(value: DriveWarning) {
        if (settings.soundEnabled) {
            if (tone == null) tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65) }.getOrNull()
            runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 250) }
        }
        if (settings.vibrationEnabled) runCatching {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
            if (vibrator?.hasVibrator() == true) vibrator.vibrate(
                VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
    private fun day() = DailyPlateStore.localDay(System.currentTimeMillis())

    override fun onDestroy() {
        stopped = true; session++
        analysis?.clearAnalyzer(); provider?.unbindAll(); tone?.release()
        // ML may still publish to cameraWorker: drain it before closing the camera executor.
        mlWorker.execute { engine?.close(); store.close(); cameraWorker.shutdown() }
        mlWorker.shutdown()
        super.onDestroy()
    }
}
