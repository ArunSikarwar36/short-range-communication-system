package ai.paai.link.ui

import ai.paai.link.core.location.Geo
import ai.paai.link.core.maps.MapRegion
import ai.paai.link.maps.MapDownload
import android.annotation.SuppressLint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val PEOPLE = "srcs-people"

/** Style JSON for one downloaded area, with fonts and icons from the app's assets (no internet). */
private fun styleFor(context: android.content.Context, region: MapRegion): String =
    context.assets.open("map/style-dark.json").bufferedReader().use { it.readText() }
        .replace("{MAP_URL}", "pmtiles://file://${region.path}")
        .replace("{ASSETS}", "asset://map")

private fun addPeopleLayers(style: Style) {
    style.addSource(GeoJsonSource(PEOPLE, FeatureCollection.fromFeatures(emptyList())))
    fun kind(k: String) = Expression.eq(Expression.get("kind"), Expression.literal(k))
    style.addLayer(
        CircleLayer("srcs-sos", PEOPLE).withFilter(kind("sos")).withProperties(
            PropertyFactory.circleRadius(14f), PropertyFactory.circleColor("#E5484D"), PropertyFactory.circleOpacity(0.85f),
            PropertyFactory.circleStrokeWidth(3f), PropertyFactory.circleStrokeColor("#FFFFFF"),
        ),
    )
    style.addLayer(
        CircleLayer("srcs-friends", PEOPLE).withFilter(kind("friend")).withProperties(
            PropertyFactory.circleRadius(9f), PropertyFactory.circleColor("#3DDC97"),
            PropertyFactory.circleStrokeWidth(2f), PropertyFactory.circleStrokeColor("#0A0F16"),
        ),
    )
    style.addLayer(
        CircleLayer("srcs-me", PEOPLE).withFilter(kind("me")).withProperties(
            PropertyFactory.circleRadius(9f), PropertyFactory.circleColor("#22C7E6"),
            PropertyFactory.circleStrokeWidth(3f), PropertyFactory.circleStrokeColor("#FFFFFF"),
        ),
    )
    style.addLayer(
        SymbolLayer("srcs-names", PEOPLE).withProperties(
            PropertyFactory.textField(Expression.get("name")), PropertyFactory.textFont(arrayOf("Noto Sans Medium")),
            PropertyFactory.textSize(13f), PropertyFactory.textColor("#E6F1F5"), PropertyFactory.textHaloColor("#0A0F16"),
            PropertyFactory.textHaloWidth(1.5f), PropertyFactory.textOffset(arrayOf(0f, 1.5f)), PropertyFactory.textAnchor("top"),
            PropertyFactory.textAllowOverlap(true), PropertyFactory.textIgnorePlacement(true),
        ),
    )
}

private fun feature(kind: String, name: String, lat: Double, lon: Double) =
    Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply { addStringProperty("kind", kind); addStringProperty("name", name) }

/**
 * Offline map of a downloaded area with this phone, friends who share their location and SOS
 * alerts. [focusLat]/[focusLon] centre the map on a person (e.g. from an SOS).
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun MapScreen(vm: LinkViewModel, focusLat: Double?, focusLon: Double?, onBack: () -> Unit, onManage: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val regions by vm.c.maps.list.collectAsStateWithLifecycle()
    val me by vm.myFix.collectAsStateWithLifecycle()
    val friends by vm.friendLocations.collectAsStateWithLifecycle()
    val sos by vm.activeSos.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<MapRegion?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf<Style?>(null) }

    DisposableEffect(Unit) {
        vm.c.mapOpen.value = true
        onDispose { vm.c.mapOpen.value = false }
    }
    LaunchedEffect(regions) {
        if (selected == null || regions.none { it.path == selected?.path }) {
            selected = vm.c.maps.regions.bestFor(focusLat ?: me?.lat, focusLon ?: me?.lon)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(selected?.name?.replace('_', ' ') ?: "Offline map") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            actions = { TextButton(onClick = onManage) { Text("Map areas") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = LinkColors.Background),
        )
        val region = selected
        if (region == null) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("No offline map yet")
                Hint("Download the area around you once (needs internet), then the map works anywhere without it. You can also import a .pmtiles map file someone gave you.")
                Button(onClick = onManage) { Text("Get a map area") }
            }
            return@Column
        }
        Box(Modifier.fillMaxSize()) {
            val mapView = remember {
                MapLibre.getInstance(context)
                MapView(context).apply { onCreate(null) }
            }
            DisposableEffect(lifecycle) {
                val obs = LifecycleEventObserver { _, e ->
                    when (e) {
                        Lifecycle.Event.ON_START -> mapView.onStart()
                        Lifecycle.Event.ON_RESUME -> mapView.onResume()
                        Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                        Lifecycle.Event.ON_STOP -> mapView.onStop()
                        else -> {}
                    }
                }
                lifecycle.addObserver(obs)
                onDispose {
                    lifecycle.removeObserver(obs)
                    mapView.onPause(); mapView.onStop(); mapView.onDestroy()
                }
            }
            AndroidView({ mapView }, Modifier.fillMaxSize())
            LaunchedEffect(region.path) {
                styleReady = null
                mapView.getMapAsync { m ->
                    map = m
                    m.uiSettings.isRotateGesturesEnabled = false
                    m.setMinZoomPreference(region.minZoom.toDouble())
                    m.setMaxZoomPreference(region.maxZoom + 2.0) // allow a little over-zoom
                    m.setLatLngBoundsForCameraTarget(
                        LatLngBounds.Builder().include(LatLng(region.minLat, region.minLon)).include(LatLng(region.maxLat, region.maxLon)).build(),
                    )
                    m.setStyle(Style.Builder().fromJson(styleFor(context, region))) { style ->
                        addPeopleLayers(style)
                        styleReady = style
                        val lat = focusLat ?: me?.lat?.takeIf { region.contains(it, me!!.lon) } ?: region.centerLat
                        val lon = focusLon ?: me?.lon?.takeIf { region.contains(me!!.lat, it) } ?: region.centerLon
                        m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), minOf(15.0, region.maxZoom.toDouble())))
                    }
                }
            }
            // Keep the people layer current.
            LaunchedEffect(styleReady, me, friends, sos) {
                val style = styleReady ?: return@LaunchedEffect
                val features = buildList {
                    sos.forEach { a -> a.fix?.let { add(feature("sos", "SOS ${a.name}", it.lat, it.lon)) } }
                    friends.forEach { f -> add(feature("friend", f.name, f.lat, f.lon)) }
                    me?.let { add(feature("me", "You", it.lat, it.lon)) }
                }
                style.getSourceAs<GeoJsonSource>(PEOPLE)?.setGeoJson(FeatureCollection.fromFeatures(features))
            }
            Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (friends.isNotEmpty() && me != null) {
                    val nearest = friends.minBy { Geo.distanceM(me!!.lat, me!!.lon, it.lat, it.lon) }
                    Text(
                        "${nearest.name}: " + Geo.formatDistance(Geo.distanceM(me!!.lat, me!!.lon, nearest.lat, nearest.lon)) + " " +
                            Geo.compass(Geo.bearingDeg(me!!.lat, me!!.lon, nearest.lat, nearest.lon)),
                        color = LinkColors.OnSurface,
                    )
                }
                FloatingActionButton(onClick = {
                    val f = me ?: return@FloatingActionButton
                    map?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(f.lat, f.lon)))
                }) { Icon(Icons.Default.LocationOn, contentDescription = "Centre on me") }
            }
            if (regions.size > 1) {
                Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    regions.take(4).forEach { r ->
                        FilterChip(selected = r.path == region.path, onClick = { selected = r }, label = { Text(r.name.replace('_', ' ').take(14)) })
                    }
                }
            }
        }
    }
}

/** Download, import and delete offline map areas. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapAreasScreen(vm: LinkViewModel, onBack: () -> Unit, onOpen: () -> Unit) {
    val maps = vm.c.maps
    val regions by maps.list.collectAsStateWithLifecycle()
    val download by maps.download.collectAsStateWithLifecycle()
    val me by vm.myFix.collectAsStateWithLifecycle()
    var radius by remember { mutableDoubleStateOf(5.0) }
    var name by remember { mutableStateOf("") }
    var coords by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) maps.import(uri) { vm.notify(it) } }

    DisposableEffect(Unit) {
        vm.c.mapOpen.value = true // GPS on, to download around the current position
        onDispose { vm.c.mapOpen.value = false }
    }
    LaunchedEffect(me) { if (coords.isBlank()) me?.let { coords = "%.5f, %.5f".format(it.lat, it.lon) } }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Map areas") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = LinkColors.Background),
        )
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard("Download an area") {
                    Hint("Needs internet once. Map data © OpenStreetMap contributors, served by Protomaps.")
                    OutlinedTextField(coords, { coords = it }, label = { Text("Centre (latitude, longitude)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (me == null) Hint("Waiting for GPS… or type coordinates.")
                    Text("Radius")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2.0, 5.0, 15.0, 30.0).forEach { r ->
                            FilterChip(selected = radius == r, onClick = { radius = r }, label = { Text("${r.toInt()} km") })
                        }
                    }
                    OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    when (val d = download) {
                        is MapDownload.Idle, is MapDownload.Failed, is MapDownload.Done -> {
                            if (d is MapDownload.Failed) Text(d.message, color = LinkColors.Warn)
                            if (d is MapDownload.Done) Text("Saved '${d.region.name.replace('_', ' ')}' (${"%.1f".format(d.region.sizeBytes / 1_048_576.0)} MB)", color = LinkColors.Good)
                            Button(onClick = {
                                val parts = coords.split(',', ' ').mapNotNull { it.trim().toDoubleOrNull() }
                                if (parts.size != 2 || parts[0] !in -85.0..85.0 || parts[1] !in -180.0..180.0) {
                                    vm.notify("Enter the centre as latitude, longitude")
                                } else {
                                    maps.planArea(name.ifBlank { "Area ${radius.toInt()} km" }, parts[0], parts[1], radius)
                                }
                            }) { Text("Check size") }
                        }
                        is MapDownload.Planning -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Hint("Looking up the newest map…") }
                        is MapDownload.Planned -> {
                            Text("${d.tiles} tiles · ${"%.1f".format(d.bytes / 1_048_576.0)} MB · detail up to zoom ${d.maxZoom}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { maps.startDownload() }) { Text("Download") }
                                OutlinedButton(onClick = { maps.cancelDownload() }) { Text("Cancel") }
                            }
                        }
                        is MapDownload.Downloading -> {
                            LinearProgressIndicator({ if (d.total == 0L) 1f else d.done.toFloat() / d.total }, Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Hint("${"%.1f".format(d.done / 1_048_576.0)} / ${"%.1f".format(d.total / 1_048_576.0)} MB")
                                TextButton(onClick = { maps.cancelDownload() }) { Text("Cancel") }
                            }
                        }
                    }
                }
            }
            item {
                SectionCard("On this phone") {
                    if (regions.isEmpty()) Hint("No map areas yet.")
                    regions.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name.replace('_', ' '))
                                Hint("${"%.1f".format(r.sizeBytes / 1_048_576.0)} MB · zoom ${r.minZoom}–${r.maxZoom} · %.3f, %.3f".format(r.centerLat, r.centerLon))
                            }
                            TextButton(onClick = onOpen) { Text("Open") }
                            TextButton(onClick = { maps.delete(r) }) { Text("Delete") }
                        }
                    }
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import a .pmtiles file") }
                }
            }
        }
    }
}
