package com.quartz.notes

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quartz.notes.data.ThemeMode
import com.quartz.notes.ui.QuartzNavHost
import com.quartz.notes.ui.editor.FontResolver
import com.quartz.notes.ui.editor.LocalFontResolver
import com.quartz.notes.ui.theme.QuartzTheme

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        (application as QuartzApp).container.sync.setForeground(true)
    }

    override fun onStop() {
        (application as QuartzApp).container.sync.setForeground(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as QuartzApp).container

        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val fonts by container.fonts.fonts.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val current = settings

            if (current == null) {
                // Settings load in a few milliseconds; avoid flashing the wrong theme meanwhile.
                Box(Modifier.fillMaxSize().background(if (systemDark) Color(0xFF101013) else Color(0xFFEFEFF1)))
            } else {
                val dark = when (current.themeMode) {
                    ThemeMode.SYSTEM -> systemDark
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
                DisposableEffect(dark) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark },
                        navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark },
                    )
                    onDispose {}
                }
                val resolver = remember(fonts) { FontResolver(fonts) }
                CompositionLocalProvider(
                    LocalAppContainer provides container,
                    LocalFontResolver provides resolver,
                ) {
                    QuartzTheme(settings = current, dark = dark) {
                        QuartzNavHost()
                    }
                }
            }
        }
    }
}
