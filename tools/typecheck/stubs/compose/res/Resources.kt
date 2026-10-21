// typecheck stub: Android resource accessors from androidx.compose.ui.res
// (ui-android). See ComposeUiAndroid.kt for why these are stubbed.
@file:JvmName("ComposeResAndroidStubs")
package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter

@Composable fun painterResource(id: Int): Painter = TODO()
@Composable fun stringResource(id: Int): String = TODO()
@Composable fun stringResource(id: Int, vararg formatArgs: Any): String = TODO()
@Composable fun colorResource(id: Int): Color = TODO()
@Composable fun dimensionResource(id: Int): androidx.compose.ui.unit.Dp = TODO()
