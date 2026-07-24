package com.nankai.smartcane.navigation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nankai.smartcane.MainActivity
import com.nankai.smartcane.data.network.LocationUploadDto
import com.nankai.smartcane.data.network.SmartCaneApiClient
import java.util.concurrent.Executors

class NavigationLocationService : Service(), LocationListener {
    private var manager: LocationManager? = null
    private var listening = false
    private var lastUploadAt = 0L
    private var replanInProgress = false
    private var lastAcceptedLocation: Location? = null
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "实时导航", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopNavigation()
            return START_NOT_STICKY
        }
        intent?.getStringExtra(EXTRA_SESSION_ID)?.takeIf(String::isNotBlank)?.let {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SESSION_ID, it).apply()
        }
        intent?.getStringExtra(EXTRA_DEVICE_ID)?.takeIf(String::isNotBlank)?.let {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_DEVICE_ID, it).apply()
        }
        if (sessionId() == null || deviceId() == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.nankai.smartcane.R.drawable.ic_launcher_foreground)
            .setContentTitle("智能盲杖正在导航")
            .setContentText("正在使用真实位置更新路线")
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build())
        startUpdates()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLocationChanged(location: Location) {
        if (isMock(location) || (location.hasAccuracy() && location.accuracy > 50f)) return
        if (System.currentTimeMillis() - lastUploadAt < 2_000L) return
        val activeSession = sessionId() ?: return
        val activeDevice = deviceId() ?: return
        val distanceDeltaM = lastAcceptedLocation?.distanceTo(location)?.toDouble()
            ?.takeIf { it in 0.0..100.0 } ?: 0.0
        lastAcceptedLocation = Location(location)
        lastUploadAt = System.currentTimeMillis()
        executor.execute {
            SmartCaneApiClient.uploadNavigationLocation(
                LocationUploadDto(
                    deviceId = activeDevice,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    source = "android_navigation",
                    provider = location.provider ?: "fused",
                    quality = "navigation",
                    accuracyM = location.accuracy,
                    bearingDeg = location.bearing.takeIf { location.hasBearing() }
                )
            )
            val update = SmartCaneApiClient.updateNavigationSession(
                activeSession, location.latitude, location.longitude, location.accuracy.toDouble(), distanceDeltaM
            ) ?: return@execute
            sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName)
                .putExtra(EXTRA_SESSION_ID, activeSession)
                .putExtra(EXTRA_STATUS, update.status)
                .putExtra(EXTRA_STEP_INDEX, update.currentStepIndex)
                .putExtra(EXTRA_DISTANCE_TO_ROUTE_M, update.distanceToRouteM)
                .putExtra(EXTRA_DISTANCE_TO_DESTINATION_M, update.distanceToDestinationM)
                .putExtra(EXTRA_DISTANCE_TO_NEXT_ACTION_M, update.distanceToNextActionM)
                .putExtra(EXTRA_OFF_ROUTE, update.offRoute)
                .putExtra(EXTRA_ARRIVED, update.arrived)
                .putExtra(EXTRA_INSTRUCTION, update.currentStep?.instruction.orEmpty()))
            if (update.arrived) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply()
                stopSelf()
            } else if (update.shouldReplan && !replanInProgress) {
                replanInProgress = true
                sendBroadcast(Intent(ACTION_REPLANNING).setPackage(packageName).putExtra(EXTRA_SESSION_ID, activeSession))
                val route = SmartCaneApiClient.replanNavigationSession(activeSession)
                latestRoute = route
                replanInProgress = false
                sendBroadcast(Intent(ACTION_REPLANNED).setPackage(packageName)
                    .putExtra(EXTRA_SESSION_ID, activeSession)
                    .putExtra(EXTRA_REPLAN_SUCCESS, route != null)
                    .putExtra(EXTRA_SELECTED_ROUTE_INDEX, route?.selectedRouteIndex ?: -1)
                    .putExtra(EXTRA_ROUTE_COUNT, route?.routeCount ?: 0)
                    .putExtra(EXTRA_VOICE_PROMPT, route?.voicePrompt.orEmpty()))
            }
        }
    }

    private fun startUpdates() {
        if (listening) return
        if (!hasPermission()) {
            sendBroadcast(Intent(ACTION_LOCATION_FAILED).setPackage(packageName)
                .putExtra(EXTRA_ERROR, "定位权限已失效，导航已停止。"))
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply()
            stopSelf()
            return
        }
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        manager = locationManager
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            runCatching {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(provider, 2_000L, 1f, this)
                }
            }.onFailure {
                if (it is SecurityException) {
                    sendBroadcast(Intent(ACTION_LOCATION_FAILED).setPackage(packageName)
                        .putExtra(EXTRA_ERROR, "无法访问定位服务，导航已停止。"))
                    stopSelf()
                }
            }
        }
        listening = true
    }

    private fun stopNavigation() {
        sessionId()?.let { executor.execute { SmartCaneApiClient.stopNavigationSession(it) } }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply()
        stopSelf()
    }

    private fun sessionId() = getSharedPreferences(PREFS, MODE_PRIVATE)
        .getString(KEY_SESSION_ID, null)?.takeIf(String::isNotBlank)
    private fun deviceId() = getSharedPreferences(PREFS, MODE_PRIVATE)
        .getString(KEY_DEVICE_ID, null)?.takeIf(String::isNotBlank)

    private fun hasPermission() =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isMock(location: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }

    override fun onDestroy() {
        if (listening) runCatching { manager?.removeUpdates(this) }
        listening = false
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var latestRoute: com.nankai.smartcane.data.network.RouteAdviceDto? = null
        private const val ACTION_START = "com.nankai.smartcane.navigation.START"
        private const val ACTION_STOP = "com.nankai.smartcane.navigation.STOP"
        const val ACTION_STATE_CHANGED = "com.nankai.smartcane.navigation.STATE_CHANGED"
        const val ACTION_REPLANNING = "com.nankai.smartcane.navigation.REPLANNING"
        const val ACTION_REPLANNED = "com.nankai.smartcane.navigation.REPLANNED"
        const val ACTION_LOCATION_FAILED = "com.nankai.smartcane.navigation.LOCATION_FAILED"
        private const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_STATUS = "status"
        const val EXTRA_STEP_INDEX = "step_index"
        const val EXTRA_DISTANCE_TO_ROUTE_M = "distance_to_route_m"
        const val EXTRA_DISTANCE_TO_DESTINATION_M = "distance_to_destination_m"
        const val EXTRA_DISTANCE_TO_NEXT_ACTION_M = "distance_to_next_action_m"
        const val EXTRA_OFF_ROUTE = "off_route"
        const val EXTRA_ARRIVED = "arrived"
        const val EXTRA_INSTRUCTION = "instruction"
        const val EXTRA_REPLAN_SUCCESS = "replan_success"
        const val EXTRA_SELECTED_ROUTE_INDEX = "selected_route_index"
        const val EXTRA_ROUTE_COUNT = "route_count"
        const val EXTRA_VOICE_PROMPT = "voice_prompt"
        const val EXTRA_ERROR = "error"
        private const val EXTRA_DEVICE_ID = "device_id"
        private const val PREFS = "active_navigation"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_DEVICE_ID = "device_id"
        private const val CHANNEL_ID = "navigation_location"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context, sessionId: String, deviceId: String) {
            ContextCompat.startForegroundService(context, Intent(context, NavigationLocationService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_SESSION_ID, sessionId).putExtra(EXTRA_DEVICE_ID, deviceId))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NavigationLocationService::class.java).setAction(ACTION_STOP))
        }
    }
}
