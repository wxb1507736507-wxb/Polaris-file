package com.limao.unzip.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.limao.unzip.R
import java.io.File

/** 内建图片查看器：全屏显示，按屏宽采样解码（大图不 OOM），双击返回。 */
class ImagePreviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        val file = File(path)
        // 背景跟随应用主题：日间白底深字，夜间黑底浅字
        val night = com.limao.unzip.UiTheme.isNight()
        val bgRoot = if (night) 0xFF101010.toInt() else 0xFFF5F6F8.toInt()
        val bgBar = if (night) 0xE6222222.toInt() else 0xE6FFFFFF.toInt()
        val fgPrimary = if (night) 0xFFEEEEEE.toInt() else 0xFF1D1B20.toInt()
        val fgSecondary = if (night) 0xFFDDDDDD.toInt() else 0xFF49454F.toInt()
        val fgMuted = if (night) 0xFF999999.toInt() else 0xFF666666.toInt()
        val bgImageArea = if (night) 0xFF000000.toInt() else 0xFFE8EAED.toInt()
        val bgMeta = if (night) 0xFF222222.toInt() else 0xFFF0F0F0.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgRoot)
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply {
            setBackgroundColor(bgBar)
            setPadding(24, 32, 24, 32)
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(TextView(this).apply {
            text = "‹"; textSize = 26f; setTextColor(fgSecondary); setPadding(0, 0, 28, 0)
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = file.name; textSize = 14f; setTextColor(fgPrimary)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(TextView(this).apply {
            text = "${file.length() / 1024} KB"
            textSize = 12f; setTextColor(fgMuted)
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(bgImageArea)
        }
        root.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val meta = TextView(this).apply {
            textSize = 12f; setTextColor(fgMuted)
            setBackgroundColor(bgMeta)
            setPadding(20, 10, 20, 14)
            gravity = Gravity.CENTER
        }
        root.addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        val targetW = resources.displayMetrics.widthPixels
        Thread {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = runCatching { BitmapFactory.decodeFile(path, opts) }.getOrNull()
            runOnUiThread {
                if (bmp != null) {
                    img.setImageBitmap(bmp)
                    meta.text = "${bounds.outWidth}×${bounds.outHeight} · 采样 $sample"
                } else {
                    meta.text = getString(R.string.toast_cant_open, file.name)
                }
            }
        }.start()
    }

    companion object {
        const val EXTRA_PATH = "path"
    }
}
