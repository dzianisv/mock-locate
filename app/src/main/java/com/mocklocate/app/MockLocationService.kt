package com.mocklocate.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Foreground service that keeps injecting a fixed mock location via the
 * platform test-provider API, so it survives the app being backgrounded.
 *
 * Requires: the app selected as the mock location app in Developer options
 * (Settings -> System -> Developer options -> Select mock location app).
 */
class MockLocationService : Service() {

    private lateinit var locationManager: LocationManager
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var injectTask: ScheduledFuture<*>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMocking()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
                val lng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN)
                if (lat.isNaN() || lng.isNaN()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startMocking(lat, lng)
                return START_STICKY
            }
        }
        return START_STICKY
    }

    private fun startMocking(lat: Double, lng: Double) {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        createNotificationChannel()
        val notification = buildNotification(lat, lng)
        // Android 14+: foregroundServiceType="location" is declared in the manifest
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            addTestProvider(LocationManager.GPS_PROVIDER)
            addTestProvider(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            lastError = e.message
            Toast.makeText(
                this,
                getString(R.string.mock_not_selected_title),
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return
        }

        isRunning = true
        currentLat = lat
        currentLng = lng
        lastError = null

        injectTask?.cancel(false)
        injectTask = executor.scheduleAtFixedRate(
            { inject(lat, lng) },
            0, INJECT_INTERVAL_MS, TimeUnit.MILLISECONDS
        )
    }

    private fun addTestProvider(provider: String) {
        try {
            locationManager.removeTestProvider(provider)
        } catch (_: IllegalArgumentException) {
            // not previously added — fine
        }
        locationManager.addTestProvider(
            provider,
            /* requiresNetwork = */ false,
            /* requiresSatellite = */ false,
            /* requiresCell = */ false,
            /* hasMonetaryCost = */ false,
            /* supportsAltitude = */ true,
            /* supportsSpeed = */ true,
            /* supportsBearing = */ true,
            /* powerRequirement = */ Criteria.POWER_LOW,
            /* accuracy = */ Criteria.ACCURACY_FINE
        )
        locationManager.setTestProviderEnabled(provider, true)
    }

    private fun inject(lat: Double, lng: Double) {
        val now = System.currentTimeMillis()
        val elapsedNanos = SystemClock.elapsedRealtimeNanos()
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                val location = Location(provider).apply {
                    latitude = lat
                    longitude = lng
                    altitude = 0.0
                    accuracy = 5f
                    speed = 0f
                    bearing = 0f
                    time = now
                    setElapsedRealtimeNanos(elapsedNanos)
                }
                locationManager.setTestProviderLocation(provider, location)
            } catch (_: Exception) {
                // provider torn down (e.g. service stopping) — ignore
            }
        }
    }

    private fun stopMocking() {
        injectTask?.cancel(false)
        injectTask = null
        if (::locationManager.isInitialized) {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    locationManager.removeTestProvider(provider)
                } catch (_: Exception) {
                }
            }
        }
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopMocking()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(lat: Double, lng: Double): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.mocking_active))
            .setContentText(getString(R.string.service_notification, lat, lng))
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.stop_action),
                stopIntent
            )
            .build()
    }

    companion object {
        const val ACTION_START = "com.mocklocate.app.ACTION_START"
        const val ACTION_STOP = "com.mocklocate.app.ACTION_STOP"
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"

        private const val CHANNEL_ID = "mock_location"
        private const val NOTIFICATION_ID = 42
        private const val INJECT_INTERVAL_MS = 2000L

        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var currentLat: Double = Double.NaN
            private set
        @Volatile var currentLng: Double = Double.NaN
            private set
        /** Set when injection fails (e.g. app not selected as mock location app). Read-and-clear from UI. */
        @Volatile var lastError: String? = null

        fun start(context: Context, lat: Double, lng: Double) {
            val intent = Intent(context, MockLocationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LAT, lat)
                .putExtra(EXTRA_LNG, lng)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MockLocationService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
