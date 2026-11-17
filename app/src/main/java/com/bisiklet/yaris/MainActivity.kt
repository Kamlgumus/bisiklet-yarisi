package com.bisiklet.yaris

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity(), LocationListener {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var pid: String
    private var dbUrl = ""
    private var code = ""
    private var name = ""
    private var isHost = false
    private var myLoc: Location? = null
    private var picked: GeoPoint? = null
    private var session = 0
    private var centered = false
    private var inRoom = false
    private var last: JSONObject? = null
    private var map: MapView? = null
    private var statusTv: TextView? = null
    private var histTv: TextView? = null
    private var startBtn: Button? = null
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    private val evOverlay = MapEventsOverlay(object : MapEventsReceiver {
        override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
            if (canPick()) { picked = p; last?.let { render(it) } }
            return true
        }
        override fun longPressHelper(p: GeoPoint): Boolean = false
    })

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Configuration.getInstance().load(this, getSharedPreferences("osm", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        prefs = getSharedPreferences("app", MODE_PRIVATE)
        pid = prefs.getString("pid", null)
            ?: java.util.UUID.randomUUID().toString().take(8).also { prefs.edit().putString("pid", it).apply() }
        if (hasPerm()) startLoc()
        else ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        showHome()
    }

    private fun hasPerm() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (hasPerm()) startLoc()
    }

    @SuppressLint("MissingPermission")
    private fun startLoc() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try { if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 2000L, 0f, this) } catch (e: Exception) {}
        }
    }

    override fun onLocationChanged(l: Location) { myLoc = l }
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}

    override fun onResume() { super.onResume(); map?.onResume() }
    override fun onPause() { super.onPause(); map?.onPause() }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (inRoom) showHome() else super.onBackPressed() }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    // ---------- Firebase Realtime Database (REST) ----------
    private fun http(method: String, path: String, body: String? = null): String? = try {
        val c = URL("$dbUrl/$path.json").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 6000
        c.readTimeout = 6000
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null
    } catch (e: Exception) { null }

    // ---------- Ana ekran ----------
    private fun showHome() {
        inRoom = false; session++; map?.onPause(); map = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        l.addView(TextView(this).apply { text = "🚴 Bisiklet Yarışı"; textSize = 28f; setPadding(0, 0, 0, dp(24)) })
        val nameEt = EditText(this).apply { hint = "Adın"; setSingleLine(); setText(prefs.getString("name", "")) }
        val urlEt = EditText(this).apply {
            hint = "Firebase veritabanı URL'si"; setSingleLine(); setText(prefs.getString("url", ""))
        }
        val create = Button(this).apply { text = "Oda Kur" }
        val codeEt = EditText(this).apply {
            hint = "Oda kodu"; setSingleLine(); inputType = InputType.TYPE_CLASS_NUMBER
        }
        val join = Button(this).apply { text = "Odaya Katıl" }
        val msg = TextView(this).apply { setTextColor(Color.RED); setPadding(0, dp(12), 0, 0) }
        listOf(nameEt, urlEt, create, codeEt, join, msg).forEach { l.addView(it, LinearLayout.LayoutParams(MATCH, WRAP)) }

        fun prep(): Boolean {
            val n = nameEt.text.toString().trim()
            val u = urlEt.text.toString().trim().trimEnd('/')
            if (n.isEmpty() || !u.startsWith("https://")) { msg.text = "Adını ve geçerli Firebase URL'sini gir"; return false }
            name = n; dbUrl = u
            prefs.edit().putString("name", n).putString("url", u).apply()
            msg.text = "Bağlanıyor…"
            return true
        }
        create.setOnClickListener { if (prep()) Thread { createRoom(msg) }.start() }
        join.setOnClickListener {
            val c = codeEt.text.toString().trim()
            if (c.isEmpty()) { msg.text = "Oda kodunu gir"; return@setOnClickListener }
            if (prep()) Thread { joinRoom(c, msg) }.start()
        }
        setContentView(ScrollView(this).apply { addView(l) })
    }

    private fun createRoom(msg: TextView) {
        var c = ""
        for (i in 0 until 5) {
            c = (1000..9999).random().toString()
            val r = http("GET", "rooms/$c") ?: run { ui.post { msg.text = "Bağlantı hatası: URL'yi kontrol et" }; return }
            if (r.trim() == "null") break
        }
        val init = JSONObject().put("host", pid).put("round", 0)
            .put("players", JSONObject().put(pid, JSONObject().put("name", name)))
        if (http("PUT", "rooms/$c", init.toString()) == null) {
            ui.post { msg.text = "Oda kurulamadı (Firebase kurallarını kontrol et)" }; return
        }
        ui.post { code = c; isHost = true; showRoom() }
    }

    private fun joinRoom(c: String, msg: TextView) {
        val r = http("GET", "rooms/$c/round") ?: run { ui.post { msg.text = "Bağlantı hatası: URL'yi kontrol et" }; return }
        if (r.trim() == "null") { ui.post { msg.text = "Oda bulunamadı" }; return }
        val host = http("GET", "rooms/$c/host")?.trim()
        http("PUT", "rooms/$c/players/$pid", JSONObject().put("name", name).toString())
        ui.post { code = c; isHost = host == "\"$pid\""; showRoom() }
    }

    // ---------- Oda ekranı ----------
    private fun showRoom() {
        inRoom = true; centered = false; picked = null; last = null
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val st = TextView(this).apply {
            textSize = 16f; setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundColor(Color.parseColor("#EEEEEE"))
        }
        val mv = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(6.0)
            controller.setCenter(GeoPoint(39.0, 35.0))
        }
        val btn = Button(this).apply { text = "Turu Başlat"; visibility = android.view.View.GONE }
        val hist = TextView(this).apply { textSize = 15f; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val sv = ScrollView(this).apply { addView(hist) }
        root.addView(st, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(mv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(btn, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(sv, LinearLayout.LayoutParams(MATCH, dp(140)))
        setContentView(root)
        map = mv; statusTv = st; histTv = hist; startBtn = btn

        btn.setOnClickListener {
            val p = picked
            val j = last
            if (p == null || j == null) {
                Toast.makeText(this, "Önce haritaya dokunup hedef seç", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val n = curRound(j) + 1
            picked = null
            Thread {
                http("PUT", "rooms/$code/rounds/r$n",
                    JSONObject().put("lat", p.latitude).put("lng", p.longitude).toString())
                http("PUT", "rooms/$code/round", n.toString())
            }.start()
        }

        val s = ++session
        Thread {
            while (session == s) {
                try { tick() } catch (e: Exception) {}
                try { Thread.sleep(2000) } catch (e: Exception) {}
            }
        }.start()
    }

    private fun tick() {
        val l = myLoc
        if (l != null) http("PUT", "rooms/$code/players/$pid",
            JSONObject().put("name", name).put("lat", l.latitude).put("lng", l.longitude).toString())
        val txt = http("GET", "rooms/$code") ?: return
        val j = try { JSONObject(txt) } catch (e: Exception) { return }
        val n = j.optInt("round")
        val r = j.optJSONObject("rounds")?.optJSONObject("r$n")
        if (r != null && l != null && r.optJSONObject("finish")?.has(pid) != true) {
            val d = FloatArray(1)
            Location.distanceBetween(l.latitude, l.longitude, r.optDouble("lat"), r.optDouble("lng"), d)
            if (d[0] <= 30f) http("PUT", "rooms/$code/rounds/r$n/finish/$pid", "{\".sv\":\"timestamp\"}")
        }
        ui.post { if (inRoom) { last = j; render(j) } }
    }

    private fun curRound(j: JSONObject) = j.optInt("round")

    private fun winnerOfRound(j: JSONObject, r: JSONObject): String? {
        val f = r.optJSONObject("finish") ?: return null
        var best: String? = null
        var bt = Long.MAX_VALUE
        for (k in f.keys()) { val t = f.optLong(k); if (t < bt) { bt = t; best = k } }
        return best?.let { j.optJSONObject("players")?.optJSONObject(it)?.optString("name") ?: it }
    }

    private fun winnerOf(j: JSONObject): String? {
        val r = j.optJSONObject("rounds")?.optJSONObject("r${curRound(j)}") ?: return null
        return winnerOfRound(j, r)
    }

    private fun canPick(): Boolean {
        val j = last ?: return false
        return isHost && (curRound(j) == 0 || winnerOf(j) != null)
    }

    private fun marker(m: MapView, p: GeoPoint, text: String) = Marker(m).apply {
        position = p; setTextIcon(text); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
    }

    private fun render(j: JSONObject) {
        val m = map ?: return
        val n = curRound(j)
        val rounds = j.optJSONObject("rounds")
        val r = rounds?.optJSONObject("r$n")
        val players = j.optJSONObject("players")
        val w = winnerOf(j)
        val me = myLoc

        m.overlays.clear()
        m.overlays.add(evOverlay)
        var dest: GeoPoint? = null
        if (r != null) { dest = GeoPoint(r.optDouble("lat"), r.optDouble("lng")); m.overlays.add(marker(m, dest, "🏁 Hedef")) }
        val pk = picked
        if (pk != null && canPick()) m.overlays.add(marker(m, pk, "📍 Yeni hedef"))
        if (players != null) for (k in players.keys()) {
            val p = players.optJSONObject(k) ?: continue
            if (!p.has("lat")) continue
            val label = "🚴 " + if (k == pid) "Sen" else p.optString("name")
            m.overlays.add(marker(m, GeoPoint(p.optDouble("lat"), p.optDouble("lng")), label))
        }
        if (!centered && me != null) {
            m.controller.setZoom(16.0); m.controller.setCenter(GeoPoint(me.latitude, me.longitude)); centered = true
        }
        m.invalidate()

        val count = players?.length() ?: 1
        val state = when {
            n == 0 -> if (isHost) "Haritaya dokunup hedef seç, sonra Turu Başlat'a bas" else "Host hedef seçiyor…"
            w != null -> "🏆 Tur $n kazananı: $w"
            me != null && dest != null -> {
                val d = FloatArray(1)
                Location.distanceBetween(me.latitude, me.longitude, dest.latitude, dest.longitude, d)
                "Yarış sürüyor! Hedefe: ${d[0].toInt()} m"
            }
            else -> "Yarış sürüyor! Konum bekleniyor…"
        }
        statusTv?.text = "Oda: $code · $count kişi\n$state"

        startBtn?.apply {
            visibility = if (isHost && (n == 0 || w != null)) android.view.View.VISIBLE else android.view.View.GONE
            text = if (n == 0) "Turu Başlat" else "Yeni Tur Başlat"
        }

        val sb = StringBuilder("🏆 Yarışlar\n")
        if (n == 0) sb.append("Henüz yarış yok")
        for (i in n downTo 1) {
            val rr = rounds?.optJSONObject("r$i")
            val ww = rr?.let { winnerOfRound(j, it) }
            sb.append("Tur $i: ").append(if (ww != null) "🏆 $ww" else "devam ediyor").append("\n")
        }
        histTv?.text = sb.toString()
    }
}
