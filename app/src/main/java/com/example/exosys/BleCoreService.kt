package com.example.exosys

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.FileReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread
import kotlin.math.abs

class BleCoreService : Service() {

    companion object {
        private const val CHANNEL_ID = "ExosysBleChannel"
        private const val PREFS_NAME = "ExosysBlackBox"
        private const val KEY_GNSS_ENABLED = "FASE6_GNSS_INJECT_ENABLED"
        private const val KEY_GNSS_INTERVAL_MS = "FASE6_GNSS_INTERVAL_MS"
        private const val KEY_LAST_LAT = "LAST_VALID_LAT"
        private const val KEY_LAST_LON = "LAST_VALID_LON"
        private const val KEY_LAST_ALT = "LAST_VALID_ALT"

        val EXOSYS_SERVICE_UUID: UUID = UUID.fromString("E20A39F4-73F5-4BC4-A12F-17D1AD07A961")
        val EXOSYS_POS_CHAR_UUID: UUID = UUID.fromString("E20A39F5-73F5-4BC4-A12F-17D1AD07A961")

        val connectedBleDevices = CopyOnWriteArraySet<BluetoothDevice>()

        @Volatile var isRunning: Boolean = false
        @Volatile var lastLat: Double = 0.0
        @Volatile var lastLon: Double = 0.0
        @Volatile var lastAlt: Double = 0.0
        @Volatile var lastSpdKmh: Float = 0.0f
        @Volatile var lastProvider: String = "NINGUNO"
        @Volatile var lastPosPayload: String = "POS:0.000000,0.000000,0.0,0.0"
        @Volatile var injectedPacketsCount: Long = 0L

        fun isGnssInjectionEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_GNSS_ENABLED, false)
        }

        fun setGnssInjectionEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_GNSS_ENABLED, enabled)
                .apply()

            val intent = Intent(context, BleCoreService::class.java).apply {
                action = if (enabled) "ACTION_START_GNSS_INJECT" else "ACTION_STOP_GNSS_INJECT"
            }
            try {
                context.startService(intent)
            } catch (_: Throwable) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    }
                } catch (_: Throwable) {}
            }
        }

        fun getGnssIntervalMs(context: Context): Long {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_GNSS_INTERVAL_MS, 1000L)
                .coerceIn(500L, 15000L)
        }

        fun setGnssIntervalMs(context: Context, intervalMs: Long) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_GNSS_INTERVAL_MS, intervalMs.coerceIn(500L, 15000L))
                .apply()
            if (isGnssInjectionEnabled(context)) {
                try {
                    val intent = Intent(context, BleCoreService::class.java).apply {
                        action = "ACTION_UPDATE_GNSS_INTERVAL"
                    }
                    context.startService(intent)
                } catch (_: Throwable) {}
            }
        }

        fun triggerImmediatePulse(context: Context) {
            try {
                val intent = Intent(context, BleCoreService::class.java).apply {
                    action = "ACTION_FORCE_PULSE_NOW"
                }
                context.startService(intent)
            } catch (_: Throwable) {}
        }
    }

    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var posCharacteristic: BluetoothGattCharacteristic? = null

    private var locationManager: LocationManager? = null
    private var locationTrackingActive = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isPowerSaveReceiverRegistered = false

    // Control de estado del anunciador BLE para no reiniciar la portadora si la coordenada no ha cambiado
    private var isAdvertisingActive = false
    private var advLat: Float = Float.NaN
    private var advLon: Float = Float.NaN
    private var advAlt: Int = Int.MIN_VALUE
    private var advSpd: Int = Int.MIN_VALUE
    private var lastAdvUpdateMs: Long = 0L

    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                if (locationTrackingActive) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    val status = if (pm.isPowerSaveMode) "10s (Ahorro Batería)" else "1s (Rendimiento)"
                    emitirLogConsola("Ajuste de energía detectado. Cadencia GPS: $status")
                    detenerRastreoGnss()
                    iniciarRastreoGnss()
                }
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertisingActive = true
            Log.i("EXOSYS_BLE", "Baliza BLE 0xE20A transmitiendo a 10Hz.")
        }
        override fun onStartFailure(errorCode: Int) {
            isAdvertisingActive = false
            emitirLogConsola("Alerta BLE: Código de radio $errorCode")
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            guardarUbicacionEnCache(location)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    // Se ejecuta religiosamente cada 1 segundo (o intervalo configurado)
    private val periodicPulseRunnable = object : Runnable {
        override fun run() {
            if (!isGnssInjectionEnabled(this@BleCoreService)) return

            val loc = obtenerMejorUbicacionConocida()
            if (loc != null) {
                procesarYTransmitirUbicacion(loc)
            } else if (lastLat != 0.0 || lastLon != 0.0) {
                val cachedLoc = Location("CACHED").apply {
                    latitude = lastLat
                    longitude = lastLon
                    altitude = lastAlt
                    speed = lastSpdKmh / 3.6f
                }
                procesarYTransmitirUbicacion(cachedLoc)
            }

            mainHandler.postDelayed(this, obtenerIntervaloDinamico())
        }
    }

    private fun obtenerIntervaloDinamico(): Long {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (pm.isPowerSaveMode) 10000L else getGnssIntervalMs(this)
    }

    private fun cargarCacheCoordenadasPrevias() {
        val sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cLat = sp.getFloat(KEY_LAST_LAT, 0.0f).toDouble()
        val cLon = sp.getFloat(KEY_LAST_LON, 0.0f).toDouble()
        val cAlt = sp.getFloat(KEY_LAST_ALT, 0.0f).toDouble()
        if (cLat != 0.0 || cLon != 0.0) {
            lastLat = cLat
            lastLon = cLon
            lastAlt = cAlt
            lastProvider = "CACHE"
            lastPosPayload = String.format(Locale.US, "POS:%.6f,%.6f,%.1f,0.0", lastLat, lastLon, lastAlt)
        }
    }

    private fun guardarUbicacionEnCache(loc: Location) {
        if (loc.latitude == 0.0 && loc.longitude == 0.0) return
        lastLat = loc.latitude
        lastLon = loc.longitude
        if (loc.hasAltitude()) lastAlt = loc.altitude
        if (loc.hasSpeed()) lastSpdKmh = loc.speed * 3.6f
        lastProvider = (loc.provider ?: "FUSED").uppercase(Locale.US)

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_LAST_LAT, loc.latitude.toFloat())
            .putFloat(KEY_LAST_LON, loc.longitude.toFloat())
            .putFloat(KEY_LAST_ALT, lastAlt.toFloat())
            .apply()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        cargarCacheCoordenadasPrevias()
        crearCanalNotificacion()

        try {
            registerReceiver(powerSaveReceiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
            isPowerSaveReceiverRegistered = true
        } catch (_: Throwable) {}

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Exosys Core & GNSS Link")
            .setContentText("Baliza BLE y Telemetría Pasiva Activas")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        // Blindaje total contra InvalidForegroundServiceTypeException en Android 14
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(1, notification)
            }
        } catch (_: Throwable) {
            try {
                startForeground(1, notification)
            } catch (_: Throwable) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        iniciarServidorGatt()

        obtenerMejorUbicacionConocida()?.let { guardarUbicacionEnCache(it) }
        actualizarBalizaBleConCoordenadas(
            lastLat.toFloat(),
            lastLon.toFloat(),
            lastAlt.toInt(),
            (lastSpdKmh * 10f).toInt(),
            forceRestart = !isAdvertisingActive
        )

        when (intent?.action) {
            "ACTION_START_GNSS_INJECT", "ACTION_UPDATE_GNSS_INTERVAL" -> {
                injectedPacketsCount = 0L
                detenerRastreoGnss()
                iniciarRastreoGnss()
            }
            "ACTION_STOP_GNSS_INJECT" -> {
                detenerRastreoGnss()
                emitirLogConsola("Fase 6: Inyección GNSS pasiva detenida.")
            }
            "ACTION_FORCE_PULSE_NOW" -> {
                if (!locationTrackingActive && isGnssInjectionEnabled(this)) {
                    iniciarRastreoGnss()
                }
                val loc = obtenerMejorUbicacionConocida()
                if (loc != null) {
                    procesarYTransmitirUbicacion(loc, forceBleUpdate = true, forceLog = true)
                } else if (lastLat != 0.0 || lastLon != 0.0) {
                    val cachedLoc = Location("CACHED").apply {
                        latitude = lastLat
                        longitude = lastLon
                        altitude = lastAlt
                    }
                    procesarYTransmitirUbicacion(cachedLoc, forceBleUpdate = true, forceLog = true)
                } else {
                    emitirLogConsola("Esperando primera lectura del GPS/Red del teléfono...")
                }
            }
            else -> {
                if (isGnssInjectionEnabled(this) && !locationTrackingActive) {
                    iniciarRastreoGnss()
                }
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun iniciarRastreoGnss() {
        if (!tienePermisosUbicacion()) {
            emitirLogConsola("ERROR Fase 6: Concede permiso de Ubicación a Exosys.")
            return
        }

        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager = lm
            val interval = obtenerIntervaloDinamico()

            val providersToRegister = HashSet<String>()
            providersToRegister.add(LocationManager.GPS_PROVIDER)
            providersToRegister.add(LocationManager.NETWORK_PROVIDER)
            providersToRegister.add(LocationManager.PASSIVE_PROVIDER)
            if (lm.allProviders.contains("fused")) {
                providersToRegister.add("fused")
            }

            for (prov in providersToRegister) {
                try {
                    if (lm.allProviders.contains(prov)) {
                        lm.requestLocationUpdates(prov, interval, 0f, locationListener, Looper.getMainLooper())
                    }
                } catch (_: Throwable) {}
            }

            locationTrackingActive = true
            emitirLogConsola("Fase 6 Activa: Escupiendo GPS/Red cada ${interval}ms al reloj.")

            mainHandler.removeCallbacks(periodicPulseRunnable)
            mainHandler.post(periodicPulseRunnable)
        } catch (e: Exception) {
            emitirLogConsola("Error iniciando GPS pasivo: ${e.localizedMessage}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun detenerRastreoGnss() {
        mainHandler.removeCallbacks(periodicPulseRunnable)
        try { locationManager?.removeUpdates(locationListener) } catch (_: Throwable) {}
        locationTrackingActive = false
    }

    @SuppressLint("MissingPermission")
    private fun obtenerMejorUbicacionConocida(): Location? {
        if (!tienePermisosUbicacion()) return null
        val lm = locationManager ?: (getSystemService(Context.LOCATION_SERVICE) as? LocationManager) ?: return null
        return try {
            var bestLoc: Location? = null
            val allProvs = HashSet<String>()
            allProvs.addAll(lm.allProviders)
            allProvs.add("fused")
            allProvs.add(LocationManager.GPS_PROVIDER)
            allProvs.add(LocationManager.NETWORK_PROVIDER)
            allProvs.add(LocationManager.PASSIVE_PROVIDER)

            for (p in allProvs) {
                try {
                    val l = lm.getLastKnownLocation(p) ?: continue
                    if (l.latitude == 0.0 && l.longitude == 0.0) continue
                    if (bestLoc == null || l.time > bestLoc.time) {
                        bestLoc = l
                    }
                } catch (_: Throwable) {}
            }
            bestLoc
        } catch (_: Throwable) { null }
    }

    private fun procesarYTransmitirUbicacion(
        loc: Location,
        forceBleUpdate: Boolean = false,
        forceLog: Boolean = false
    ) {
        guardarUbicacionEnCache(loc)

        val lat = lastLat
        val lon = lastLon
        val alt = lastAlt
        val spdKmh = lastSpdKmh
        val prov = lastProvider

        val payload = String.format(Locale.US, "POS:%.6f,%.6f,%.1f,%.1f", lat, lon, alt, spdKmh)
        lastPosPayload = payload
        injectedPacketsCount++

        // 1. Transmisión real cada 1 segundo (GATT + Baliza BLE + UDP)
        notificarGattBle(payload)
        actualizarBalizaBleConCoordenadas(
            lat.toFloat(),
            lon.toFloat(),
            alt.toInt(),
            (spdKmh * 10f).toInt(),
            forceRestart = forceBleUpdate
        )
        despacharTramaUdpAlReloj(payload)

        // 2. Escala Logarítmica estricta para impresión en consola:
        // Del 1 al 10: cada 1s | Del 10 al 100: cada 10s | Del 100 al 1000: cada 100s | 1000+: cada 1000s
        val c = injectedPacketsCount
        val shouldLog = forceLog || when {
            c <= 10L -> true
            c <= 100L -> (c % 10L == 0L)
            c <= 1000L -> (c % 100L == 0L)
            else -> (c % 1000L == 0L)
        }
        if (shouldLog) {
            emitirLogConsola("GNSS Inyectado [$c] -> $payload ($prov)")
        }
    }

    private fun despacharTramaUdpAlReloj(payload: String) {
        thread(start = true, isDaemon = true) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply { broadcast = true }
                val data = payload.toByteArray(Charsets.UTF_8)
                val targets = HashSet<String>()

                val sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val savedIp = sp.getString("LAST_IP", "")?.trim().orEmpty()
                val manualIp = sp.getString("MANUAL_TARGET_IP", "")?.trim().orEmpty()
                if (savedIp.isNotEmpty()) targets.add(savedIp)
                if (manualIp.isNotEmpty()) targets.add(manualIp)

                targets.addAll(leerClientesArpHotspot())
                targets.addAll(obtenerBroadcastsLocales())
                targets.add("255.255.255.255")

                for (ipStr in targets) {
                    try {
                        val addr = InetAddress.getByName(ipStr)
                        socket.send(DatagramPacket(data, data.size, addr, 8888))
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {} finally { socket?.close() }
        }
    }

    private fun leerClientesArpHotspot(): List<String> {
        val result = ArrayList<String>()
        try {
            BufferedReader(FileReader("/proc/net/arp")).use { br ->
                var line = br.readLine()
                while (line != null) {
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 4) {
                        val ip = parts[0]
                        val mac = parts[3]
                        if (ip != "IP" && mac != "00:00:00:00:00:00" &&
                            (ip.startsWith("192.168.") || ip.startsWith("172.") || ip.startsWith("10."))
                        ) {
                            result.add(ip)
                        }
                    }
                    line = br.readLine()
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private fun obtenerBroadcastsLocales(): List<String> {
        val out = ArrayList<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val bcast = ia.broadcast
                    if (bcast is Inet4Address) bcast.hostAddress?.let { out.add(it) }
                }
            }
        } catch (_: Exception) {}
        return out
    }

    @SuppressLint("MissingPermission")
    private fun iniciarServidorGatt() {
        if (gattServer != null) return
        try {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter ?: return
            if (!adapter.isEnabled) return

            val server = bluetoothManager.openGattServer(this, object : BluetoothGattServerCallback() {
                override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                    if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                        connectedBleDevices.add(device)
                    } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                        connectedBleDevices.remove(device)
                    }
                }

                override fun onCharacteristicReadRequest(
                    device: BluetoothDevice,
                    requestId: Int,
                    offset: Int,
                    characteristic: BluetoothGattCharacteristic
                ) {
                    if (characteristic.uuid == EXOSYS_POS_CHAR_UUID) {
                        val bytes = lastPosPayload.toByteArray(Charsets.UTF_8)
                        val slice = if (offset < bytes.size) bytes.copyOfRange(offset, bytes.size) else ByteArray(0)
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
                    } else {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                    }
                }
            }) ?: return

            val service = BluetoothGattService(EXOSYS_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val posChar = BluetoothGattCharacteristic(
                EXOSYS_POS_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            service.addCharacteristic(posChar)
            server.addService(service)

            gattServer = server
            posCharacteristic = posChar
        } catch (_: Throwable) {}
    }

    @SuppressLint("MissingPermission")
    private fun notificarGattBle(payload: String) {
        val server = gattServer ?: return
        val charac = posCharacteristic ?: return
        try {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            @Suppress("DEPRECATION")
            charac.value = bytes
            for (dev in connectedBleDevices) {
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(dev, charac, false)
            }
        } catch (_: Throwable) {}
    }

    private fun actualizarBalizaBleConCoordenadas(
        lat: Float,
        lon: Float,
        altMeters: Int,
        spdTenths: Int,
        forceRestart: Boolean = false
    ) {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            isAdvertisingActive = false
            return
        }

        val adv = adapter.bluetoothLeAdvertiser ?: return
        advertiser = adv

        val now = System.currentTimeMillis()
        val coordsChanged = abs(lat - advLat) > 0.000005f ||
                abs(lon - advLon) > 0.000005f ||
                altMeters != advAlt ||
                spdTenths != advSpd

        // Si el anunciador BLE ya está activo con las mismas coordenadas, no lo reiniciamos:
        // Así el hardware BLE emite de forma ininterrumpida a 10 Hz (cada 100ms) y la barra del reloj jamás cae.
        if (isAdvertisingActive && !forceRestart) {
            if (!coordsChanged) return
            if (now - lastAdvUpdateMs < 2500L && advLat != 0.0f) return
        }

        try {
            val bb = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            bb.putFloat(lat)
            bb.putFloat(lon)
            bb.putShort(altMeters.coerceIn(-1000, 15000).toShort())
            bb.putShort(spdTenths.coerceIn(0, 10000).toShort())

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build()

            val primaryData = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(0xE20A, bb.array())
                .build()

            val scanResponse = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(ParcelUuid(EXOSYS_SERVICE_UUID))
                .build()

            adv.stopAdvertising(advertiseCallback)
            adv.startAdvertising(settings, primaryData, scanResponse, advertiseCallback)

            advLat = lat
            advLon = lon
            advAlt = altMeters
            advSpd = spdTenths
            lastAdvUpdateMs = now
            isAdvertisingActive = true
        } catch (e: SecurityException) {
            emitirLogConsola("Sin permisos para anunciar BLE: ${e.localizedMessage}")
        } catch (_: Throwable) {}
    }

    private fun tienePermisosUbicacion(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun emitirLogConsola(msg: String) {
        sendBroadcast(Intent("EXOSYS_LOG").apply { setPackage(packageName); putExtra("MSG", msg) })
    }

    override fun onDestroy() {
        isRunning = false
        isAdvertisingActive = false
        if (isPowerSaveReceiverRegistered) {
            try { unregisterReceiver(powerSaveReceiver) } catch (_: Throwable) {}
        }
        detenerRastreoGnss()
        try { advertiser?.stopAdvertising(advertiseCallback); gattServer?.close() } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Exosys Enlace", NotificationManager.IMPORTANCE_MIN)
            channel.description = "Mantiene la baliza BLE y la Fase 6 GNSS activas para el LilyGo"
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}