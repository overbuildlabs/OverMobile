// typecheck stub, used only with --no-compose: stands in for the Compose
// runtime (LogManager uses mutableStateListOf) and for the Compose activities
// that are skipped but referenced from non-Compose code.
package androidx.compose.runtime

fun <T> mutableStateListOf(): MutableList<T> = java.util.ArrayList()
