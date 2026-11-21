package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.reu.features.state.ReuMainViewModel

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val app = application as ReuApplication
    val viewModel = ViewModelProvider(this, app.viewModelFactory())[ReuMainViewModel::class.java]
    setContent {
      ReuNavShell(viewModel = viewModel)
    }
  }
}
