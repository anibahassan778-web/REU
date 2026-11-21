package com.example

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun `verify REU app name in English and Arabic resources`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals("REU", context.getString(R.string.app_name))

    val arConfig = Configuration(context.resources.configuration).apply {
      setLocale(Locale.forLanguageTag("ar"))
    }
    val arContext = context.createConfigurationContext(arConfig)
    assertEquals("REU", arContext.getString(R.string.app_name))
    assertEquals("لوحة التحكم", arContext.getString(R.string.nav_dashboard))
  }

  @Test
  fun `verify navigation across core screens and Arabic RTL switch`() {
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("dashboard_screen").assertIsDisplayed()

    composeRule.onNodeWithTag("bottom_nav_import").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("importer_screen").assertIsDisplayed()

    composeRule.onNodeWithTag("bottom_nav_analysis").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("analysis_screen").assertIsDisplayed()

    composeRule.onNodeWithTag("topbar_settings_button").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()

    composeRule.onNodeWithTag("lang_arabic").assertIsDisplayed().performClick()
    composeRule.waitUntil(timeoutMillis = 5000) {
      runCatching {
        composeRule.onNodeWithTag("topbar_title").assertTextEquals("الإعدادات")
        composeRule.onNodeWithText("اللغة والاتجاه (RTL / LTR)").assertIsDisplayed()
        true
      }.getOrDefault(false)
    }
  }
}
