package com.phonelink.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : Activity() {
    private lateinit var ad: ArrayAdapter<String>
    private var recs: List<History.Rec> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "History"

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val clear = Button(this)
        clear.text = "Clear all"
        clear.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Clear the whole history?")
                .setPositiveButton("Clear") { _, _ ->
                    History.clear(this)
                    reload()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        ad = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        val lv = ListView(this)
        lv.adapter = ad
        lv.isFastScrollEnabled = true
        lv.setOnItemClickListener { _, _, i, _ -> if (i < recs.size) pick(i) }

        col.addView(clear)
        col.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(col)
        reload()
    }

    private fun reload() {
        recs = History.all(this)
        ad.setNotifyOnChange(false)
        ad.clear()
        if (recs.isEmpty()) ad.add("Nothing yet") else ad.addAll(recs.map { fmt(it) })
        ad.notifyDataSetChanged()
    }

    private fun fmt(r: History.Rec): String {
        val tag = when (r.kind) {
            "down" -> "Downloaded"
            "up" -> "Sent"
            "play" -> "Played"
            "recv" -> "Received"
            "sent" -> "Shared"
            "pair" -> "Phone allowed"
            "conn" -> "Phone connected"
            else -> r.kind
        }
        val state = if (r.ok) "" else " (failed)"
        val parts = ArrayList<String>()
        if (r.size > 0) parts.add(fmtSize(r.size))
        if (r.speed > 0) parts.add(fmtSpeed(r.speed))
        if (r.peer.isNotEmpty()) parts.add(r.peer)
        parts.add(SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(r.at)))
        return "$tag$state: ${r.name}\n" + parts.joinToString("  |  ")
    }

    private fun pick(i: Int) {
        val r = recs[i]
        val e = extOf(r.name)
        val canOpen = r.path.isNotEmpty() && File(r.path).exists() && (e in VIDEO || e in AUDIO || e in IMAGE)
        val opts = if (canOpen) arrayOf("Open", "Remove from list") else arrayOf("Remove from list")
        AlertDialog.Builder(this).setTitle(r.name).setItems(opts) { _, w ->
            if (canOpen && w == 0) {
                startActivity(
                    Intent(this, PlayerActivity::class.java)
                        .putExtra("url", Uri.fromFile(File(r.path)).toString())
                        .putExtra("name", r.name)
                )
            } else {
                History.remove(this, i)
                reload()
            }
        }.show()
    }
}
