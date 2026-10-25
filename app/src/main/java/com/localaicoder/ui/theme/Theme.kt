package com.localaicoder.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(primary = Color(0xFF3B5BDB), secondary = Color(0xFF5C7CFA))
private val Dark = darkColorScheme(primary = Color(0xFF91A7FF), secondary = Color(0xFFBAC8FF))

@Composable
fun LocalAICoderTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        darkTheme -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
