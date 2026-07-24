package com.nankai.smartcane.ui.blind

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.nankai.smartcane.data.network.NavigationRouteDto

@Composable
fun BlindNavigationScreen(
    route: NavigationRouteDto?,
    status: String,
    instruction: String,
    onStop: () -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            text = instruction.ifBlank { if (status == "replanning") "正在重新规划" else "实时导航" },
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        )
        NavigationAmap(route, Modifier.fillMaxWidth().weight(1f))
        Row(Modifier.fillMaxWidth().padding(12.dp)) {
            Button(onClick = onBack, modifier = Modifier.weight(1f).padding(end = 6.dp)) { Text("返回语音页") }
            Button(onClick = onStop, modifier = Modifier.weight(1f).padding(start = 6.dp)) { Text("结束导航") }
        }
    }
}

@Composable
private fun NavigationAmap(route: NavigationRouteDto?, modifier: Modifier) {
    val context = LocalContext.current
    val mapView = remember {
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
        TextureMapView(context).apply { onCreate(Bundle()) }
    }
    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDestroy()
        }
    }
    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { view ->
            val amap = view.map
            amap.clear()
            val routePoints = route?.polyline.orEmpty().map { LatLng(it.latitude, it.longitude) }
            if (routePoints.size >= 2) {
                amap.addPolyline(
                    PolylineOptions().addAll(routePoints)
                        .color(android.graphics.Color.rgb(37, 99, 235)).width(12f)
                )
                val bounds = LatLngBounds.builder().apply { routePoints.forEach(::include) }.build()
                amap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
            }
            // Risk points are an independent marker layer and are never connected.
            route?.matchedRiskPoints.orEmpty().forEach { risk ->
                val lat = risk.latitude ?: return@forEach
                val lng = risk.longitude ?: return@forEach
                val hue = when (risk.riskLevel.lowercase()) {
                    "high" -> BitmapDescriptorFactory.HUE_RED
                    "medium" -> BitmapDescriptorFactory.HUE_ORANGE
                    else -> BitmapDescriptorFactory.HUE_YELLOW
                }
                amap.addMarker(
                    MarkerOptions().position(LatLng(lat, lng))
                        .title(risk.riskType).snippet("距路线 ${risk.distanceToRouteM.toInt()} 米")
                        .icon(BitmapDescriptorFactory.defaultMarker(hue))
                )
            }
        }
    )
}
