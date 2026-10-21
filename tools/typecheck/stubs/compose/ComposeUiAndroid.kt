// typecheck stub: Android-only parts of androidx.compose.ui (ui-android).
// The type-check compiles against the JetBrains Compose *desktop* jars from
// Maven Central (same androidx.compose.* API surface, Google Maven is not
// reachable), which lack the Android-specific APIs declared here.
@file:JvmName("ComposeUiAndroidStubs")
package androidx.compose.ui.platform

import android.content.Context
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

val LocalContext: ProvidableCompositionLocal<Context> = staticCompositionLocalOf { error("stub") }
val LocalView: ProvidableCompositionLocal<android.view.View> = staticCompositionLocalOf { error("stub") }
val LocalLifecycleOwner: ProvidableCompositionLocal<androidx.lifecycle.LifecycleOwner> = staticCompositionLocalOf { error("stub") }
val LocalConfiguration: ProvidableCompositionLocal<android.content.res.Configuration> = staticCompositionLocalOf { error("stub") }
