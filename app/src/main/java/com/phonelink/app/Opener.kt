package com.phonelink.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/** "Open with": Android shows every installed app that can handle that type of file. */
object Opener {
    fun mime(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extOf(name)) ?: "*/*"

    /** Streams a remote video / audio straight into the app the user picks. */
    fun openUrl(a: Activity, url: String, name: String) {
        val v = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), mime(name))
        a.startActivity(Intent.createChooser(v, "Open with"))
    }

    /** Opens a file stored on this phone in the app the user picks. */
    fun openFile(a: Activity, f: File, name: String) {
        val uri = FileProvider.getUriForFile(a, a.packageName + ".files", f)
        val v = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime(name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(Intent.createChooser(v, "Open with"))
    }
}
