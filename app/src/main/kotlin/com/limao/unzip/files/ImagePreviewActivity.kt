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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101010.toInt())
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply {
            setBackgroundColor(0xE6222222.toInt())
            setPadding(24, 32, 24, 32)
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(TextView(this).apply {
            text = "‹"; textSize = 26f; setTextColor(0xFFDDDDDD.toInt()); setPadding(0, 0, 28, 0)
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = file.name; textSize = 14f; setTextColor(0xFFEEEEEE.toInt())
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(TextView(this).apply {
            text = "${file.length() / 1024} KB"
            textSize = 12f; setTextColor(0xFF999999.toInt())
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF000000.toInt())
        }
        root.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val meta = TextView(this).apply {
            textSize = 12f; setTextColor(0xFF888888.toInt())
            setBackgroundColor(0xFF222222.toInt())
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
