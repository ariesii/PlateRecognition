package com.example.platerecognition.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.provider.MediaStore
import com.example.platerecognition.recognition.PlateResult
import com.example.platerecognition.recognition.StabilityGate
import com.example.platerecognition.vision.DriveScene
import com.example.platerecognition.vision.ScenePainter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CaptureRecord(
    val day: String,
    val plate: String,
    val text: String,
    val capturedAt: Long,
    val confidence: Float,
    val uri: Uri?,
    val deleted: Boolean
)

/** Daily uniqueness applies even if an image is later removed from the gallery. */
class DailyPlateStore(private val context: Context) : SQLiteOpenHelper(context, "plates.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE captures (
            day TEXT NOT NULL, plate TEXT NOT NULL, state TEXT NOT NULL, uri TEXT,
            captured_at INTEGER NOT NULL DEFAULT 0, display_text TEXT NOT NULL DEFAULT '',
            confidence REAL NOT NULL DEFAULT 0,
            PRIMARY KEY(day, plate))""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) {
            db.execSQL("ALTER TABLE captures ADD COLUMN captured_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE captures ADD COLUMN display_text TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE captures ADD COLUMN confidence REAL NOT NULL DEFAULT 0")
        } else error("No migration from schema $oldVersion to $newVersion")
    }

    /** Reclaim interrupted writes. Several plates may share a single photographed frame. */
    fun recoverInterrupted() {
        val db = writableDatabase
        val uris = mutableSetOf<String>()
        db.query("captures", arrayOf("uri"), "state=?", arrayOf("pending"),
            null, null, null).use { cursor ->
            while (cursor.moveToNext()) if (!cursor.isNull(0)) uris += cursor.getString(0)
        }
        uris.forEach { context.contentResolver.delete(Uri.parse(it), null, null) }
        db.delete("captures", "state=?", arrayOf("pending"))
    }

    fun countForDay(day: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM captures WHERE day=? AND state=?", arrayOf(day, "saved")
    ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    /** Last 200 records, including tombstones so deletion does not silently permit recapture. */
    fun listRecords(day: String? = null, plateContains: String = ""): List<CaptureRecord> {
        val clauses = mutableListOf("state IN ('saved','deleted')")
        val args = mutableListOf<String>()
        if (!day.isNullOrBlank()) { clauses += "day=?"; args += day }
        if (plateContains.isNotBlank()) {
            clauses += "plate LIKE ? ESCAPE '\\'"
            val escaped = plateContains.trim().uppercase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            args += "%$escaped%"
        }
        val result = ArrayList<CaptureRecord>()
        readableDatabase.query("captures",
            arrayOf("day", "plate", "state", "uri", "captured_at", "display_text", "confidence"),
            clauses.joinToString(" AND "), args.toTypedArray(), null, null,
            "captured_at DESC, day DESC, plate ASC", "200").use { cursor ->
            while (cursor.moveToNext()) {
                val plate = cursor.getString(1)
                val text = cursor.getString(5).ifBlank { plate } // Schema v1 images keep their key.
                result += CaptureRecord(cursor.getString(0), plate, text, cursor.getLong(4),
                    cursor.getFloat(6), if (cursor.isNull(3)) null else Uri.parse(cursor.getString(3)),
                    cursor.getString(2) == "deleted")
            }
        }
        return result
    }

    /** Remove the image when it has no other plate references. Keep the dedup key for today. */
    fun deletePhoto(day: String, plate: String) {
        val db = writableDatabase
        val row = db.query("captures", arrayOf("uri"), "day=? AND plate=? AND state=?",
            arrayOf(day, plate, "saved"), null, null, null).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        } ?: return
        val others = db.rawQuery("SELECT COUNT(*) FROM captures WHERE uri=? AND state=? AND NOT (day=? AND plate=?)",
            arrayOf(row, "saved", day, plate)).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
        if (others == 0) context.contentResolver.delete(Uri.parse(row), null, null)
        check(db.update("captures", ContentValues().apply {
            put("state", "deleted"); putNull("uri")
        }, "day=? AND plate=? AND state=?", arrayOf(day, plate, "saved")) == 1) {
            "Cannot mark image as deleted"
        }
    }

    /** Stores one annotated analyzed frame for all newly stable plates in that frame. */
    fun saveNew(day: String, candidates: List<PlateResult>, frame: Bitmap,
                results: List<PlateResult>, atMillis: Long, scene: DriveScene? = null): List<String> {
        val db = writableDatabase
        val inserted = mutableListOf<String>()
        var media: Uri? = null
        try {
            candidates.mapNotNull { result ->
                StabilityGate.normalize(result.text)?.let { it to result }
            }.distinctBy { it.first }.forEach { (plate, result) ->
                val reservation = ContentValues().apply {
                    put("day", day); put("plate", plate); put("state", "pending")
                    put("captured_at", atMillis); put("display_text", result.text)
                    put("confidence", result.detection.confidence)
                }
                if (db.insertWithOnConflict("captures", null, reservation, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    inserted += plate
                }
            }
            if (inserted.isEmpty()) return emptyList()
            val date = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date(atMillis))
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "${date}_${inserted.first()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PlateRecognition")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val created = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
                "MediaStore refused image creation"
            }
            media = created
            inserted.forEach { plate ->
                check(db.update("captures", ContentValues().apply { put("uri", created.toString()) },
                    "day=? AND plate=? AND state=?", arrayOf(day, plate, "pending")) == 1) {
                    "Cannot attach image to reservation"
                }
            }
            val marked = annotate(frame, results, atMillis, scene)
            try {
                context.contentResolver.openOutputStream(created, "w").use { stream ->
                    checkNotNull(stream) { "Cannot open image output" }
                    check(marked.compress(Bitmap.CompressFormat.JPEG, 90, stream)) { "JPEG encode failed" }
                }
            } finally {
                marked.recycle()
            }
            check(context.contentResolver.update(created,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1) {
                "Image publish failed"
            }
            db.beginTransaction()
            try {
                inserted.forEach { plate ->
                    check(db.update("captures", ContentValues().apply { put("state", "saved") },
                        "day=? AND plate=? AND state=?", arrayOf(day, plate, "pending")) == 1) {
                        "Cannot commit plate reservation"
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            return inserted
        } catch (e: Exception) {
            // If deletion fails, retain reservations rather than risk two photos of one plate.
            val cleanupUri = media
            val removed = cleanupUri == null || runCatching {
                context.contentResolver.delete(cleanupUri, null, null)
            }.isSuccess
            if (removed) inserted.forEach { plate ->
                db.delete("captures", "day=? AND plate=? AND state=?", arrayOf(day, plate, "pending"))
            }
            throw e
        }
    }

    private fun annotate(frame: Bitmap, results: List<PlateResult>, millis: Long,
                         scene: DriveScene?): Bitmap {
        val marked = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(marked)
        canvas.drawBitmap(frame, 0f, 0f, null)
        val scale = (frame.width / 720f).coerceIn(0.75f, 2.5f)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 24f * scale }
        val bg = Paint().apply { color = 0xCC14232C.toInt() }
        if (scene != null) {
            ScenePainter.draw(canvas, scene, frame.width, frame.height)
        } else {
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(46, 171, 179); style = Paint.Style.STROKE; strokeWidth = 3f * scale
            }
            results.forEach { result ->
                val box = result.detection.box
                canvas.drawRect(box.left, box.top, box.right, box.bottom, stroke)
                val width = label.measureText(result.text) + 14f * scale
                val y = (box.top - 30f * scale).coerceAtLeast(0f)
                canvas.drawRect(RectF(box.left, y, (box.left + width).coerceAtMost(frame.width.toFloat()), y + 32f * scale), bg)
                canvas.drawText(result.text, box.left + 6f * scale, y + 25f * scale, label)
            }
        }
        val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(millis))
        canvas.drawRect(0f, frame.height - 36f * scale, label.measureText(clock) + 18f * scale,
            frame.height.toFloat(), bg)
        canvas.drawText(clock, 8f * scale, frame.height - 10f * scale, label)
        return marked
    }

    companion object {
        fun localDay(timeMillis: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(timeMillis))
    }
}
