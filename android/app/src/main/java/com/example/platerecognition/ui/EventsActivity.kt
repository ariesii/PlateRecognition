package com.example.platerecognition.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.addTextChangedListener
import com.example.platerecognition.R
import com.example.platerecognition.storage.CaptureRecord
import com.example.platerecognition.storage.DailyPlateStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class EventsActivity : AppCompatActivity() {
    private lateinit var store: DailyPlateStore
    private lateinit var list: LinearLayout
    private lateinit var count: TextView
    private lateinit var search: EditText
    private lateinit var todayOnly: SwitchCompat
    private val work = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var scheduled: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_events)
        store = DailyPlateStore(this)
        list = findViewById(R.id.recordList)
        count = findViewById(R.id.recordCount)
        search = findViewById(R.id.searchPlate)
        todayOnly = findViewById(R.id.todayOnly)
        findViewById<Button>(R.id.backToLive).setOnClickListener { finish() }
        search.addTextChangedListener { refreshDelayed() }
        todayOnly.setOnCheckedChangeListener { _, _ -> refreshDelayed() }
        refresh()
    }

    private fun refreshDelayed() {
        scheduled?.let(handler::removeCallbacks)
        val runnable = Runnable { refresh() }
        scheduled = runnable
        handler.postDelayed(runnable, 250)
    }

    private fun refresh() {
        val ticket = ++generation
        val query = search.text.toString()
        val day = if (todayOnly.isChecked) DailyPlateStore.localDay(System.currentTimeMillis()) else null
        work.execute {
            try {
                val rows = store.listRecords(day, query)
                runOnUiThread {
                    if (ticket == generation && !isDestroyed) render(rows, ticket)
                }
            } catch (e: Exception) {
                runOnUiThread { if (!isDestroyed) count.text = "紀錄讀取失敗：${e.message}" }
            }
        }
    }

    private fun render(rows: List<CaptureRecord>, ticket: Int) {
        list.removeAllViews()
        count.text = if (rows.isEmpty()) "沒有符合的紀錄。返回即時畫面，對準車牌即可自動存照。"
            else "${rows.size} 筆紀錄 · 最多顯示最近 200 筆"
        rows.forEach { row -> list.addView(card(row, ticket)) }
    }

    private fun card(row: CaptureRecord, ticket: Int): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.panel_surface)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF293C45.toInt())
            contentDescription = "${row.text} 的存檔照片"
        }
        root.addView(image, LinearLayout.LayoutParams(dp(112), dp(80)))
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        root.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        details.addView(TextView(this).apply {
            text = row.text
            textSize = 20f; setTextColor(0xFFF4F2EB.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
        })
        val time = if (row.capturedAt > 0) SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.TAIWAN)
            .format(Date(row.capturedAt)) else "${row.day} · 舊版紀錄"
        val subtitle = TextView(this).apply {
            text = "$time  ·  ${if (row.deleted) "已刪除（今日仍去重）" else "已存檔"}"
            textSize = 13f; setTextColor(0xFF9AB0B8.toInt())
        }
        details.addView(subtitle)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        details.addView(actions)
        if (!row.deleted && row.uri != null) {
            addAction(actions, "查看") { openPhoto(row.uri) }
            addAction(actions, "分享") { sharePhoto(row.uri) }
            addAction(actions, "刪除") { confirmDelete(row) }
            work.execute {
                val thumb = runCatching { contentResolver.loadThumbnail(row.uri, Size(240, 170), null) }.getOrNull()
                runOnUiThread {
                    if (ticket == generation && !isDestroyed && image.parent != null) {
                        if (thumb != null) image.setImageBitmap(thumb)
                        else {
                            image.contentDescription = "照片已移除，但今日車牌紀錄仍保留"
                            subtitle.text = "$time · 照片已移除（今日仍去重）"
                        }
                    } else thumb?.recycle()
                }
            }
        } else {
            image.contentDescription = "紀錄已刪除；當天仍不重拍"
        }
        return root
    }

    private fun addAction(row: LinearLayout, label: String, click: () -> Unit) {
        row.addView(Button(this).apply {
            text = label; textSize = 13f; isAllCaps = false
            minWidth = 0; minimumWidth = 0; minHeight = dp(42)
            setOnClickListener { click() }
        }, LinearLayout.LayoutParams(-2, dp(44)))
    }

    private fun openPhoto(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: ActivityNotFoundException) {
            count.text = "找不到可以開啟照片的 App"
        } catch (e: SecurityException) {
            count.text = "照片無法讀取，可能已從相簿移除"
        }
    }

    private fun sharePhoto(uri: Uri) {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, "車牌照片", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "分享車牌照片"))
        } catch (e: Exception) {
            count.text = "分享失敗：${e.message}"
        }
    }

    private fun confirmDelete(row: CaptureRecord) {
        AlertDialog.Builder(this)
            .setTitle("刪除這張照片？")
            .setMessage("若同張照片有其他車牌紀錄，照片會保留到最後一筆刪除。當天此車牌仍不會自動重拍。")
            .setNegativeButton("取消", null)
            .setPositiveButton("刪除") { _, _ ->
                work.execute {
                    val error = runCatching { store.deletePhoto(row.day, row.plate) }.exceptionOrNull()
                    runOnUiThread {
                        if (!isDestroyed) {
                            if (error != null) count.text = "刪除失敗：${error.message}"
                            else refresh()
                        }
                    }
                }
            }.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scheduled?.let(handler::removeCallbacks)
        work.execute { store.close() }
        work.shutdown()
        super.onDestroy()
    }
}
