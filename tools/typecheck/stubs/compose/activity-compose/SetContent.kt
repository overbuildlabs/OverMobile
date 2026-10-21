// typecheck stub: androidx.activity.compose (activity-compose 1.8.2).
package androidx.activity.compose

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext

fun ComponentActivity.setContent(
    parent: CompositionContext? = null,
    content: @Composable () -> Unit
) {}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {}
