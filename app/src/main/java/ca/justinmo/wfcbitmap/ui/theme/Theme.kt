package ca.justinmo.wfcbitmap.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The app's only colour scheme: black surfaces, white ink.
 *
 * There is no light scheme and no dynamic colour, whatever the system is set to. The editor is a drawing on a
 * black surface, and the drawing's own colours are the point of it -- a Material accent, or a palette taken
 * from the wallpaper, would put a second colour on screen beside the one the person chose.
 *
 * Every role is one of the two colours, so nothing can fall back to Material's defaults and arrive purple: a
 * surface is black, anything printed on a surface is white, and a filled control is white with black ink.
 */
private val EditorColorScheme = darkColorScheme(
  // Accents. A filled control is white, so its ink is black.
  primary = Color.White,
  onPrimary = Color.Black,
  primaryContainer = Color.Black,
  onPrimaryContainer = Color.White,
  inversePrimary = Color.Black,
  secondary = Color.White,
  onSecondary = Color.Black,
  secondaryContainer = Color.Black,
  onSecondaryContainer = Color.White,
  tertiary = Color.White,
  onTertiary = Color.Black,
  tertiaryContainer = Color.Black,
  onTertiaryContainer = Color.White,
  // The surfaces, and what is written on them.
  background = Color.Black,
  onBackground = Color.White,
  surface = Color.Black,
  onSurface = Color.White,
  surfaceVariant = Color.Black,
  onSurfaceVariant = Color.White,
  surfaceTint = Color.White,
  inverseSurface = Color.White,
  inverseOnSurface = Color.Black,
  // A failure is reported in the same ink as everything else: this app has no red.
  error = Color.White,
  onError = Color.Black,
  errorContainer = Color.Black,
  onErrorContainer = Color.White,
  // The lines that divide one black surface from the next are white, at the thickness Material draws them.
  outline = Color.White,
  outlineVariant = Color.White,
  scrim = Color.Black,
  // Material's container steps are a light theme's way of separating surfaces; here there is one surface.
  surfaceBright = Color.Black,
  surfaceDim = Color.Black,
  surfaceContainer = Color.Black,
  surfaceContainerHigh = Color.Black,
  surfaceContainerHighest = Color.Black,
  surfaceContainerLow = Color.Black,
  surfaceContainerLowest = Color.Black,
)

/**
 * Wraps the editor in its theme.
 *
 * There is nothing to choose: the app is dark and black-and-white however the system is set, so the theme takes
 * no arguments and cannot be asked for a light one.
 */
@Composable
fun WFCBitmapTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = EditorColorScheme,
    typography = Typography,
    content = content,
  )
}