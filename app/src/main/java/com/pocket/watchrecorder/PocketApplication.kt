package com.pocket.watchrecorder

import android.app.Application
import com.pocket.watchrecorder.network.ApiKeyStore
import com.pocket.watchrecorder.network.PocketCredentials

/**
 * Loads the stored Pocket key before anything can need it.
 *
 * This has to happen here rather than in the Activity: [UploadWorker] runs in
 * the same process with no UI at all, and it needs the credential just as much
 * as the screen does.
 *
 * One AES-GCM decrypt against the Keystore, which is why it is acceptable on
 * the main thread at startup.
 */
class PocketApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PocketCredentials.bind(ApiKeyStore(this))
    }
}
