package com.pocket.watchrecorder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.pocket.watchrecorder.ui.PocketRecorderApp

/**
 * The whole Activity.
 *
 * Everything else that used to live in this file — the ViewModel, the theme,
 * six screens and the record button — now sits in [com.pocket.watchrecorder.ui]
 * and [RecorderViewModel]. Capture itself belongs to
 * [com.pocket.watchrecorder.audio.RecordingService], which is why this class
 * has no lifecycle handling left to do.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The splash theme and icon were already in the project but nothing
        // installed them, so the app started on a blank frame.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent { PocketRecorderApp() }
    }
}
