package com.example.platerecognition.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Owns two ONNX Runtime sessions. Call from the single camera analysis executor only. */
class PlateEngine(context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val detector: OrtSession
    private val ocr: OrtSession
    private val detectorInput: String
    private val ocrInput: String

    init {
        val modelDirectory = File(context.filesDir, "models").apply { mkdirs() }
        val detectFile = modelFile(context, modelDirectory, "detector.onnx", 3_838_666L)
        val ocrFile = modelFile(context, modelDirectory, "ocr.onnx", 76_554_979L)
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        try {
            detector = environment.createSession(detectFile.absolutePath, options)
            try {
                ocr = environment.createSession(ocrFile.absolutePath, options)
            } catch (e: Exception) {
                detector.close()
                throw e
            }
        } finally {
            options.close()
        }
        detectorInput = detector.inputNames.single()
        ocrInput = ocr.inputNames.single()
        val detectorInfo = detector.inputInfo.values.single().info as TensorInfo
        val ocrInfo = ocr.inputInfo.values.single().info as TensorInfo
        require(detectorInfo.shape.contentEquals(longArrayOf(1, 3, 640, 640))) {
            "Unexpected detector input: ${detectorInfo.shape.contentToString()}"
        }
        require(ocrInfo.shape.size == 4 && ocrInfo.shape[1] == 3L && ocrInfo.shape[2] == 48L) {
            "Unexpected OCR input: ${ocrInfo.shape.contentToString()}"
        }
    }

    fun detect(frame: Bitmap): List<PlateDetection> {
        val input = prepareDetector(frame)
        val detected = infer(detector, detectorInput, input, longArrayOf(1, 3, 640, 640))
        return DetectorMath.decode(detected.first, detected.second, frame.width, frame.height)
    }

    fun readPlate(frame: Bitmap, detection: PlateDetection): PlateResult? {
        val corrected = rectify(frame, detection) ?: return null
        val crop = if (detection.doubleRow) splitDoubleRow(corrected) else corrected
        try {
            val (ocrInputData, width) = prepareOcr(crop)
            val output = infer(ocr, ocrInput, ocrInputData, longArrayOf(1, 3, 48, width.toLong()))
            val text = OcrDecoder.decode(output.first, output.second)
            return if (text.isBlank()) null else PlateResult(detection, text)
        } finally {
            if (crop !== corrected) crop.recycle()
            corrected.recycle()
        }
    }

    fun recognize(frame: Bitmap): List<PlateResult> = detect(frame).mapNotNull { readPlate(frame, it) }

    override fun close() {
        ocr.close()
        detector.close()
        // The shared ORT environment belongs to the process, not this Activity.
    }

    private fun modelFile(context: Context, dir: File, name: String, length: Long): File {
        val file = File(dir, name)
        if (file.length() != length) {
            val temp = File(dir, "$name.partial")
            try {
                context.assets.open(name).use { input ->
                    FileOutputStream(temp).use { output -> input.copyTo(output) }
                }
                require(temp.length() == length) { "Invalid asset size: $name" }
                if (file.exists()) check(file.delete()) { "Cannot replace damaged $name" }
                check(temp.renameTo(file)) { "Cannot install $name" }
            } finally {
                temp.delete()
            }
        }
        return file
    }

    private fun infer(session: OrtSession, inputName: String, data: FloatArray, shape: LongArray): Pair<FloatArray, LongArray> {
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), shape).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                require(result.size() == 1) { "Model must have one output" }
                val tensor = result[0] as OnnxTensor
                val dimensions = (tensor.info as TensorInfo).shape
                require(dimensions.size == 3 && dimensions[0] == 1L &&
                    dimensions[1] > 0 && dimensions[2] > 0 &&
                    dimensions[1] * dimensions[2] <= Int.MAX_VALUE) {
                    "Unexpected output shape: ${dimensions.contentToString()}"
                }
                val flat = FloatArray((dimensions[1] * dimensions[2]).toInt())
                val buffer = checkNotNull(tensor.floatBuffer) { "Model output must contain floats" }
                require(buffer.remaining() == flat.size) { "Output tensor size mismatch" }
                buffer.get(flat)
                return flat to dimensions
            }
        }
    }

    private fun prepareDetector(frame: Bitmap): FloatArray {
        val scale = min(640f / frame.width, 640f / frame.height)
        val x = (640 - frame.width * scale) / 2f
        val y = (640 - frame.height * scale) / 2f
        val canvasBitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(canvasBitmap)
            canvas.drawColor(Color.rgb(114, 114, 114))
            canvas.drawBitmap(frame, null,
                RectF(x, y, x + frame.width * scale, y + frame.height * scale),
                Paint(Paint.FILTER_BITMAP_FLAG))
            val pixels = IntArray(640 * 640)
            canvasBitmap.getPixels(pixels, 0, 640, 0, 0, 640, 640)
            val data = FloatArray(pixels.size * 3)
            pixels.forEachIndexed { index, pixel ->
                data[index] = Color.red(pixel) / 255f
                data[pixels.size + index] = Color.green(pixel) / 255f
                data[pixels.size * 2 + index] = Color.blue(pixel) / 255f
            }
            return data
        } finally {
            canvasBitmap.recycle()
        }
    }

    private fun rectify(frame: Bitmap, detection: PlateDetection): Bitmap? {
        val p = detection.points
        val width = max(hypot(p[0].x - p[1].x, p[0].y - p[1].y),
            hypot(p[2].x - p[3].x, p[2].y - p[3].y)).toInt()
        val height = max(hypot(p[0].x - p[3].x, p[0].y - p[3].y),
            hypot(p[1].x - p[2].x, p[1].y - p[2].y)).toInt()
        if (width < 12 || height < 6 || width > 3200 || height > 1000) return null
        val src = p.flatMap { listOf(it.x, it.y) }.toFloatArray()
        val dst = floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat())
        val transform = Matrix()
        if (!transform.setPolyToPoly(src, 0, dst, 0, 4)) return null
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(frame, transform, Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    /** The top 5/12 and bottom 2/3 are resized/placed side by side, matching upstream. */
    private fun splitDoubleRow(image: Bitmap): Bitmap {
        val lowerTop = image.height / 3
        val lowerHeight = image.height - lowerTop
        val result = Bitmap.createBitmap(image.width * 2, lowerHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.rgb(114, 114, 114))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(image, android.graphics.Rect(0, 0, image.width, max(1, (image.height * 5f / 12f).toInt())),
            RectF(0f, 0f, image.width.toFloat(), lowerHeight.toFloat()), paint)
        canvas.drawBitmap(image, android.graphics.Rect(0, lowerTop, image.width, image.height),
            RectF(image.width.toFloat(), 0f, (image.width * 2).toFloat(), lowerHeight.toFloat()), paint)
        return result
    }

    private fun prepareOcr(crop: Bitmap): Pair<FloatArray, Int> {
        val contentWidth = ceil(48.0 * crop.width / crop.height).toInt().coerceIn(1, 3200)
        val canvasWidth = max(320, contentWidth)
        val resized = Bitmap.createScaledBitmap(crop, contentWidth, 48, true)
        try {
            val pixels = IntArray(contentWidth * 48)
            resized.getPixels(pixels, 0, contentWidth, 0, 0, contentWidth, 48)
            val plane = canvasWidth * 48
            val data = FloatArray(plane * 3)
            pixels.forEachIndexed { index, color ->
                val offset = (index / contentWidth) * canvasWidth + (index % contentWidth)
                data[offset] = Color.blue(color) / 127.5f - 1f
                data[plane + offset] = Color.green(color) / 127.5f - 1f
                data[plane * 2 + offset] = Color.red(color) / 127.5f - 1f
            }
            return data to canvasWidth
        } finally {
            if (resized !== crop) resized.recycle()
        }
    }
}
