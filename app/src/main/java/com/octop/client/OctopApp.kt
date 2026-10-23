package com.octop.client

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.octop.client.data.network.ApiClient
import com.octop.client.util.DiagnosticLogger

class OctopApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        instance = this
        DiagnosticLogger.init(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient { ApiClient.okHttpClient }
            .crossfade(true)
            .build()
    }

    companion object {
        lateinit var instance: OctopApp
            private set
    }
}
