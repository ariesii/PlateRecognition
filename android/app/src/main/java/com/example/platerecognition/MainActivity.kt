package com.example.platerecognition

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.platerecognition.camera.CameraFrame
import com.example.platerecognition.camera.PlateOverlay
import com.example.platerecognition.recognition.PlateEngine
import com.example.platerecognition.recognition.StabilityGate
import com.example.platerecognition.storage.DailyPlateStore
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

class MainActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private lateinit var overlay: PlateOverlay
    private lateinit var status: TextView
    private lateinit var store: DailyPlateStore
    private val gate = StabilityGate()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var engine: PlateEngine? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var today: String = ""
    private var savedToday = 0

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.setText(R.string.camera_permission)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        status = findViewById(R.id.status)
        preview.scaleType = PreviewView.ScaleType.FILL_CENTER
        store = DailyPlateStore(this)
        worker.execute {
            try {
                store.recoverInterrupted()
                today = DailyPlateStore.localDay(System.currentTimeMillis())
                savedToday = store.countForDay(today)
                engine = PlateEngine(this)
                runOnUiThread {
                    status.text = "模型就緒 · 今日已保存 $savedToday 個車牌 · 掃描中"
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        startCamera()
                    } else requestCamera.launch(Manifest.permission.CAMERA)
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "模型初始化失敗：${e.message}" }
            }
        }
    }

    private fun startCamera() {
        if (isFinishing || isDestroyed || engine == null) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val cameraPreview = Preview.Builder().setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)
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
        val frame = try {
            CameraFrame.copyRotated(image)
        } catch (e: Exception) {
            runOnUiThread { status.text = "相機影格錯誤：${e.message}" }
            return
        } finally {
            image.close()
        }
        try {
            val started = SystemClock.elapsedRealtime()
            val results = checkNotNull(engine).recognize(frame)
            val now = System.currentTimeMillis()
            val day = DailyPlateStore.localDay(now)
            if (day != today) {
                today = day
                savedToday = store.countForDay(day)
            }
            var lastSaved: List<String> = emptyList()
            var saveError: String? = null
            val candidates = gate.observe(results, day, SystemClock.elapsedRealtime())
            try {
                lastSaved = store.saveNew(day, candidates, frame, results, now)
                savedToday += lastSaved.size
            } catch (e: Exception) {
                candidates.forEach { gate.forget(it.text, day) }
                saveError = e.message ?: "無法寫入相簿"
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            val imageWidth = frame.width
            val imageHeight = frame.height
            runOnUiThread {
                if (!isDestroyed) {
                    overlay.show(results, imageWidth, imageHeight)
                    status.text = when {
                        saveError != null -> "存檔失敗：$saveError"
                        lastSaved.isNotEmpty() -> "已存檔：${lastSaved.joinToString()} · 今日 $savedToday 個車牌 · ${elapsed}ms"
                        else -> "即時辨識：${results.joinToString { it.text }.ifEmpty { "尋找車牌中" }} · 今日 $savedToday 個車牌 · ${elapsed}ms"
                    }
                }
            }
        } catch (e: Exception) {
            analysis?.clearAnalyzer()
            runOnUiThread { status.text = "辨識停止：${e.message}" }
        } finally {
            frame.recycle()
        }
    }

    override fun onDestroy() {
        analysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
        worker.execute {
            engine?.close()
            store.close()
        }
        worker.shutdown()
        super.onDestroy()
    }
}
