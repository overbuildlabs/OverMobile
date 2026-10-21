// typecheck stub: androidx.activity ComponentActivity + activity-compose
// setContent (activity / activity-compose 1.8.2, Google Maven only).
// The real ComponentActivity implements LifecycleOwner,
// ViewModelStoreOwner, OnBackPressedDispatcherOwner, ...; only LifecycleOwner
// is declared here, so code using lifecycleScope / onBackPressedDispatcher etc.
// will fail with "Unresolved reference" until it is added.
package androidx.activity

import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract

open class ComponentActivity : android.app.Activity(), ActivityResultCaller, androidx.lifecycle.LifecycleOwner {
    override val lifecycle: androidx.lifecycle.Lifecycle get() = TODO()

    final override fun <I, O> registerForActivityResult(
        contract: ActivityResultContract<I, O>,
        callback: ActivityResultCallback<O>
    ): ActivityResultLauncher<I> = TODO()
}
