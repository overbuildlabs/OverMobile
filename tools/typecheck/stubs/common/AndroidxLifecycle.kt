// typecheck stub: androidx.lifecycle (lifecycle-common/runtime 2.7.0, Google
// Maven only). Real 2.7.0 signatures for the core types; ViewModel,
// lifecycleScope, LifecycleService etc. are not declared.
package androidx.lifecycle

interface LifecycleObserver

fun interface LifecycleEventObserver : LifecycleObserver {
    fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event)
}

interface DefaultLifecycleObserver : LifecycleObserver {
    fun onCreate(owner: LifecycleOwner) {}
    fun onStart(owner: LifecycleOwner) {}
    fun onResume(owner: LifecycleOwner) {}
    fun onPause(owner: LifecycleOwner) {}
    fun onStop(owner: LifecycleOwner) {}
    fun onDestroy(owner: LifecycleOwner) {}
}

interface LifecycleOwner {
    val lifecycle: Lifecycle
}

abstract class Lifecycle {
    abstract fun addObserver(observer: LifecycleObserver)
    abstract fun removeObserver(observer: LifecycleObserver)
    abstract val currentState: State

    enum class Event { ON_CREATE, ON_START, ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY, ON_ANY }

    enum class State {
        DESTROYED, INITIALIZED, CREATED, STARTED, RESUMED;
        fun isAtLeast(state: State): Boolean = compareTo(state) >= 0
    }
}
