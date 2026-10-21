// typecheck stub: androidx.activity result API (activity 1.8.2, Google Maven
// only). The real zxing-android-embedded jar (com.journeyapps ScanContract)
// extends ActivityResultContract, so these names/signatures must match the
// real library exactly.
package androidx.activity.result

import androidx.activity.result.contract.ActivityResultContract

fun interface ActivityResultCallback<O> {
    fun onActivityResult(result: O)
}

abstract class ActivityResultLauncher<I> {
    open fun launch(input: I) {}
    abstract fun unregister()
    abstract fun getContract(): ActivityResultContract<I, *>
}

interface ActivityResultCaller {
    fun <I, O> registerForActivityResult(
        contract: ActivityResultContract<I, O>,
        callback: ActivityResultCallback<O>
    ): ActivityResultLauncher<I>
}

class ActivityResult(val resultCode: Int, val data: android.content.Intent?)
