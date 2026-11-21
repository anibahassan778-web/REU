package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.reu.features.ui.LocalMonoFont
import com.reu.features.ui.ReuDarkColorScheme

@Composable
fun ReuTheme(
  isArabic: Boolean = false,
  content: @Composable () -> Unit,
) {
  CompositionLocalProvider(LocalMonoFont provides JetBrainsMonoFontFamily) {
    MaterialTheme(
      colorScheme = ReuDarkColorScheme,
      typography = createReuTypography(isArabic),
      content = content,
    )
  }
}
