package com.mocklocate.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mocklocate.app.databinding.ActivityMainBinding
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var map: MapView
    private var pin: Marker? = null
    private var myLocationOverlay: MyLocationNewOverlay? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { enableMyLocationIfGranted() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        map = binding.map
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)

        // Tap anywhere on the map to drop the pin
        map.overlays.add(object : Overlay() {
            override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
                val point = mapView.projection
                    .fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
                setPin(point.latitude, point.longitude)
                return true
            }
        })

        // Restore last pin (or default to San Francisco)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val lat = prefs.getFloat(KEY_LAT, 37.7749f).toDouble()
        val lng = prefs.getFloat(KEY_LNG, -122.4194f).toDouble()
        val hasPin = prefs.getBoolean(KEY_HAS_PIN, false)
        val startPoint = GeoPoint(lat, lng)
        map.controller.setCenter(startPoint)
        if (hasPin) setPin(lat, lng) else updateCoordsLabel(null)

        binding.btnStart.setOnClickListener { onStartClicked() }
        binding.btnStop.setOnClickListener { MockLocationService.stop(this) }
        binding.btnMyLocation.setOnClickListener { centerOnMyLocation() }

        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        myLocationOverlay?.enableMyLocation()
        refreshUi()

        // Surface injection failures (e.g. app not chosen as mock location app)
        MockLocationService.lastError?.let {
            MockLocationService.lastError = null
            showMockNotSelectedDialog()
        }
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        myLocationOverlay?.disableMyLocation()
    }

    private fun setPin(lat: Double, lng: Double) {
        val point = GeoPoint(lat, lng)
        val marker = pin ?: Marker(map).also {
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            map.overlays.add(it)
            pin = it
        }
        marker.position = point
        map.controller.animateTo(point)
        map.invalidate()
        updateCoordsLabel(point)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putFloat(KEY_LAT, lat.toFloat())
            .putFloat(KEY_LNG, lng.toFloat())
            .putBoolean(KEY_HAS_PIN, true)
            .apply()
    }

    private fun updateCoordsLabel(point: GeoPoint?) {
        binding.tvCoords.text = if (point == null) {
            getString(R.string.tap_hint)
        } else {
            getString(R.string.coords_format, point.latitude, point.longitude)
        }
    }

    private fun onStartClicked() {
        val marker = pin
        if (marker == null) {
            Toast.makeText(this, R.string.need_pin, Toast.LENGTH_SHORT).show()
            return
        }
        MockLocationService.start(this, marker.position.latitude, marker.position.longitude)
        // UI refresh happens in onResume; do a quick optimistic update too
        binding.root.postDelayed({ refreshUi() }, 600)
    }

    private fun refreshUi() {
        val running = MockLocationService.isRunning
        binding.btnStart.isEnabled = !running
        binding.btnStop.isEnabled = running
        binding.tvStatus.text = if (running) {
            getString(
                R.string.mocking_active_coords,
                MockLocationService.currentLat,
                MockLocationService.currentLng
            )
        } else {
            getString(R.string.idle_status)
        }
    }

    private fun showMockNotSelectedDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.mock_not_selected_title)
            .setMessage(R.string.mock_not_selected_msg)
            .setPositiveButton(R.string.open_dev_settings) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
        else enableMyLocationIfGranted()
    }

    private fun enableMyLocationIfGranted() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        if (myLocationOverlay == null) {
            myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
            map.overlays.add(myLocationOverlay)
        }
        myLocationOverlay?.enableMyLocation()
    }

    private fun centerOnMyLocation() {
        val overlay = myLocationOverlay
        val loc = overlay?.myLocation
        if (overlay != null && loc != null) {
            map.controller.animateTo(loc)
        } else {
            Toast.makeText(this, R.string.no_location_yet, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val PREFS = "mocklocate"
        private const val KEY_LAT = "pin_lat"
        private const val KEY_LNG = "pin_lng"
        private const val KEY_HAS_PIN = "has_pin"
    }
}
