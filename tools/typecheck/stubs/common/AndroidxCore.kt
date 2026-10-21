// typecheck stub: androidx.core (core-ktx 1.12.0) is only published on Google
// Maven, which cloud sessions cannot reach. Only the members the app uses are
// declared, with the real signatures. Add more here when the app starts using
// them (a call to a missing member shows up as "Unresolved reference").
package androidx.core.content

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

open class ContextCompat protected constructor() {
    companion object {
        const val RECEIVER_VISIBLE_TO_INSTANT_APPS: Int = 0x1
        const val RECEIVER_EXPORTED: Int = 0x2
        const val RECEIVER_NOT_EXPORTED: Int = 0x4

        @JvmStatic fun checkSelfPermission(context: Context, permission: String): Int = 0
        @JvmStatic fun startForegroundService(context: Context, intent: Intent) {}
        @JvmStatic fun getColor(context: Context, id: Int): Int = 0
        @JvmStatic fun getDrawable(context: Context, id: Int): android.graphics.drawable.Drawable? = null
        @JvmStatic fun <T : Any> getSystemService(context: Context, serviceClass: Class<T>): T? = null
        @JvmStatic fun getMainExecutor(context: Context): java.util.concurrent.Executor = TODO()
        @JvmStatic fun registerReceiver(
            context: Context,
            receiver: android.content.BroadcastReceiver?,
            filter: android.content.IntentFilter,
            flags: Int
        ): Intent? = null
    }
}

open class FileProvider : android.content.ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        @JvmStatic fun getUriForFile(context: Context, authority: String, file: File): Uri = TODO()
        @JvmStatic fun getUriForFile(context: Context, authority: String, file: File, displayName: String): Uri = TODO()
    }
}
