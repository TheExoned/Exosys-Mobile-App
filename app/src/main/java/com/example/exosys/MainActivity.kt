package com.example.exosys

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.PrintWriter
import java.io.StringWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.system.exitProcess

object ExosysMainI18n {
    private val LANG_IDX = mapOf(
        "es" to 0, "en" to 1, "zh" to 2, "ja" to 3,
        "hi" to 4, "fr" to 5, "ar" to 6, "ru" to 7
    )

    private val DICT = mapOf(
        "btn_lang" to arrayOf("🌐 IDIOMA", "🌐 LANG", "🌐 语言", "🌐 言語", "🌐 भाषा", "🌐 LANGUE", "🌐 اللغة", "🌐 ЯЗЫК"),
        "settings_short" to arrayOf("Ajustes >", "Settings >", "设置 >", "設定 >", "सेटिंग्स >", "Réglages >", "إعدادات >", "Настр. >"),
        "st_off" to arrayOf("Apagado", "Off", "已关闭", "オフ", "बंद", "Éteint", "متوقف", "Выкл"),
        "st_ready" to arrayOf("Listo", "Ready", "就绪", "準備完了", "तैयार", "Prêt", "جاهز", "Готов"),
        "st_linking" to arrayOf("Enlazando...", "Linking...", "连接中...", "接続中...", "जुड़ रहा है...", "Connexion...", "جارٍ الربط...", "Связь..."),
        "st_linked" to arrayOf("Enlazado", "Linked", "已连接", "接続済み", "जुड़ा हुआ", "Connecté", "متصل", "Связан"),
        "st_tx" to arrayOf("BLE: Activo", "BLE: Active", "BLE: 广播中", "BLE: 送信中", "BLE: सक्रिय", "BLE: Actif", "BLE: نشط", "BLE: Активен"),
        "btn_cast" to arrayOf(
            "INICIAR CONTROL INALÁMBRICO",
            "START WIRELESS CONTROL",
            "启动无线投屏控制",
            "ワイヤレス制御を開始",
            "वायरलेस नियंत्रण शुरू करें",
            "DÉMARRER CONTRÔLE SANS FIL",
            "بدء التحكم اللاسلكي",
            "ЗАПУСТИТЬ БЕСПРОВОДНОЙ КОНТРОЛЬ"
        ),
        "btn_maps" to arrayOf(
            "MAPAS",
            "MAPS",
            "地图",
            "マップ",
            "नक्शे",
            "CARTES",
            "الخرائط",
            "КАРТЫ"
        ),
        "btn_wallpapers" to arrayOf(
            "FONDOS DE PANTALLA",
            "WALLPAPERS",
            "壁纸",
            "壁紙",
            "वॉलपेपर",
            "FONDS D'ÉCRAN",
            "خلفيات الشاشة",
            "ОБОИ"
        ),
        "btn_api" to arrayOf(
            "SISTEMA Y API",
            "SYSTEM & API",
            "系统与 API 设置",
            "システム＆API設定",
            "सिस्टम और API",
            "SYSTÈME ET API",
            "النظام و API",
            "СИСТЕМА И API"
        ),
        "con_udp_listen" to arrayOf(
            "> Escuchando nodo de autodescubrimiento en el puerto UDP 8889...",
            "> Listening for auto-discovery node on UDP port 8889...",
            "> 正在监听 UDP 端口 8889 上的自动发现节点...",
            "> UDPポート8889で自動検出ノードを待機中...",
            "> UDP पोर्ट 8889 पर ऑटो-डिस्कवरी नोड सुन रहा है...",
            "> Écoute du nœud d'auto-découverte sur le port UDP 8889...",
            "> الاستماع لعقدة الاكتشاف التلقائي على منفذ UDP 8889...",
            "> Прослушивание узла автообнаружения на UDP порту 8889..."
        ),
        "con_perm_req" to arrayOf(
            "[!] Se requieren permisos de Ubicación y Bluetooth para enlazar.",
            "[!] Location and Bluetooth permissions are required to link.",
            "[!] 需要位置和蓝牙权限才能建立连接。",
            "[!] リンクには位置情報とBluetoothの権限が必要です。",
            "[!] लिंक करने के लिए लोकेशन और ब्लूटूथ अनुमतियों की आवश्यकता है।",
            "[!] Les autorisations de localisation et Bluetooth sont requises pour lier.",
            "[!] يلزم توفر أذونات الموقع والبلوتوث للربط.",
            "[!] Для связи требуются разрешения геолокации и Bluetooth."
        ),
        "con_tx_screen" to arrayOf(
            "TX Pantalla hacia:",
            "Screen TX to:",
            "投屏传输至：",
            "画面送信先:",
            "स्क्रीन TX लक्ष्य:",
            "TX Écran vers :",
            "إرسال الشاشة إلى:",
            "TX экрана на:"
        ),
        "con_cast_denied" to arrayOf(
            "ERROR: Permiso de captura denegado.",
            "ERROR: Screen capture permission denied.",
            "错误：屏幕录制权限被拒绝。",
            "エラー: 画面キャプチャの権限が拒否されました。",
            "त्रुटि: स्क्रीन कैप्चर अनुमति अस्वीकृत।",
            "ERREUR : Autorisation de capture d'écran refusée.",
            "خطأ: تم رفض إذن التقاط الشاشة.",
            "ОШИБКА: Доступ к захвату экрана отклонён."
        ),
        "con_link_ok" to arrayOf(
            "[LINK_OK] LilyGo conectado vía IP:",
            "[LINK_OK] LilyGo connected via IP:",
            "[LINK_OK] LilyGo 已通过 IP 连接：",
            "[LINK_OK] LilyGo IP接続完了:",
            "[LINK_OK] LilyGo IP के माध्यम से जुड़ा:",
            "[LINK_OK] LilyGo connecté via IP :",
            "[LINK_OK] تم اتصال LilyGo عبر IP:",
            "[LINK_OK] LilyGo подключён по IP:"
        ),
        "con_sock_stop" to arrayOf(
            "Socket 8889 detenido:",
            "Socket 8889 stopped:",
            "端口 8889 已停止：",
            "ソケット8889停止:",
            "सॉकेट 8889 रुका:",
            "Socket 8889 arrêté :",
            "توقف المنفذ 8889:",
            "Сокет 8889 остановлен:"
        ),
        "con_qr_ok" to arrayOf(
            "[QR OK] LilyGo detectado en",
            "[QR OK] LilyGo detected at",
            "[QR OK] 检测到 LilyGo 位于",
            "[QR OK] LilyGoを検出:",
            "[QR OK] LilyGo यहाँ मिला:",
            "[QR OK] LilyGo détecté sur",
            "[QR OK] تم اكتشاف LilyGo في",
            "[QR OK] LilyGo обнаружен на"
        ),
        "con_ble_start" to arrayOf(
            "Transmisión BLE iniciada (Baliza 0xE20A + Servidor GATT activos).",
            "BLE transmission started (Beacon 0xE20A + GATT Server active).",
            "BLE 广播已启动（0xE20A 信标 + GATT 服务器已激活）。",
            "BLE送信開始（ビーコン 0xE20A + GATTサーバー稼働中）。",
            "BLE प्रसारण शुरू (बीकन 0xE20A + GATT सर्वर सक्रिय)।",
            "Transmission BLE démarrée (Balise 0xE20A + Serveur GATT actifs).",
            "بدأ بث BLE (المنارة 0xE20A + خادم GATT نشط).",
            "BLE передача запущена (Маяк 0xE20A + GATT-сервер активны)."
        ),
        "con_ble_stop" to arrayOf(
            "Transmisión BLE cortada.",
            "BLE transmission stopped.",
            "BLE 广播已停止。",
            "BLE送信を停止しました。",
            "BLE प्रसारण बंद किया गया।",
            "Transmission BLE coupée.",
            "تم إيقاف بث BLE.",
            "BLE передача остановлена."
        ),
        "con_bt_turning_on" to arrayOf(
            "Encendiendo Bluetooth e iniciando transmisión BLE...",
            "Turning on Bluetooth and starting BLE transmission...",
            "正在打开蓝牙并启动 BLE 广播...",
            "BluetoothをオンにしてBLE送信を開始中...",
            "ब्लूटूथ चालू कर रहा है और BLE प्रसारण शुरू हो रहा है...",
            "Activation du Bluetooth et démarrage de la transmission BLE...",
            "جارٍ تشغيل البلوتوث وبدء بث BLE...",
            "Включение Bluetooth и запуск передачи BLE..."
        ),
        "con_acc_req" to arrayOf(
            "[!] Activa 'Exosys Tactical Touch' en Accesibilidad.",
            "[!] Enable 'Exosys Tactical Touch' in Accessibility settings.",
            "[!] 请在无障碍设置中启用 'Exosys Tactical Touch'。",
            "[!] ユーザー補助設定で「Exosys Tactical Touch」を有効にしてください。",
            "[!] एक्सेसिबिलिटी में 'Exosys Tactical Touch' चालू करें।",
            "[!] Activez 'Exosys Tactical Touch' dans l'Accessibilité.",
            "[!] فعّل 'Exosys Tactical Touch' في إعدادات إمكانية الوصول.",
            "[!] Включите 'Exosys Tactical Touch' в Спец. возможностях."
        ),
        "crash_title" to arrayOf(
            "Reporte de Cierre Previo (Limpiado)",
            "Previous Crash Report (Cleared)",
            "上次崩溃报告（已清除）",
            "前回のクラッシュレポート（消去済）",
            "पिछली क्रैश रिपोर्ट (साफ़ की गई)",
            "Rapport de fermeture précédent (Nettoyé)",
            "تقرير الإغلاق السابق (تم مسحه)",
            "Отчёт о предыдущем сбое (Очищено)"
        ),
        "btn_understood" to arrayOf("ENTENDIDO", "UNDERSTOOD", "知道了", "了解", "समझ गया", "COMPRIS", "مفهوم", "ПОНЯТНО"),
        "toast_wifi" to arrayOf("Ajustes de Wi-Fi.", "Wi-Fi Settings.", "Wi-Fi 设置。", "Wi-Fi設定。", "Wi-Fi सेटिंग्स।", "Paramètres Wi-Fi.", "إعدادات Wi-Fi.", "Настройки Wi-Fi."),
        "hs_title" to arrayOf(
            "RESCATE HOTSPOT (QR O IP MANUAL)",
            "HOTSPOT RESCUE (QR OR MANUAL IP)",
            "热点救援（二维码或手动 IP）",
            "ホットスポット復旧（QRまたは手動IP）",
            "हॉटस्पॉट बचाव (QR या मैनुअल IP)",
            "SECOURS HOTSPOT (QR OU IP MANUELLE)",
            "إنقاذ نقطة الاتصال (QR أو IP يدوي)",
            "РЕЗЕРВ ХОТСПОТА (QR ИЛИ РУЧНОЙ IP)"
        ),
        "hs_desc" to arrayOf(
            "Ve a 'Conexión Inalámbrica -> Hotspot' en el reloj para mostrar el QR.\nSi el router bloquea UDP, escanéalo o escribe la IP a mano.",
            "Go to 'Wireless Connection -> Hotspot' on the watch to show the QR.\nIf the router blocks UDP, scan it or type the IP manually.",
            "在手表上进入“无线连接 -> 热点”以显示二维码。\n如果路由器拦截 UDP，请扫码或手动输入 IP。",
            "時計の「ワイヤレス接続 -> ホットスポット」でQRを表示します。\nUDPがブロックされる場合はQRをスキャンするかIPを手入力してください。",
            "QR दिखाने के लिए घड़ी पर 'वायरलेस कनेक्शन -> हॉटस्पॉट' पर जाएं।\nयदि राउटर UDP को रोकता है, तो स्कैन करें या IP लिखें।",
            "Allez dans 'Connexion sans fil -> Hotspot' sur la montre pour afficher le QR.\nSi le routeur bloque l'UDP, scannez-le ou entrez l'IP manuellement.",
            "انتقل إلى 'اتصال لاسلكي -> نقطة اتصال' في الساعة لإظهار رمز QR.\nإذا حظر الراوتر UDP، امسحه ضوئيًا أو اكتب IP يدويًا.",
            "Откройте 'Беспроводная связь -> Хотспот' на часах для показа QR.\nЕсли роутер блокирует UDP, отсканируйте QR или введите IP вручную."
        ),
        "hs_hint" to arrayOf("IP (Ej: 192.168.43.50)", "IP (e.g. 192.168.43.50)", "IP (例: 192.168.43.50)", "IP (例: 192.168.43.50)", "IP (उदा: 192.168.43.50)", "IP (Ex : 192.168.43.50)", "IP (مثال: 192.168.43.50)", "IP (Напр.: 192.168.43.50)"),
        "btn_scan_qr" to arrayOf("ESCANEAR QR DEL RELOJ", "SCAN WATCH QR", "扫描手表二维码", "時計のQRをスキャン", "घड़ी का QR स्कैन करें", "SCANNER LE QR DE LA MONTRE", "مسح رمز QR للساعة", "СКАНИРОВАТЬ QR ЧАСОВ"),
        "btn_save_close" to arrayOf("GUARDAR Y CERRAR", "SAVE & CLOSE", "保存并关闭", "保存して閉じる", "सहेजें और बंद करें", "ENREGISTRER ET FERMER", "حفظ وإغلاق", "СОХРАНИТЬ И ЗАКРЫТЬ"),
        "toast_qr_invalid" to arrayOf("QR no válido o no es de Exosys", "Invalid or non-Exosys QR", "无效二维码或非 Exosys 二维码", "無効なQRまたはExosys用ではありません", "अमान्य QR या Exosys का नहीं है", "QR invalide ou non Exosys", "رمز QR غير صالح", "Неверный QR-код"),
        "toast_qr_err" to arrayOf("Error al escanear QR:", "Error scanning QR:", "扫码错误：", "QRスキャンエラー:", "QR स्कैन करने में त्रुटि:", "Erreur de scan QR :", "خطأ في مسح QR:", "Ошибка сканирования QR:"),
        "toast_gms_not_ready" to arrayOf(
            "La API del escáner de Google Play Services no está lista.",
            "Google Play Services scanner API is not ready.",
            "Google Play 服务扫码组件尚未就绪。",
            "Google Play開発者サービスのスキャナーAPIが準備できていません。",
            "Google Play Services स्कैनर API तैयार नहीं है।",
            "L'API du scanner Google Play Services n'est pas prête.",
            "واجهة ماسح Google Play غير جاهزة.",
            "API сканера Google Play Services не готов."
        ),
        "gnss_title" to arrayOf("CONTROL GNSS PASIVO", "PASSIVE GNSS CONTROL", "被动 GNSS 定位控制", "パッシブGNSS制御", "निष्क्रिय GNSS नियंत्रण", "CONTRÔLE GNSS PASSIF", "تحكم GNSS السلبي", "ПАССИВНЫЙ КОНТРОЛЬ GNSS"),
        "gnss_desc" to arrayOf(
            "El teléfono inyecta coordenadas POS:lat,lon al reloj cada 1s (Impresión logarítmica en consola: 1s, 10s, 100s...).",
            "The phone injects POS:lat,lon coordinates to the watch every 1s (Logarithmic console output: 1s, 10s, 100s...).",
            "手机每秒向手表注入 POS:lat,lon 坐标（控制台按对数间隔打印：1s, 10s, 100s...）。",
            "スマホから1秒ごとにPOS:lat,lon座標を時計へ注入します（コンソール対数表示: 1s, 10s, 100s...）。",
            "फ़ोन हर 1 सेकंड में घड़ी को POS:lat,lon निर्देशांक भेजता है (कंसोल लॉग: 1s, 10s, 100s...)।",
            "Le téléphone injecte les coordonnées POS:lat,lon à la montre chaque 1s (Affichage logarithmique : 1s, 10s, 100s...).",
            "يقوم الهاتف بحقن إحداثيات POS:lat,lon للساعة كل ثانية (طباعة لوغاريتمية في وحدة التحكم).",
            "Телефон передаёт координаты POS:lat,lon на часы каждую 1с (Логарифмический вывод в консоль: 1с, 10с, 100с...)."
        ),
        "gnss_cb" to arrayOf(
            "Inyección automática de GPS (1s)",
            "Automatic GPS injection (1s)",
            "自动注入 GPS 坐标 (1秒)",
            "自動GPS注入 (1秒)",
            "स्वचालित GPS इंजेक्शन (1s)",
            "Injection GPS automatique (1s)",
            "حقن GPS التلقائي (1 ثانية)",
            "Авто-инъекция GPS (1с)"
        ),
        "gnss_btn_pulse" to arrayOf("ENVIAR PULSO GPS AHORA", "SEND GPS PULSE NOW", "立即发送 GPS 脉冲", "今すぐGPSパルスを送信", "अभी GPS पल्स भेजें", "ENVOYER IMPULSION GPS", "إرسال نبضة GPS الآن", "ОТПРАВИТЬ ИМПУЛЬС GPS"),
        "btn_close" to arrayOf("CERRAR", "CLOSE", "关闭", "閉じる", "बंद करें", "FERMER", "إغلاق", "ЗАКРЫТЬ"),
        "toast_hs_hint" to arrayOf(
            "Toca: Red móvil -> Zona Wi-Fi personal",
            "Tap: Mobile network -> Personal hotspot",
            "请点击：移动网络 -> 个人热点",
            "タップ: モバイルネットワーク -> パーソナルホットスポット",
            "टैप करें: मोबाइल नेटवर्क -> पर्सनल हॉटस्पॉट",
            "Touchez : Réseau mobile -> Point d'accès personnel",
            "اضغط: شبكة الجوال -> نقطة الاتصال الشخصية",
            "Нажмите: Мобильная сеть -> Личная точка доступа"
        )
    )

    fun tr(ctx: Context, key: String): String {
        val code = ExonedForgeI18n.getLang(ctx)
        val idx = LANG_IDX[code] ?: 0
        val arr = DICT[key] ?: return key
        return arr.getOrElse(idx) { arr[0] }
    }

    fun translateServiceLog(ctx: Context, rawMsg: String): String {
        val idx = LANG_IDX[ExonedForgeI18n.getLang(ctx)] ?: 0
        if (idx == 0) return rawMsg

        return rawMsg
            .replace(
                "Fase 6 Activa: Escupiendo GPS/Red cada 1000ms al reloj.",
                arrayOf(
                    "Fase 6 Activa: Escupiendo GPS/Red cada 1000ms al reloj.",
                    "Phase 6 Active: Streaming GPS/Net every 1000ms to watch.",
                    "阶段 6 激活：每 1000ms 向手表发送 GPS/网络定位。",
                    "フェーズ6稼働中: 1000msごとにGPS/ネットワーク位置を時計へ送信中。",
                    "फेज़ 6 सक्रिय: हर 1000ms में घड़ी को GPS/नेट भेज रहा है।",
                    "Phase 6 Active : Diffusion GPS/Réseau toutes les 1000ms vers la montre.",
                    "المرحلة 6 نشطة: بث GPS/الشبكة كل 1000 مللي ثانية للساعة.",
                    "Фаза 6 Активна: Передача GPS/Сети каждые 1000мс на часы."
                )[idx]
            )
            .replace(
                "GNSS Inyectado",
                arrayOf(
                    "GNSS Inyectado",
                    "GNSS Injected",
                    "GNSS 已注入",
                    "GNSS注入完了",
                    "GNSS इंजेक्ट किया गया",
                    "GNSS Injecté",
                    "تم حقن GNSS",
                    "GNSS передан"
                )[idx]
            )
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var frameWifi: FrameLayout
    private lateinit var haloWifi: ImageView
    private lateinit var cirWifi: FrameLayout
    private lateinit var txtWifiState: TextView

    private lateinit var frameHotspot: FrameLayout
    private lateinit var haloHotspot: ImageView
    private lateinit var cirHotspot: FrameLayout
    private lateinit var txtHotspotState: TextView

    private lateinit var frameBt: FrameLayout
    private lateinit var haloBt: ImageView
    private lateinit var cirBt: FrameLayout
    private lateinit var txtBtState: TextView

    private lateinit var tvConsole: TextView
    private lateinit var svConsole: ScrollView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var uiMonitorActive = false
    @Volatile private var autoStartBleWhenBtTurnsOn = false

    private var discoverySocket: DatagramSocket? = null
    @Volatile private var lastKnownWatchIp: String = ""
    @Volatile private var isWatchUdpLinked: Boolean = false
    @Volatile private var lastWatchUdpPulseMs: Long = 0L

    private val hardwareStatusRunnable = object : Runnable {
        override fun run() {
            if (!uiMonitorActive || !::cirWifi.isInitialized) return
            if (autoStartBleWhenBtTurnsOn && esBluetoothRealmenteEncendido()) {
                autoStartBleWhenBtTurnsOn = false
                iniciarTransmisionBle()
            }
            if (System.currentTimeMillis() - lastWatchUdpPulseMs > 8000) {
                isWatchUdpLinked = false
            }
            actualizarNodosDeHardware()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!::tvConsole.isInitialized) return
            val msg = intent.getStringExtra("MSG") ?: return
            val translated = ExosysMainI18n.translateServiceLog(this@MainActivity, msg)
            tvConsole.append("\n> $translated")
            svConsole.post { svConsole.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permisosConcedidos ->
        if (!permisosConcedidos.entries.all { it.value }) {
            if (::tvConsole.isInitialized) {
                tvConsole.append("\n> ${ExosysMainI18n.tr(this, "con_perm_req")}")
            }
        } else if (BleCoreService.isGnssInjectionEnabled(this)) {
            iniciarTransmisionBle()
        }
    }

    private val btEnableLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (esBluetoothRealmenteEncendido()) {
            autoStartBleWhenBtTurnsOn = false
            iniciarTransmisionBle()
        } else {
            abrirPantallaSeleccionBluetooth()
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val sharedPrefs = getSharedPreferences("ExosysBlackBox", Context.MODE_PRIVATE)
            val fallbackIp = sharedPrefs.getString("MANUAL_TARGET_IP", "") ?: ""
            val targetIp = if (isWatchUdpLinked && lastKnownWatchIp.isNotEmpty()) lastKnownWatchIp else fallbackIp

            val serviceIntent = Intent(this, ScreenCasterService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
                putExtra("TARGET_IP", targetIp)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            tvConsole.append("\n> ${ExosysMainI18n.tr(this, "con_tx_screen")} ${if (targetIp.isEmpty()) "Broadcast" else targetIp}")
        } else {
            tvConsole.append("\n> ${ExosysMainI18n.tr(this, "con_cast_denied")}")
        }
    }

    private val exndMediaPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            ExndWallpaperStudioUi.onMediaUriSelected(this, uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, IntentFilter("EXOSYS_LOG"), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, IntentFilter("EXOSYS_LOG"))
        }

        val sharedPrefs = getSharedPreferences("ExosysBlackBox", Context.MODE_PRIVATE)
        val lastCrash = sharedPrefs.getString("CRASH_LOG", null)
        if (lastCrash != null) {
            sharedPrefs.edit().remove("CRASH_LOG").commit()
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            sharedPrefs.edit().putString("CRASH_LOG", sw.toString()).commit()
            defaultHandler?.uncaughtException(t, throwable) ?: exitProcess(1)
        }

        val statusBarId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (statusBarId > 0) resources.getDimensionPixelSize(statusBarId) else 80

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#02080A"))
            fitsSystemWindows = true
            setPadding(32, statusBarHeight + 24, 32, 32)
        }

        val topHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, 16)
        }

        val txtTitle = TextView(this).apply {
            text = "EXOSYS TACTICAL LINK"
            setTextColor(Color.parseColor("#FF003C"))
            textSize = 18.5f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnLang = Button(this).apply {
            text = ExosysMainI18n.tr(this@MainActivity, "btn_lang")
            setTextColor(Color.parseColor("#00E5FF"))
            setBackgroundColor(Color.parseColor("#061A1E"))
            typeface = Typeface.DEFAULT_BOLD
            textSize = 10.5f
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { mostrarDialogoIdioma() }
        }

        topHeaderRow.addView(txtTitle)
        topHeaderRow.addView(btnLang)

        val rowNodes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            }
        }

        // 1. WI-FI
        val colWifi = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        frameWifi = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(dp(70), dp(70)) }
        haloWifi = crearHalo()
        cirWifi = crearCirculoInterno("Wi-Fi")
        frameWifi.addView(haloWifi)
        frameWifi.addView(cirWifi)
        txtWifiState = TextView(this).apply {
            text = "Wi-Fi: ..."
            setTextColor(Color.LTGRAY)
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        val btnAdjWifi = crearBotonAjustesCorto { mostrarAjustesWifi() }
        colWifi.addView(frameWifi)
        colWifi.addView(txtWifiState)
        colWifi.addView(btnAdjWifi)

        // 2. HOTSPOT
        val colHotspot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        frameHotspot = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(dp(70), dp(70)) }
        haloHotspot = crearHalo()
        cirHotspot = crearCirculoInterno("( ( • ) )")
        frameHotspot.addView(haloHotspot)
        frameHotspot.addView(cirHotspot)
        txtHotspotState = TextView(this).apply {
            text = "Hotspot: ..."
            setTextColor(Color.LTGRAY)
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        val btnAdjHotspot = crearBotonAjustesCorto { mostrarAjustesHotspot() }
        colHotspot.addView(frameHotspot)
        colHotspot.addView(txtHotspotState)
        colHotspot.addView(btnAdjHotspot)

        // 3. BLUETOOTH
        val colBt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        frameBt = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(dp(70), dp(70)) }
        haloBt = crearHalo()
        cirBt = crearCirculoInterno("BT")
        frameBt.addView(haloBt)
        frameBt.addView(cirBt)
        txtBtState = TextView(this).apply {
            text = "BLE: ..."
            setTextColor(Color.LTGRAY)
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        val btnAdjBt = crearBotonAjustesCorto { mostrarAjustesBluetooth() }
        colBt.addView(frameBt)
        colBt.addView(txtBtState)
        colBt.addView(btnAdjBt)

        rowNodes.addView(colWifi)
        rowNodes.addView(colHotspot)
        rowNodes.addView(colBt)

        val div1 = android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { bottomMargin = dp(16) }
            setBackgroundColor(Color.parseColor("#061A1E"))
        }

        val btnCast = Button(this).apply {
            text = ExosysMainI18n.tr(this@MainActivity, "btn_cast")
            setBackgroundColor(Color.parseColor("#FFB300"))
            setTextColor(Color.BLACK)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(32, 24, 32, 24)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
            setOnClickListener { solicitarCapturaPantalla() }
        }

        val btnMapas = Button(this).apply {
            text = ExosysMainI18n.tr(this@MainActivity, "btn_maps")
            setBackgroundColor(Color.parseColor("#00E5FF"))
            setTextColor(Color.BLACK)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(32, 24, 32, 24)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
            setOnClickListener { CartoApiManager.abrirObtencionDeMapas(this@MainActivity) }
        }

        val btnWallpapers = Button(this).apply {
            text = ExosysMainI18n.tr(this@MainActivity, "btn_wallpapers")
            setBackgroundColor(Color.parseColor("#00FF41"))
            setTextColor(Color.BLACK)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(32, 24, 32, 24)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
            setOnClickListener {
                val fallbackIp = sharedPrefs.getString("MANUAL_TARGET_IP", "") ?: ""
                val targetIp = if (isWatchUdpLinked && lastKnownWatchIp.isNotEmpty()) lastKnownWatchIp else fallbackIp
                ExndWallpaperStudioUi.mostrarEstudioFondosDialog(this@MainActivity, targetIp) {
                    exndMediaPickerLauncher.launch(arrayOf("video/*", "image/*"))
                }
            }
        }

        val btnApi = Button(this).apply {
            text = ExosysMainI18n.tr(this@MainActivity, "btn_api")
            setBackgroundColor(Color.parseColor("#0A1114"))
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 12f
            setPadding(32, 20, 32, 20)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
            setOnClickListener { CartoApiManager.mostrarMenuAjustes(this@MainActivity) }
        }

        tvConsole = TextView(this).apply {
            text = ExosysMainI18n.tr(this@MainActivity, "con_udp_listen")
            setTextColor(Color.parseColor("#00FF41"))
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
        }

        svConsole = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.parseColor("#050A0C"))
            setPadding(24, 24, 24, 24)
            addView(tvConsole)
        }

        layout.addView(topHeaderRow)
        layout.addView(rowNodes)
        layout.addView(div1)
        layout.addView(btnCast)
        layout.addView(btnMapas)
        layout.addView(btnWallpapers)
        layout.addView(btnApi)
        layout.addView(svConsole)

        setContentView(layout)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (lastCrash != null) {
            mostrarDialogoCrashAnterior(lastCrash)
        }

        evaluarPermisosYProceder()
        iniciarListenerInversoUdp()
        if (BleCoreService.isGnssInjectionEnabled(this)) {
            iniciarTransmisionBle()
        }

        verificarAutoReanudacion(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!::cirWifi.isInitialized) return
        uiMonitorActive = true
        mainHandler.removeCallbacks(hardwareStatusRunnable)
        mainHandler.post(hardwareStatusRunnable)
    }

    override fun onPause() {
        if (::cirWifi.isInitialized) {
            uiMonitorActive = false
            mainHandler.removeCallbacks(hardwareStatusRunnable)
        }
        super.onPause()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun mostrarDialogoIdioma() {
        val options = arrayOf(
            "Español (ES)" to "es",
            "English (EN)" to "en",
            "中文 (简体) (ZH)" to "zh",
            "日本語 (JA)" to "ja",
            "हिन्दी (HI)" to "hi",
            "Français (FR)" to "fr",
            "العربية (AR)" to "ar",
            "Русский (RU)" to "ru"
        )
        AlertDialog.Builder(this)
            .setTitle("Language / Idioma")
            .setItems(options.map { it.first }.toTypedArray()) { dialog, which ->
                val code = options[which].second
                ExonedForgeI18n.setLang(this, code)
                dialog.dismiss()
                recreate()
            }
            .show()
    }

    private fun mostrarDialogoCrashAnterior(crashLog: String) {
        val scroll = ScrollView(this)
        val txt = TextView(this).apply {
            text = crashLog
            setTextColor(Color.parseColor("#FF003C"))
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        scroll.addView(txt)
        AlertDialog.Builder(this)
            .setTitle(ExosysMainI18n.tr(this, "crash_title"))
            .setView(scroll)
            .setPositiveButton(ExosysMainI18n.tr(this, "btn_understood"), null)
            .show()
    }

    private fun crearHalo(): ImageView {
        return ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(70), dp(70))
            setImageResource(android.R.drawable.ic_menu_rotate)
            setColorFilter(Color.parseColor("#00E5FF"))
            visibility = android.view.View.INVISIBLE
        }
    }

    private fun crearCirculoInterno(texto: String): FrameLayout {
        val f = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#141414"))
                setStroke(dp(2), Color.DKGRAY)
            }
        }
        val t = TextView(this).apply {
            text = texto
            setTextColor(Color.GRAY)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        f.addView(t)
        return f
    }

    private fun crearBotonAjustesCorto(onClick: () -> Unit): Button {
        return Button(this).apply {
            text = ExosysMainI18n.tr(this@MainActivity, "settings_short")
            textSize = 9f
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dp(80), dp(28)).apply { topMargin = dp(4) }
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#00E5FF"))
            setOnClickListener { onClick() }
        }
    }

    private fun aplicarEstiloNodo(cir: FrameLayout, halo: ImageView, txt: TextView, estado: Int, nombre: String) {
        val bg = cir.background as GradientDrawable
        val t = cir.getChildAt(0) as TextView
        halo.clearAnimation()

        when (estado) {
            1 -> {
                bg.setColor(Color.parseColor("#141414"))
                bg.setStroke(dp(2), Color.DKGRAY)
                t.setTextColor(Color.GRAY)
                halo.visibility = android.view.View.INVISIBLE
                txt.text = "$nombre: ${ExosysMainI18n.tr(this, "st_off")}"
                txt.setTextColor(Color.GRAY)
            }
            2 -> {
                bg.setColor(Color.parseColor("#061C30"))
                bg.setStroke(dp(2), Color.parseColor("#007AFF"))
                t.setTextColor(Color.WHITE)
                halo.visibility = android.view.View.INVISIBLE
                txt.text = "$nombre: ${ExosysMainI18n.tr(this, "st_ready")}"
                txt.setTextColor(Color.parseColor("#007AFF"))
            }
            3 -> {
                bg.setColor(Color.parseColor("#005066"))
                bg.setStroke(dp(2), Color.parseColor("#00E5FF"))
                t.setTextColor(Color.WHITE)
                halo.visibility = android.view.View.VISIBLE
                val anim = RotateAnimation(0f, 360f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f)
                anim.duration = 1500
                anim.interpolator = LinearInterpolator()
                anim.repeatCount = Animation.INFINITE
                halo.startAnimation(anim)
                txt.text = ExosysMainI18n.tr(this, "st_linking")
                txt.setTextColor(Color.parseColor("#00E5FF"))
            }
            4 -> {
                bg.setColor(Color.parseColor("#00A020"))
                bg.setStroke(dp(2), Color.parseColor("#00FF41"))
                t.setTextColor(Color.BLACK)
                halo.visibility = android.view.View.INVISIBLE
                txt.text = ExosysMainI18n.tr(this, "st_linked")
                txt.setTextColor(Color.parseColor("#00FF41"))
            }
        }
    }

    private fun isWifiEnabledReal(): Boolean {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wm?.isWifiEnabled == true
        } catch (_: Throwable) { false }
    }

    private fun actualizarNodosDeHardware() {
        val hsOn = esHotspotRealmenteEncendido()
        val btOn = esBluetoothRealmenteEncendido()
        val bleRunning = btOn && BleCoreService.isRunning

        if (isWifiEnabledReal()) {
            aplicarEstiloNodo(cirWifi, haloWifi, txtWifiState, 2, "Wi-Fi")
        } else {
            aplicarEstiloNodo(cirWifi, haloWifi, txtWifiState, 1, "Wi-Fi")
        }

        if (hsOn) {
            if (isWatchUdpLinked) {
                aplicarEstiloNodo(cirHotspot, haloHotspot, txtHotspotState, 4, "Hotspot")
            } else {
                aplicarEstiloNodo(cirHotspot, haloHotspot, txtHotspotState, 3, "Hotspot")
            }
            cirHotspot.setOnClickListener { abrirHotspotUniversalSinRebote() }
        } else {
            aplicarEstiloNodo(cirHotspot, haloHotspot, txtHotspotState, 1, "Hotspot")
            cirHotspot.setOnClickListener { abrirHotspotUniversalSinRebote() }
        }

        if (btOn) {
            if (bleRunning) {
                aplicarEstiloNodo(cirBt, haloBt, txtBtState, 4, "BLE")
                txtBtState.text = ExosysMainI18n.tr(this, "st_tx")
            } else {
                aplicarEstiloNodo(cirBt, haloBt, txtBtState, 2, "BLE")
            }
            cirBt.setOnClickListener { if (bleRunning) cortarTransmisionBle() else iniciarTransmisionBle() }
        } else {
            aplicarEstiloNodo(cirBt, haloBt, txtBtState, 1, "BLE")
            cirBt.setOnClickListener { activarBluetoothYArrancarTransmision() }
        }

        if (isWatchUdpLinked) {
            tvConsole.post { tvConsole.invalidate() }
        }
    }

    private fun iniciarListenerInversoUdp() {
        thread(start = true, isDaemon = true) {
            try {
                discoverySocket = DatagramSocket(8889)
                val buf = ByteArray(256)
                var receivedLinkOkCount = 0L

                while (true) {
                    val packet = DatagramPacket(buf, buf.size)
                    discoverySocket?.receive(packet)
                    val msg = String(packet.data, 0, packet.length).trim()

                    if (msg.startsWith("HELLO_EXOSYS:")) {
                        val parts = msg.split(":")
                        if (parts.size >= 2) {
                            val wIp = parts[1].trim()
                            lastKnownWatchIp = wIp
                            lastWatchUdpPulseMs = System.currentTimeMillis()
                            isWatchUdpLinked = true

                            val updateIntent = Intent(this@MainActivity, ScreenCasterService::class.java).apply {
                                action = "UPDATE_TARGET_IP"
                                putExtra("NEW_IP", wIp)
                            }
                            try { startService(updateIntent) } catch (_: Throwable) {}

                            receivedLinkOkCount++

                            val shouldLog = when {
                                receivedLinkOkCount <= 1L -> true
                                receivedLinkOkCount <= 10L -> false
                                receivedLinkOkCount <= 100L -> (receivedLinkOkCount % 20L == 0L)
                                else -> (receivedLinkOkCount % 100L == 0L)
                            }

                            if (shouldLog) {
                                mainHandler.post {
                                    if (::tvConsole.isInitialized) {
                                        tvConsole.append("\n> ${ExosysMainI18n.tr(this@MainActivity, "con_link_ok")} $wIp (Ping $receivedLinkOkCount)")
                                        svConsole.fullScroll(ScrollView.FOCUS_DOWN)
                                    }
                                }
                            }
                        }
                    } else if (msg.startsWith("ACK_VISION") || msg.startsWith("SCROLL") || msg.startsWith("MODE_") || msg.startsWith("TOUCH:")) {
                        val serviceIntent = Intent(this@MainActivity, ScreenCasterService::class.java)
                        serviceIntent.action = "INCOMING_COMMAND"
                        serviceIntent.putExtra("CMD", msg)
                        try { startService(serviceIntent) } catch (_: Throwable) {}
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (::tvConsole.isInitialized) {
                        tvConsole.append("\n> ${ExosysMainI18n.tr(this@MainActivity, "con_sock_stop")} ${e.localizedMessage}")
                    }
                }
            }
        }
    }

    private fun mostrarAjustesWifi() {
        Toast.makeText(this, ExosysMainI18n.tr(this, "toast_wifi"), Toast.LENGTH_SHORT).show()
    }

    private fun mostrarAjustesHotspot() {
        val sharedPrefs = getSharedPreferences("ExosysBlackBox", Context.MODE_PRIVATE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply { setColor(Color.parseColor("#061418")); cornerRadius = dp(10).toFloat() }
        }
        val t = TextView(this).apply {
            text = ExosysMainI18n.tr(this@MainActivity, "hs_title")
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        }
        val d = TextView(this).apply {
            text = ExosysMainI18n.tr(this@MainActivity, "hs_desc")
            setTextColor(Color.LTGRAY)
            textSize = 11f
            setPadding(0, 0, 0, dp(12))
        }

        val input = EditText(this).apply {
            hint = ExosysMainI18n.tr(this@MainActivity, "hs_hint")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.DKGRAY)
            setText(sharedPrefs.getString("MANUAL_TARGET_IP", ""))
        }

        var dlg: AlertDialog? = null

        val btnScanQr = Button(this).apply {
            text = ExosysMainI18n.tr(this@MainActivity, "btn_scan_qr")
            setBackgroundColor(Color.parseColor("#FFB300"))
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
            setOnClickListener {
                dlg?.dismiss()
                lanzarEscanerQrGms()
            }
        }

        val btnSave = Button(this).apply {
            text = ExosysMainI18n.tr(this@MainActivity, "btn_save_close")
            setBackgroundColor(Color.parseColor("#00FF41"))
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                sharedPrefs.edit().putString("MANUAL_TARGET_IP", input.text.toString().trim()).apply()
                dlg?.dismiss()
            }
        }

        box.addView(t); box.addView(d); box.addView(input); box.addView(btnScanQr); box.addView(btnSave)
        dlg = AlertDialog.Builder(this).setView(box).create()
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.show()
    }

    private fun lanzarEscanerQrGms() {
        try {
            val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
            val scanner = com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this, options)

            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val rawValue = barcode.rawValue
                    if (rawValue != null && rawValue.startsWith("EXOSYS_IP:")) {
                        val ip = rawValue.removePrefix("EXOSYS_IP:").trim()
                        lastKnownWatchIp = ip
                        isWatchUdpLinked = true
                        lastWatchUdpPulseMs = System.currentTimeMillis()
                        getSharedPreferences("ExosysBlackBox", Context.MODE_PRIVATE).edit().putString("MANUAL_TARGET_IP", ip).apply()

                        tvConsole.append("\n> ${ExosysMainI18n.tr(this, "con_qr_ok")} $ip")
                        svConsole.post { svConsole.fullScroll(ScrollView.FOCUS_DOWN) }
                    } else {
                        Toast.makeText(this, ExosysMainI18n.tr(this, "toast_qr_invalid"), Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "${ExosysMainI18n.tr(this, "toast_qr_err")} ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } catch (_: Exception) {
            Toast.makeText(this, ExosysMainI18n.tr(this, "toast_gms_not_ready"), Toast.LENGTH_LONG).show()
        }
    }

    private fun mostrarAjustesBluetooth() {
        val activeGnss = BleCoreService.isGnssInjectionEnabled(this)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply { setColor(Color.parseColor("#061418")); cornerRadius = dp(10).toFloat() }
        }
        val t = TextView(this).apply {
            text = ExosysMainI18n.tr(this@MainActivity, "gnss_title")
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        }
        val d = TextView(this).apply {
            text = ExosysMainI18n.tr(this@MainActivity, "gnss_desc")
            setTextColor(Color.LTGRAY)
            textSize = 11f
            setPadding(0, 0, 0, dp(12))
        }

        val cbGnss = CheckBox(this).apply {
            text = ExosysMainI18n.tr(this@MainActivity, "gnss_cb")
            setTextColor(Color.WHITE)
            isChecked = activeGnss
            setOnCheckedChangeListener { _, c ->
                BleCoreService.setGnssInjectionEnabled(this@MainActivity, c)
            }
        }

        val btnForcePulse = Button(this).apply {
            text = ExosysMainI18n.tr(this@MainActivity, "gnss_btn_pulse")
            setBackgroundColor(Color.parseColor("#00FF41"))
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
                bottomMargin = dp(8)
            }
            setOnClickListener {
                BleCoreService.triggerImmediatePulse(this@MainActivity)
            }
        }

        var dlg: AlertDialog? = null
        val btnClose = Button(this).apply {
            text = ExosysMainI18n.tr(this@MainActivity, "btn_close")
            setBackgroundColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            setOnClickListener { dlg?.dismiss() }
        }

        box.addView(t); box.addView(d); box.addView(cbGnss); box.addView(btnForcePulse); box.addView(btnClose)
        dlg = AlertDialog.Builder(this).setView(box).create()
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.show()
    }

    private fun esHotspotRealmenteEncendido(): Boolean {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                val methodState = wm.javaClass.getDeclaredMethod("getWifiApState")
                methodState.isAccessible = true
                val state = methodState.invoke(wm) as? Int ?: 0
                if (state == 13 || state == 3) return true
                val methodEnabled = wm.javaClass.getDeclaredMethod("isWifiApEnabled")
                methodEnabled.isAccessible = true
                if (methodEnabled.invoke(wm) as? Boolean == true) return true
            }
        } catch (_: Throwable) {}
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val n = nif.name.lowercase(Locale.US)
                if (n.startsWith("ap") || n.startsWith("wlan1") || n.startsWith("swlan") || n.startsWith("softap")) {
                    val addrs = nif.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val a = addrs.nextElement()
                        if (a is Inet4Address && !a.isLoopbackAddress) return true
                    }
                }
            }
        } catch (_: Throwable) {}
        return false
    }

    private fun esBluetoothRealmenteEncendido(): Boolean {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return btManager?.adapter?.isEnabled == true
    }

    private fun iniciarTransmisionBle() {
        try {
            BleCoreService.isRunning = true
            val intent = Intent(this, BleCoreService::class.java)
            startService(intent)
            tvConsole.append("\n> ${ExosysMainI18n.tr(this, "con_ble_start")}")
        } catch (e: Exception) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(Intent(this, BleCoreService::class.java))
                }
            } catch (_: Throwable) {}
        }
    }

    private fun cortarTransmisionBle() {
        try {
            autoStartBleWhenBtTurnsOn = false
            BleCoreService.isRunning = false
            BleCoreService.setGnssInjectionEnabled(this, false)
            stopService(Intent(this, BleCoreService::class.java))
            tvConsole.append("\n> ${ExosysMainI18n.tr(this, "con_ble_stop")}")
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun activarBluetoothYArrancarTransmision() {
        autoStartBleWhenBtTurnsOn = true
        try {
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (adapter != null) {
                @Suppress("DEPRECATION")
                if (adapter.enable()) {
                    tvConsole.append("\n> ${ExosysMainI18n.tr(this, "con_bt_turning_on")}")
                    mainHandler.postDelayed({ if (esBluetoothRealmenteEncendido()) { autoStartBleWhenBtTurnsOn = false; iniciarTransmisionBle() } }, 600L)
                    return
                }
            }
        } catch (_: Throwable) {}
        try { btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) { abrirPantallaSeleccionBluetooth() }
    }

    private fun abrirPantallaSeleccionBluetooth() {
        try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) }) } catch (_: Exception) {}
    }

    private fun inyectarToqueFirme(service: AccessibilityService, x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        try {
            val path = Path().apply { moveTo(x, y); lineTo(x + 1.5f, y + 1.5f) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 85L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            service.dispatchGesture(gesture, null, null)
        } catch (_: Throwable) {}
    }

    private fun buscarNodoYDarClicFirme(service: AccessibilityService, textos: List<String>, fallbackXRatio: Float, fallbackYRatio: Float) {
        val dm = resources.displayMetrics
        try {
            val root = service.rootInActiveWindow
            if (root != null) {
                for (query in textos) {
                    val nodes = root.findAccessibilityNodeInfosByText(query)
                    if (!nodes.isNullOrEmpty()) {
                        for (node in nodes) {
                            var curr: AccessibilityNodeInfo? = node
                            while (curr != null) {
                                if (curr.isClickable) { if (curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return; break }
                                curr = curr.parent
                            }
                            val rect = Rect()
                            node.getBoundsInScreen(rect)
                            if (rect.width() > 0 && rect.height() > 0) {
                                inyectarToqueFirme(service, rect.exactCenterX(), rect.exactCenterY())
                                return
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        inyectarToqueFirme(service, dm.widthPixels * fallbackXRatio, dm.heightPixels * fallbackYRatio)
    }

    private fun abrirHotspotUniversalSinRebote() {
        val brand = "${Build.MANUFACTURER}${Build.BRAND}".lowercase(Locale.US)
        if (brand.contains("honor") || brand.contains("huawei")) {
            val touchService = ExosysTouchService.instance
            val initialHotspotState = esHotspotRealmenteEncendido()
            try { startActivity(Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }) } catch (_: Throwable) {}
            if (touchService != null) {
                thread(start = true, isDaemon = true) {
                    try {
                        Thread.sleep(550)
                        buscarNodoYDarClicFirme(touchService, listOf("Red móvil", "Mobile network"), 0.50f, 0.540f)
                        Thread.sleep(580)
                        buscarNodoYDarClicFirme(touchService, listOf("Zona Wi-Fi personal", "Personal hotspot"), 0.50f, 0.338f)
                        Thread.sleep(600)
                        if (esHotspotRealmenteEncendido() == initialHotspotState) {
                            val dm = resources.displayMetrics
                            inyectarToqueFirme(touchService, dm.widthPixels * 0.875f, dm.heightPixels * 0.158f)
                        }
                    } catch (_: Throwable) {}
                }
            } else { Toast.makeText(this, ExosysMainI18n.tr(this, "toast_hs_hint"), Toast.LENGTH_LONG).show() }
            return
        }

        val candidatos = ArrayList<Intent>()
        if (brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme")) {
            candidatos.add(Intent().apply { component = ComponentName("com.oplus.wirelesssettings", "com.oplus.wirelesssettings.wifiap.WifiApSettingsActivity") })
            candidatos.add(Intent().apply { component = ComponentName("com.android.settings", "com.oplus.settings.feature.sp.OplusWifiApSettingsActivity") })
        } else if (brand.contains("samsung")) {
            candidatos.add(Intent("android.settings.WIFI_AP_SETTINGS"))
        }
        candidatos.add(Intent("android.settings.TETHER_SETTINGS"))
        candidatos.add(Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_DEFAULT); component = ComponentName("com.android.settings", "com.android.settings.TetherSettings") })

        for (intent in candidatos) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
                return
            } catch (_: Throwable) {}
        }
    }

    private fun verificarAutoReanudacion(intent: Intent?) {
        if (intent?.getBooleanExtra("AUTO_RECAST", false) == true) {
            intent.removeExtra("AUTO_RECAST")
            solicitarCapturaPantalla()
        }
    }

    private fun evaluarPermisosYProceder() {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p.addAll(listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            p.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val faltan = p.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (faltan.isNotEmpty()) permissionLauncher.launch(faltan.toTypedArray())
    }

    private fun solicitarCapturaPantalla() {
        if (ExosysTouchService.instance == null) {
            tvConsole.append("\n> ${ExosysMainI18n.tr(this, "con_acc_req")}")
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            return
        }
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(logReceiver) } catch (_: Throwable) {}
        discoverySocket?.close()
    }
}