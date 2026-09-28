package com.beedev.trajets

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- Données

data class Pt(val lat: Double, val lon: Double, val t: Long)

data class Trip(val start: Long, val end: Long, val points: List<Pt>)

object Geo {
    fun km(p: List<Pt>): Double {
        val r = FloatArray(1)
        var m = 0.0
        for (i in 1 until p.size) {
            Location.distanceBetween(p[i - 1].lat, p[i - 1].lon, p[i].lat, p[i].lon, r)
            m += r[0]
        }
        return m / 1000.0
    }

    fun clock(secs: Long): String =
        "%d:%02d:%02d".format(secs / 3600, (secs % 3600) / 60, secs % 60)

    fun speed(km: Double, secs: Long): String =
        if (secs > 0) "%.1f km/h".format(km / (secs / 3600.0)) else "-"
}

/** État partagé entre le service (qui reçoit le GPS) et l'écran. */
object TrackState {
    var recording by mutableStateOf(false)
    var startTime by mutableLongStateOf(0L)
    val points = mutableStateListOf<Pt>()
}

/** Historique des trajets, gardé dans un simple fichier JSON sur le téléphone. */
object Store {
    private fun file(c: Context) = File(c.filesDir, "trips.json")

    fun load(c: Context): List<Trip> = try {
        val a = JSONArray(file(c).readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val ps = o.getJSONArray("p")
            Trip(
                o.getLong("s"),
                o.getLong("e"),
                (0 until ps.length()).map { j ->
                    val q = ps.getJSONArray(j)
                    Pt(q.getDouble(0), q.getDouble(1), q.getLong(2))
                },
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(c: Context, list: List<Trip>) {
        val a = JSONArray()
        list.forEach { t ->
            val ps = JSONArray()
            t.points.forEach { ps.put(JSONArray().put(it.lat).put(it.lon).put(it.t)) }
            a.put(JSONObject().put("s", t.start).put("e", t.end).put("p", ps))
        }
        file(c).writeText(a.toString())
    }
}

// ---------------------------------------------------------------- Service GPS

class TrackingService : Service() {
    private lateinit var client: FusedLocationProviderClient

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { TrackState.points.add(Pt(it.latitude, it.longitude, it.time)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("trajet", "Enregistrement du trajet", NotificationManager.IMPORTANCE_LOW),
        )
        val notif = NotificationCompat.Builder(this, "trajet")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Trajet en cours")
            .setContentText("Enregistrement de ta position...")
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, 1, notif, type)

        client = LocationServices.getFusedLocationProviderClient(this)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateDistanceMeters(5f)
            .build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (::client.isInitialized) client.removeLocationUpdates(callback)
        super.onDestroy()
    }
}

// ---------------------------------------------------------------- Écrans

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osm")
            osmdroidTileCache = File(cacheDir, "osm/tiles")
        }
        setContent { MaterialTheme { Surface { App() } } }
    }
}

fun startTrip(ctx: Context) {
    TrackState.points.clear()
    TrackState.startTime = System.currentTimeMillis()
    TrackState.recording = true
    ContextCompat.startForegroundService(ctx, Intent(ctx, TrackingService::class.java))
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var trips by remember { mutableStateOf(Store.load(ctx)) }
    var selected by remember { mutableStateOf<Trip?>(null) }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            r[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) startTrip(ctx)
    }

    fun toggle() {
        if (TrackState.recording) {
            ctx.stopService(Intent(ctx, TrackingService::class.java))
            TrackState.recording = false
            val p = TrackState.points.toList()
            if (p.size >= 2) {
                Store.save(ctx, trips + Trip(TrackState.startTime, System.currentTimeMillis(), p))
                trips = Store.load(ctx)
            }
        } else {
            val need = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.launch(need.toTypedArray())
        }
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Enregistrer") })
            Tab(selected = tab == 1, onClick = { tab = 1; selected = null }, text = { Text("Historique") })
        }
        val sel = selected
        when {
            tab == 0 -> Recorder(onToggle = { toggle() })
            sel == null -> History(trips) { selected = it }
            else -> Detail(
                trip = sel,
                onBack = { selected = null },
                onDelete = {
                    Store.save(ctx, trips - sel)
                    trips = Store.load(ctx)
                    selected = null
                },
            )
        }
    }
}

@Composable
fun Recorder(onToggle: () -> Unit) {
    val pts = TrackState.points.toList()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(TrackState.recording) {
        while (TrackState.recording) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val secs = when {
        TrackState.recording -> (now - TrackState.startTime) / 1000
        pts.size > 1 -> (pts.last().t - pts.first().t) / 1000
        else -> 0L
    }
    val km = Geo.km(pts)

    Column(Modifier.fillMaxSize()) {
        TripMap(pts, follow = true, modifier = Modifier.weight(1f).fillMaxWidth())
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat("Distance", "%.2f km".format(km))
                Stat("Durée", Geo.clock(secs))
                Stat("Vitesse moy.", Geo.speed(km, secs))
            }
            Button(onClick = onToggle, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(if (TrackState.recording) "Arrêter le trajet" else "Démarrer un trajet")
            }
        }
    }
}

@Composable
fun History(trips: List<Trip>, onSelect: (Trip) -> Unit) {
    if (trips.isEmpty()) {
        Text("Aucun trajet enregistré pour le moment.", Modifier.padding(24.dp))
        return
    }
    val fmt = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(trips.reversed()) { t ->
            Column(Modifier.fillMaxWidth().clickable { onSelect(t) }.padding(16.dp)) {
                Text(fmt.format(Date(t.start)), style = MaterialTheme.typography.titleMedium)
                Text("%.2f km  ·  %s".format(Geo.km(t.points), Geo.clock((t.end - t.start) / 1000)))
            }
            HorizontalDivider()
        }
    }
}

@Composable
fun Detail(trip: Trip, onBack: () -> Unit, onDelete: () -> Unit) {
    val km = Geo.km(trip.points)
    val secs = (trip.end - trip.start) / 1000
    Column(Modifier.fillMaxSize()) {
        TripMap(trip.points, follow = false, modifier = Modifier.weight(1f).fillMaxWidth())
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat("Distance", "%.2f km".format(km))
                Stat("Durée", Geo.clock(secs))
                Stat("Vitesse moy.", Geo.speed(km, secs))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Retour") }
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text("Supprimer") }
            }
        }
    }
}

@Composable
fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun TripMap(points: List<Pt>, follow: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(3.0)
            controller.setCenter(GeoPoint(20.0, 0.0))
        }
    }
    DisposableEffect(Unit) { onDispose { map.onDetach() } }

    AndroidView(factory = { map }, modifier = modifier, update = { m ->
        m.overlays.clear()
        if (points.isNotEmpty()) {
            val gp = points.map { GeoPoint(it.lat, it.lon) }
            m.overlays.add(
                Polyline().apply {
                    setPoints(gp)
                    outlinePaint.color = 0xFF6D4AFF.toInt()
                    outlinePaint.strokeWidth = 12f
                },
            )
            if (follow) {
                if (m.zoomLevelDouble < 10.0) m.controller.setZoom(17.0)
                m.controller.setCenter(gp.last())
            } else if (gp.size > 1) {
                m.post { m.zoomToBoundingBox(BoundingBox.fromGeoPoints(gp), false, 80) }
            }
        }
        m.invalidate()
    })
}
