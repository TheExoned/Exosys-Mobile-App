package com.example.exosys

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

class ScreenCasterService : Service() {

    private val CHANNEL_ID = "ExosysCasterChannel"
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var savedResultCode: Int = 0
    private var savedResultData: Intent? = null

    @Volatile private var isStreaming = false
    @Volatile private var isScreenInteractive = true
    @Volatile private var projectionRevokedWhileOff = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var TARGET_IP = ""
    private val TARGET_PORT = 8888
    @Volatile private var targetAddress: InetAddress? = null
    private var txThreadRunning = false

    @Volatile private var isSegmentedMode = true
    @Volatile private var scrollY = 0
    @Volatile private var urgentViewportUpdate = false
    private val syncLock = Object()

    private val VIRTUAL_W = 368
    private val VIRTUAL_H = 800
    private val VIEWPORT_H = 464
    private val FULL_W = 208
    private val MIN_FRAME_INTERVAL_MS = 60L

    private fun langIdx(): Int {
        return mapOf(
            "es" to 0, "en" to 1, "zh" to 2, "ja" to 3,
            "hi" to 4, "fr" to 5, "ar" to 6, "ru" to 7
        )[ExonedForgeI18n.getLang(this)] ?: 0
    }

    private fun trCaster(key: String): String {
        val idx = langIdx()
        val dict = mapOf(
            "rom_paused" to arrayOf(
                "ROM pausó captura por pantalla apagada. Esperando encendido...",
                "ROM paused capture due to screen off. Waiting for screen on...",
                "系统因熄屏暂停了画面捕获。正在等待亮屏...",
                "画面消灯によりROMがキャプチャを一時停止しました。点灯待機中...",
                "स्क्रीन बंद होने के कारण ROM ने कैप्चर रोका। स्क्रीन चालू होने की प्रतीक्षा...",
                "Capture mise en pause par la ROM (écran éteint). En attente d'allumage...",
                "أوقف النظام الالتقاط بسبب إيقاف الشاشة. في انتظار التشغيل...",
                "Система приостановила захват из-за выключения экрана. Ожидание..."
            ),
            "tx_stopped" to arrayOf(
                "Transmisión detenida manualmente.",
                "Transmission stopped manually.",
                "传输已手动停止。",
                "送信が手動で停止されました。",
                "प्रसारण मैनुअल रूप से रोका गया।",
                "Transmission arrêtée manuellement.",
                "تم إيقاف البث يدويًا.",
                "Передача остановлена вручную."
            ),
            "scr_off" to arrayOf(
                "Pantalla apagada: Enlace UDP en guardia activa.",
                "Screen off: UDP link on active standby.",
                "屏幕已关闭：UDP 链路处于活动待命状态。",
                "画面消灯: UDPリンクはアクティブ待機中。",
                "स्क्रीन बंद: UDP लिंक सक्रिय स्टैंडबाय पर।",
                "Écran éteint : Lien UDP en veille active.",
                "الشاشة مغلقة: رابط UDP في وضع الاستعداد النشط.",
                "Экран выключен: UDP-канал в активном резерве."
            ),
            "scr_on" to arrayOf(
                "Pantalla encendida: Reanudando flujo UDP.",
                "Screen on: Resuming UDP stream.",
                "屏幕已点亮：正在恢复 UDP 视频流。",
                "画面点灯: UDPストリームを再開中。",
                "स्क्रीन चालू: UDP स्ट्रीम फिर से शुरू हो रही है।",
                "Écran allumé : Reprise du flux UDP.",
                "الشاشة مفتوحة: استئناف تدفق UDP.",
                "Экран включён: Возобновление потока UDP."
            ),
            "wakelock_ok" to arrayOf(
                "Anti-Suspensión activo: La pantalla no se apagará sola.",
                "Anti-Sleep active: Screen will not turn off automatically.",
                "防休眠已激活：屏幕不会自动熄灭。",
                "スリープ防止有効: 画面は自動消灯しません。",
                "एंटी-स्लीप सक्रिय: स्क्रीन अपने आप बंद नहीं होगी।",
                "Anti-veille actif : L'écran ne s'éteindra pas tout seul.",
                "منع السكون نشط: لن تنطفئ الشاشة تلقائيًا.",
                "Анти-сон активен: Экран не погаснет автоматически."
            ),
            "notif_title" to arrayOf(
                "Control remoto de pantalla",
                "Remote Screen Control",
                "远程屏幕控制",
                "リモート画面制御",
                "रिमोट स्क्रीन नियंत्रण",
                "Contrôle d'écran à distance",
                "التحكم عن بعد في الشاشة",
                "Удалённое управление экраном"
            ),
            "notif_text" to arrayOf(
                "Transmisión de Pantalla Activa (Persistente)",
                "Active Screen Transmission (Persistent)",
                "屏幕传输运行中（持久模式）",
                "画面送信アクティブ（常駐）",
                "स्क्रीन प्रसारण सक्रिय (स्थायी)",
                "Transmission d'écran active (Persistante)",
                "بث الشاشة نشط (مستمر)",
                "Активная передача экрана (Постоянная)"
            ),
            "proj_denied" to arrayOf(
                "ERROR: Proyección visual denegada.",
                "ERROR: Visual projection denied.",
                "错误：屏幕投影权限被拒绝。",
                "エラー: 画面投影が拒否されました。",
                "त्रुटि: स्क्रीन प्रोजेक्शन अस्वीकृत।",
                "ERREUR : Projection visuelle refusée.",
                "خطأ: تم رفض عرض الشاشة.",
                "ОШИБКА: Проекция экрана отклонена."
            ),
            "hot_restored" to arrayOf(
                "Captura restaurada en caliente tras encender pantalla.",
                "Capture hot-restored after screen on.",
                "亮屏后已热恢复画面捕获。",
                "画面点灯後にキャプチャをホットリストアしました。",
                "स्क्रीन चालू होने के बाद कैप्चर तुरंत बहाल किया गया।",
                "Capture restaurée à chaud après l'allumage de l'écran.",
                "تمت استعادة الالتقاط فورًا بعد تشغيل الشاشة.",
                "Захват восстановлен на горячую после включения экрана."
            ),
            "relink_err" to arrayOf(
                "Error al solicitar re-enlace:",
                "Error requesting re-link:",
                "请求重新连接时出错：",
                "再リンク要求エラー:",
                "पुनः लिंक का अनुरोध करने में त्रुटि:",
                "Erreur lors de la demande de reliaison :",
                "خطأ في طلب إعادة الربط:",
                "Ошибка запроса переподключения:"
            ),
            "handshake_ok" to arrayOf(
                ">>> HANDSHAKE RECIBIDO: Enlace bidireccional activo.",
                ">>> HANDSHAKE RECEIVED: Bidirectional link active.",
                ">>> 收到握手信号：双向链路已激活。",
                ">>> ハンドシェイク受信: 双方向リンクが有効です。",
                ">>> हैंडशेक प्राप्त हुआ: द्विदिश लिंक सक्रिय।",
                ">>> HANDSHAKE REÇU : Lien bidirectionnel actif.",
                ">>> تم استلام المصافحة: الرابط ثنائي الاتجاه نشط.",
                ">>> РУКОПОЖАТИЕ ПОЛУЧЕНО: Двусторонняя связь активна."
            ),
            "toast_video_ok" to arrayOf(
                "¡ENLACE DE VIDEO CONFIRMADO!",
                "VIDEO LINK CONFIRMED!",
                "视频链路已确认！",
                "ビデオリンク確認完了！",
                "वीडियो लिंक की पुष्टि हो गई!",
                "LIEN VIDÉO CONFIRMÉ !",
                "تم تأكيد رابط الفيديو!",
                "ВИДЕОКАНАЛ ПОДТВЕРЖДЁН!"
            ),
            "acc_alert" to arrayOf(
                "ALERTA: Activa 'Exosys Tactical Touch' en Accesibilidad.",
                "ALERT: Enable 'Exosys Tactical Touch' in Accessibility.",
                "警告：请在无障碍设置中启用 'Exosys Tactical Touch'。",
                "警告: ユーザー補助で「Exosys Tactical Touch」を有効にしてください。",
                "चेतावनी: एक्सेसिबिलिटी में 'Exosys Tactical Touch' चालू करें।",
                "ALERTE : Activez 'Exosys Tactical Touch' dans l'Accessibilité.",
                "تنبيه: فعّل 'Exosys Tactical Touch' في إمكانية الوصول.",
                "ВНИМАНИЕ: Включите 'Exosys Tactical Touch' в Спец. возможностях."
            ),
            "udp_bind_fail" to arrayOf(
                "Fallo al amarrar socket UDP. Reintentando...",
                "Failed to bind UDP socket. Retrying...",
                "绑定 UDP 套接字失败，正在重试...",
                "UDPソケットのバインドに失敗しました。再試行中...",
                "UDP सॉकेट बाइंड करने में विफल। पुनः प्रयास कर रहा है...",
                "Échec de liaison du socket UDP. Nouvel essai...",
                "فشل ربط منفذ UDP. جارٍ إعادة المحاولة...",
                "Ошибка привязки UDP-сокета. Повтор..."
            )
        )
        return dict[key]?.getOrElse(idx) { dict[key]!![0] } ?: key
    }

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val screenOnNow = pm.isInteractive

            if (!screenOnNow || !isScreenInteractive) {
                projectionRevokedWhileOff = true
                isScreenInteractive = false
                sendLogToApp(trCaster("rom_paused"))
            } else {
                isStreaming = false
                triggerImmediateViewport()
                sendLogToApp(trCaster("tx_stopped"))
                stopSelf()
            }
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenInteractive = false
                    sendLogToApp(trCaster("scr_off"))
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    isScreenInteractive = true
                    if (projectionRevokedWhileOff) {
                        projectionRevokedWhileOff = false
                        restaurarCapturaTrasEncendido()
                    } else {
                        sendLogToApp(trCaster("scr_on"))
                        triggerImmediateViewport()
                    }
                }
            }
        }
    }

    private fun sendLogToApp(msg: String) {
        val intent = Intent("EXOSYS_LOG")
        intent.setPackage(packageName)
        intent.putExtra("MSG", msg)
        sendBroadcast(intent)
    }

    override fun onCreate() {
        super.onCreate()
        crearCanalNotificacion()
        adquirirBlindajeDeEnergia()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(screenStateReceiver, filter)
        }
    }

    private fun adquirirBlindajeDeEnergia() {
        try {
            val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val levelAndFlags = PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE
            wakeLock = pm.newWakeLock(levelAndFlags, "Exosys::CasterScreenLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            sendLogToApp(trCaster("wakelock_ok"))
        } catch (e: Exception) {
            sendLogToApp("WakeLock: ${e.message}")
        }

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(lockMode, "Exosys::CasterWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            sendLogToApp("WifiLock: ${e.message}")
        }
    }

    private fun liberarBlindajeDeEnergia() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_CASTING") {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == "UPDATE_TARGET_IP") {
            val newIp = intent.getStringExtra("NEW_IP")
            if (!newIp.isNullOrBlank()) {
                TARGET_IP = newIp
                targetAddress = try { InetAddress.getByName(TARGET_IP) } catch (e: Exception) { null }
            }
            return START_STICKY
        }

        if (intent?.action == "INCOMING_COMMAND") {
            val cmd = intent.getStringExtra("CMD")
            if (cmd != null) {
                procesarComandoDesdeReloj(cmd)
            }
            return START_STICKY
        }

        val resultCode = intent?.getIntExtra("RESULT_CODE", 0) ?: 0
        TARGET_IP = intent?.getStringExtra("TARGET_IP") ?: TARGET_IP.ifEmpty { "192.168.4.1" }
        if (TARGET_IP.isNotEmpty()) {
            targetAddress = try { InetAddress.getByName(TARGET_IP) } catch (e: Exception) { null }
        }

        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        if (resultCode != 0 && data != null) {
            savedResultCode = resultCode
            savedResultData = Intent(data)

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(trCaster("notif_title"))
                .setContentText(trCaster("notif_text"))
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(2, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(2, notification)
            }

            try { mediaProjection?.unregisterCallback(mediaProjectionCallback) } catch (_: Exception) {}
            try { virtualDisplay?.release() } catch (_: Exception) {}
            try { imageReader?.close() } catch (_: Exception) {}

            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            if (mediaProjection != null) {
                projectionRevokedWhileOff = false
                isScreenInteractive = true
                montarSuperficieVirtual()
                if (!txThreadRunning) iniciarHiloTransmisor()
                triggerImmediateViewport()
            } else {
                sendLogToApp(trCaster("proj_denied"))
                stopSelf()
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun montarSuperficieVirtual() {
        imageReader = ImageReader.newInstance(VIRTUAL_W, VIRTUAL_H, PixelFormat.RGBA_8888, 2)
        mediaProjection?.registerCallback(mediaProjectionCallback, Handler(Looper.getMainLooper()))
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ExosysCaster", VIRTUAL_W, VIRTUAL_H, 160,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun restaurarCapturaTrasEncendido() {
        try {
            if (savedResultCode != 0 && savedResultData != null) {
                val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                try { virtualDisplay?.release() } catch (_: Exception) {}
                try { imageReader?.close() } catch (_: Exception) {}
                mediaProjection = projectionManager.getMediaProjection(savedResultCode, Intent(savedResultData))
                if (mediaProjection != null) {
                    montarSuperficieVirtual()
                    sendLogToApp(trCaster("hot_restored"))
                    triggerImmediateViewport()
                    return
                }
            }
        } catch (_: Exception) {}

        try {
            val relinkIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("AUTO_RECAST", true)
            }
            startActivity(relinkIntent)
        } catch (e: Exception) {
            sendLogToApp("${trCaster("relink_err")} ${e.message}")
        }
    }

    private fun triggerImmediateViewport() {
        urgentViewportUpdate = true
        synchronized(syncLock) {
            syncLock.notifyAll()
        }
    }

    private fun procesarComandoDesdeReloj(comando: String) {
        when {
            comando.contains("ACK_VISION") -> {
                sendLogToApp(trCaster("handshake_ok"))
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, trCaster("toast_video_ok"), Toast.LENGTH_LONG).show()
                }
                triggerImmediateViewport()
            }
            comando.contains("SCROLL_UP") -> {
                val nextY = scrollY - 120
                scrollY = if (nextY < 0) 0 else nextY
                triggerImmediateViewport()
            }
            comando.contains("SCROLL_DOWN") -> {
                val maxScroll = VIRTUAL_H - VIEWPORT_H
                val nextY = scrollY + 120
                scrollY = if (nextY > maxScroll) maxScroll else nextY
                triggerImmediateViewport()
            }
            comando.contains("MODE_FULL") -> {
                isSegmentedMode = false
                triggerImmediateViewport()
            }
            comando.contains("MODE_SEG") -> {
                isSegmentedMode = true
                triggerImmediateViewport()
            }
            comando.startsWith("TOUCH:") -> {
                procesarToqueRemoto(comando.removePrefix("TOUCH:"))
            }
        }
    }

    private fun procesarToqueRemoto(payload: String) {
        try {
            val parts = payload.split(",")
            if (parts.size < 5) return

            val x1 = parts[0].toFloat()
            val y1 = parts[1].toFloat()
            val x2 = parts[2].toFloat()
            val y2 = parts[3].toFloat()
            val dur = parts[4].toLong()

            val touchSvc = ExosysTouchService.instance
            if (touchSvc == null) {
                sendLogToApp(trCaster("acc_alert"))
                return
            }

            val vx1: Float
            val vy1: Float
            val vx2: Float
            val vy2: Float

            if (isSegmentedMode) {
                val maxScroll = (VIRTUAL_H - VIEWPORT_H).toFloat()
                val curScroll = scrollY.toFloat().coerceIn(0f, maxScroll)
                vx1 = x1
                vy1 = curScroll + y1
                vx2 = x2
                vy2 = curScroll + y2
            } else {
                val xOffset = ((VIRTUAL_W - FULL_W) / 2).toFloat()
                if (x1 < xOffset || x1 > xOffset + FULL_W) return
                vx1 = ((x1 - xOffset) / FULL_W.toFloat()) * VIRTUAL_W.toFloat()
                vy1 = (y1 / VIEWPORT_H.toFloat()) * VIRTUAL_H.toFloat()
                vx2 = (((x2 - xOffset).coerceIn(0f, FULL_W.toFloat())) / FULL_W.toFloat()) * VIRTUAL_W.toFloat()
                vy2 = (y2 / VIEWPORT_H.toFloat()) * VIRTUAL_H.toFloat()
            }

            val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val realW: Float
            val realH: Float
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.maximumWindowMetrics.bounds
                realW = bounds.width().toFloat()
                realH = bounds.height().toFloat()
            } else {
                val dm = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                realW = dm.widthPixels.toFloat()
                realH = dm.heightPixels.toFloat()
            }

            val scale = kotlin.math.min(VIRTUAL_W.toFloat() / realW, VIRTUAL_H.toFloat() / realH)
            val drawnW = realW * scale
            val drawnH = realH * scale
            val padX = (VIRTUAL_W.toFloat() - drawnW) / 2f
            val padY = (VIRTUAL_H.toFloat() - drawnH) / 2f

            val phoneX1 = (((vx1 - padX) / drawnW) * realW).coerceIn(1f, realW - 1f)
            val phoneY1 = (((vy1 - padY) / drawnH) * realH).coerceIn(1f, realH - 1f)
            val phoneX2 = (((vx2 - padX) / drawnW) * realW).coerceIn(1f, realW - 1f)
            val phoneY2 = (((vy2 - padY) / drawnH) * realH).coerceIn(1f, realH - 1f)

            touchSvc.inyectarGesto(phoneX1, phoneY1, phoneX2, phoneY2, dur)

            val h = Handler(Looper.getMainLooper())
            h.postDelayed({ triggerImmediateViewport() }, 80)
            h.postDelayed({ triggerImmediateViewport() }, 250)
        } catch (e: Exception) {
            sendLogToApp("Touch RX Error: ${e.message}")
        }
    }

    private fun iniciarHiloTransmisor() {
        isStreaming = true
        txThreadRunning = true
        var framesSent = 0

        thread {
            var socket: DatagramSocket? = null

            try {
                socket = DatagramSocket()
                socket.sendBufferSize = 65536
            } catch (e: Exception) {
                sendLogToApp(trCaster("udp_bind_fail"))
            }

            val stream = ByteArrayOutputStream(16384)

            var rawBufferBitmap: Bitmap? = null
            var allocatedRowWidth = 0
            var hasValidFrame = false

            val segViewportBitmap = Bitmap.createBitmap(VIRTUAL_W, VIEWPORT_H, Bitmap.Config.RGB_565)
            val segCanvas = Canvas(segViewportBitmap)

            val fullViewportBitmap = Bitmap.createBitmap(FULL_W, VIEWPORT_H, Bitmap.Config.RGB_565)
            val fullCanvas = Canvas(fullViewportBitmap)

            val srcRect = Rect()
            val dstRectSeg = Rect(0, 0, VIRTUAL_W, VIEWPORT_H)
            val dstRectFull = Rect(0, 0, FULL_W, VIEWPORT_H)

            var lastSendTime = 0L

            while (isStreaming) {
                try {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (!pm.isInteractive && !urgentViewportUpdate) {
                        try { imageReader?.acquireLatestImage()?.close() } catch (_: Exception) {}
                        synchronized(syncLock) {
                            syncLock.wait(200)
                        }
                        continue
                    }

                    val now = SystemClock.elapsedRealtime()
                    val isUrgent = urgentViewportUpdate
                    val elapsedSinceLast = now - lastSendTime

                    if (!isUrgent && elapsedSinceLast < MIN_FRAME_INTERVAL_MS) {
                        val waitMs = MIN_FRAME_INTERVAL_MS - elapsedSinceLast

                        synchronized(syncLock) {
                            if (!urgentViewportUpdate) {
                                syncLock.wait(waitMs)
                            }
                        }
                        continue
                    }

                    var newSurfaceFrame = false
                    val image = try { imageReader?.acquireLatestImage() } catch (_: Exception) { null }
                    if (image != null) {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - (pixelStride * VIRTUAL_W)
                        val bufferWidth = VIRTUAL_W + (rowPadding / pixelStride)

                        if (rawBufferBitmap == null || allocatedRowWidth != bufferWidth) {
                            rawBufferBitmap?.recycle()
                            rawBufferBitmap = Bitmap.createBitmap(bufferWidth, VIRTUAL_H, Bitmap.Config.ARGB_8888)
                            allocatedRowWidth = bufferWidth
                        }

                        buffer.rewind()
                        rawBufferBitmap!!.copyPixelsFromBuffer(buffer)
                        image.close()
                        hasValidFrame = true
                        newSurfaceFrame = true
                    }

                    val isHeartbeat = (SystemClock.elapsedRealtime() - lastSendTime) >= 400L

                    if ((newSurfaceFrame || isUrgent || isHeartbeat) && hasValidFrame && rawBufferBitmap != null) {
                        urgentViewportUpdate = false
                        lastSendTime = SystemClock.elapsedRealtime()

                        val segMode = isSegmentedMode
                        val maxScroll = VIRTUAL_H - VIEWPORT_H
                        val currentScroll = scrollY.coerceIn(0, maxScroll)

                        val targetBitmap = if (segMode) {
                            srcRect.set(0, currentScroll, VIRTUAL_W, currentScroll + VIEWPORT_H)
                            segCanvas.drawBitmap(rawBufferBitmap!!, srcRect, dstRectSeg, null)
                            segViewportBitmap
                        } else {
                            srcRect.set(0, 0, VIRTUAL_W, VIRTUAL_H)
                            fullCanvas.drawBitmap(rawBufferBitmap!!, srcRect, dstRectFull, null)
                            fullViewportBitmap
                        }

                        stream.reset()
                        targetBitmap.compress(Bitmap.CompressFormat.JPEG, 18, stream)
                        val bytes = stream.toByteArray()

                        val MAX_CHUNK_SIZE = 1400
                        val totalChunks = ((bytes.size + MAX_CHUNK_SIZE - 1) / MAX_CHUNK_SIZE)

                        if (totalChunks in 1..22 && targetAddress != null) {
                            val frameId = (framesSent % 256).toByte()
                            val headerByte2 = if (segMode) (totalChunks or 0x80).toByte() else totalChunks.toByte()

                            for (i in 0 until totalChunks) {
                                val start = i * MAX_CHUNK_SIZE
                                val remaining = bytes.size - start

                                val length = if (remaining < MAX_CHUNK_SIZE) remaining else MAX_CHUNK_SIZE
                                val chunkData = ByteArray(length + 4)

                                chunkData[0] = 0xEA.toByte()
                                chunkData[1] = frameId
                                chunkData[2] = headerByte2
                                chunkData[3] = i.toByte()

                                System.arraycopy(bytes, start, chunkData, 4, length)

                                val packet = DatagramPacket(chunkData, chunkData.size, targetAddress, TARGET_PORT)
                                try {
                                    socket?.send(packet)
                                } catch (e: Exception) {}

                                if (totalChunks > 2) LockSupport.parkNanos(550_000L)
                            }

                            framesSent++
                            if (framesSent == 1) {
                                val idx = langIdx()
                                val txMsg = arrayOf(
                                    "TX Activo: ${bytes.size} bytes en $totalChunks fragmentos.",
                                    "TX Active: ${bytes.size} bytes in $totalChunks chunks.",
                                    "TX 已激活：${bytes.size} 字节（共 $totalChunks 个分片）。",
                                    "TX稼働中: ${bytes.size} バイト ($totalChunks チャンク)。",
                                    "TX सक्रिय: $totalChunks खंडों में ${bytes.size} बाइट्स।",
                                    "TX Actif : ${bytes.size} octets en $totalChunks fragments.",
                                    "البث نشط: ${bytes.size} بايت في $totalChunks أجزاء.",
                                    "TX Активен: ${bytes.size} байт в $totalChunks фрагментах."
                                )[idx]
                                sendLogToApp(txMsg)
                            }
                        }
                    } else {
                        synchronized(syncLock) {
                            if (!urgentViewportUpdate) {
                                syncLock.wait(12)
                            }
                        }
                    }
                } catch (_: Exception) {
                    Thread.sleep(50)
                }
            }

            txThreadRunning = false
            rawBufferBitmap?.recycle()
            segViewportBitmap.recycle()
            fullViewportBitmap.recycle()
            socket?.close()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isStreaming = false
        triggerImmediateViewport()
        try { unregisterReceiver(screenStateReceiver) } catch (_: Exception) {}
        try { mediaProjection?.unregisterCallback(mediaProjectionCallback) } catch (_: Exception) {}
        liberarBlindajeDeEnergia()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Exosys Screen Caster",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}