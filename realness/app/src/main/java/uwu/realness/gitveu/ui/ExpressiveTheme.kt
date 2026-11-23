package uwu.realness.gitveu.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Fallback Material 3 Expressive tonal palettes for Android 8-11 (Android 12+ uses Material You dynamic colors).
 */
private val ExpressiveDarkColorScheme = darkColorScheme(
    primary = Color(0xFFF3C65F),
    onPrimary = Color(0xFF3E2E00),
    primaryContainer = Color(0xFF5A4300),
    onPrimaryContainer = Color(0xFFFFE096),
    secondary = Color(0xFFD6C5A0),
    onSecondary = Color(0xFF392F15),
    secondaryContainer = Color(0xFF51462A),
    onSecondaryContainer = Color(0xFFF3E1BB),
    tertiary = Color(0xFFAECFB0),
    onTertiary = Color(0xFF1B3620),
    tertiaryContainer = Color(0xFF324D35),
    onTertiaryContainer = Color(0xFFCAEBCB),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF131310),
    onBackground = Color(0xFFE7E2D9),
    surface = Color(0xFF131310),
    onSurface = Color(0xFFE7E2D9),
    surfaceVariant = Color(0xFF4B4639),
    onSurfaceVariant = Color(0xFFCDC6B4),
    outline = Color(0xFF969080),
    outlineVariant = Color(0xFF4B4639),
    surfaceContainerLowest = Color(0xFF0E0E0B),
    surfaceContainerLow = Color(0xFF1B1B17),
    surfaceContainer = Color(0xFF201F1B),
    surfaceContainerHigh = Color(0xFF2A2925),
    surfaceContainerHighest = Color(0xFF35342F)
)

private val ExpressiveLightColorScheme = lightColorScheme(
    primary = Color(0xFF775A00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE096),
    onPrimaryContainer = Color(0xFF251A00),
    secondary = Color(0xFF695D3F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF2E1BB),
    onSecondaryContainer = Color(0xFF231B04),
    tertiary = Color(0xFF48654B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCAEBCB),
    onTertiaryContainer = Color(0xFF05210D),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFFBF4),
    onBackground = Color(0xFF1E1B13),
    surface = Color(0xFFFFFBF4),
    onSurface = Color(0xFF1E1B13),
    surfaceVariant = Color(0xFFEAE2D0),
    onSurfaceVariant = Color(0xFF4B4639),
    outline = Color(0xFF7D7667),
    outlineVariant = Color(0xFFCDC6B4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAF3E6),
    surfaceContainer = Color(0xFFF4EDE0),
    surfaceContainerHigh = Color(0xFFEEE7DA),
    surfaceContainerHighest = Color(0xFFE8E1D4)
)

private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val ExpressiveTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.25).sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.15).sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

@Composable
fun RealnessExpressiveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> ExpressiveDarkColorScheme
        else -> ExpressiveLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = ExpressiveShapes,
        typography = ExpressiveTypography,
        content = content
    )
}
