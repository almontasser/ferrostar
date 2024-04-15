package com.stadiamaps.ferrostar.maplibreui

import androidx.compose.runtime.Composable
import com.mapbox.mapboxsdk.geometry.LatLng
import com.maplibre.compose.symbols.Polyline

@Composable
fun BorderedPolyline(
    points: List<LatLng>,
    zIndex: Int = 1,
    color: String = "#3583dd",
    borderColor: String = "#ffffff",
    lineWidth: Float = 6f,
    borderWidth: Float = 2f,
) {
  Polyline(
      points = points,
      color = borderColor,
      lineWidth = lineWidth + borderWidth * 2f,
      zIndex = zIndex,
  )
  Polyline(
      points = points,
      color = color,
      lineWidth = lineWidth,
      zIndex = zIndex,
  )
}
