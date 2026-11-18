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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A successful (local calendar day, plate) reservation is permanent for that day. */
class DailyPlateStore(private val context: Context) : SQLiteOpenHelper(context, "plates.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE captures (day TEXT NOT NULL, plate TEXT NOT NULL, state TEXT NOT NULL, uri TEXT, PRIMARY KEY(day, plate))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("No migration from schema $oldVersion to $newVersion")
    }

    /** Reclaim interrupted writes. Several plates may share a single photographed frame. */
    fun recoverInterrupted() {
        val db = writableDatabase
        val uris = mutableSetOf<String>()
        db.query("captures", arrayOf("uri"), "state=?", arrayOf("pending"),
            null, null, null).use { cursor ->
            while (cursor.moveToNext()) if (!cursor.isNull(0)) uris += cursor.getString(0)
        }
        // Failure must stop startup rather than allow a second photo.
        uris.forEach { context.contentResolver.delete(Uri.parse(it), null, null) }
        db.delete("captures", "state=?", arrayOf("pending"))
    }

    fun countForDay(day: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM captures WHERE day=? AND state=?", arrayOf(day, "saved")
    ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    /** Stores one annotated analyzed frame for all newly stable plates in that frame. */
    fun saveNew(day: String, candidates: List<PlateResult>, frame: Bitmap,
                results: List<PlateResult>, atMillis: Long): List<String> {
        val db = writableDatabase
        val inserted = mutableListOf<String>()
        var media: Uri? = null
        try {
            candidates.mapNotNull { StabilityGate.normalize(it.text) }.distinct().forEach { plate ->
                val reservation = ContentValues().apply {
                    put("day", day); put("plate", plate); put("state", "pending")
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
            val marked = annotate(frame, results, atMillis)
            try {
                context.contentResolver.openOutputStream(created, "w").use { stream ->
                    checkNotNull(stream) { "Cannot open image output" }
                    check(marked.compress(Bitmap.CompressFormat.JPEG, 90, stream)) { "JPEG encode failed" }
                }
            } finally {
                marked.recycle()
            }
            val published = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            check(context.contentResolver.update(created, published, null, null) == 1) { "Image publish failed" }
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
            // If photo deletion fails, retain pending reservations to prevent duplicates.
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

    private fun annotate(frame: Bitmap, results: List<PlateResult>, millis: Long): Bitmap {
        val marked = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(marked)
        canvas.drawBitmap(frame, 0f, 0f, null)
        val scale = (frame.width / 720f).coerceIn(0.75f, 2.5f)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 213, 0); style = Paint.Style.STROKE; strokeWidth = 3f * scale
        }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 24f * scale }
        val bg = Paint().apply { color = 0xCC101820.toInt() }
        results.forEach { result ->
            val box = result.detection.box
            canvas.drawRect(box.left, box.top, box.right, box.bottom, stroke)
            val width = label.measureText(result.text) + 14f * scale
            val y = (box.top - 30f * scale).coerceAtLeast(0f)
            canvas.drawRect(RectF(box.left, y, (box.left + width).coerceAtMost(frame.width.toFloat()), y + 32f * scale), bg)
            canvas.drawText(result.text, box.left + 6f * scale, y + 25f * scale, label)
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
