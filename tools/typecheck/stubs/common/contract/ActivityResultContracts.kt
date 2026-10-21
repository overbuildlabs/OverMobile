// typecheck stub: see AndroidxActivityResult.kt.
package androidx.activity.result.contract

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResult

abstract class ActivityResultContract<I, O> {
    abstract fun createIntent(context: Context, input: I): Intent
    abstract fun parseResult(resultCode: Int, intent: Intent?): O
    open fun getSynchronousResult(context: Context, input: I): SynchronousResult<O>? = null
    class SynchronousResult<T>(val value: T)
}

class ActivityResultContracts private constructor() {
    open class StartActivityForResult : ActivityResultContract<Intent, ActivityResult>() {
        override fun createIntent(context: Context, input: Intent): Intent = input
        override fun parseResult(resultCode: Int, intent: Intent?): ActivityResult = ActivityResult(resultCode, intent)
    }
    class RequestPermission : ActivityResultContract<String, Boolean>() {
        override fun createIntent(context: Context, input: String): Intent = TODO()
        override fun parseResult(resultCode: Int, intent: Intent?): Boolean = false
    }
    class RequestMultiplePermissions : ActivityResultContract<Array<String>, Map<String, Boolean>>() {
        override fun createIntent(context: Context, input: Array<String>): Intent = TODO()
        override fun parseResult(resultCode: Int, intent: Intent?): Map<String, Boolean> = emptyMap()
    }
    open class GetContent : ActivityResultContract<String, Uri?>() {
        override fun createIntent(context: Context, input: String): Intent = TODO()
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = null
    }
    open class CreateDocument(val mimeType: String) : ActivityResultContract<String, Uri?>() {
        override fun createIntent(context: Context, input: String): Intent = TODO()
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = null
    }
}
