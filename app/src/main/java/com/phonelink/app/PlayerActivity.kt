package com.phonelink.app

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import java.net.URL

class PlayerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lan.start(applicationContext)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val url = intent.getStringExtra("url") ?: return finish()
        val name = intent.getStringExtra("name") ?: ""
        title = name
        val remote = url.startsWith("http")
        if (remote) Lan.bindProcess(Uri.parse(url).host)

        if (extOf(name) in IMAGE) {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.setBackgroundColor(Color.BLACK)
            setContentView(iv)
            Thread {
                try {
                    val ins = if (remote) Lan.open(URL(url)).getInputStream() else URL(url).openStream()
                    val bytes = ins.use { it.readBytes() }
                    val o = BitmapFactory.Options()
                    o.inJustDecodeBounds = true
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    var s = 1
                    while (o.outWidth / s > 2048 || o.outHeight / s > 2048) s *= 2
                    val o2 = BitmapFactory.Options()
                    o2.inSampleSize = s
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
                    runOnUiThread { iv.setImageBitmap(bmp) }
                } catch (e: Exception) {
                    runOnUiThread { Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }.start()
        } else {
            val vv = VideoView(this)
            val mc = MediaController(this)
            mc.setAnchorView(vv)
            vv.setMediaController(mc)
            val frame = FrameLayout(this)
            frame.setBackgroundColor(Color.BLACK)
            frame.addView(
                vv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER
                )
            )
            setContentView(frame)
            vv.setOnPreparedListener { it.start() }
            vv.setOnErrorListener { _, _, _ ->
                Toast.makeText(this, "Cannot play this file", Toast.LENGTH_SHORT).show()
                true
            }
            vv.setVideoURI(Uri.parse(url))
        }
    }

    override fun onDestroy() {
        Lan.bindProcess(null)
        super.onDestroy()
    }
}
