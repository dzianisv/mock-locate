package com.mocklocate.app

import android.app.Application
import org.osmdroid.config.Configuration

class MockLocateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // osmdroid: load tile cache config, identify ourselves to the tile servers
        Configuration.getInstance().load(
            this,
            getSharedPreferences("osmdroid", MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = packageName
    }
}
