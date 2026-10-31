package com.example.exosys

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// =============================================================================
// 0. DICCIONARIO MULTILENGUAJE (ES, EN, ZH, JA, HI, FR, AR, RU)
//    Lee automáticamente el idioma seleccionado en el Menú Principal
// =============================================================================
object ExonedForgeI18n {
    private val LANG_INDEX = mapOf(
        "es" to 0, "en" to 1, "zh" to 2, "ja" to 3,
        "hi" to 4, "fr" to 5, "ar" to 6, "ru" to 7
    )

    private val DICT: Map<String, Array<String>> = mapOf(
        "idle" to arrayOf(
            "Inactivo", "Idle", "空闲", "待機中", "निष्क्रिय", "Inactif", "خامل", "Ожидание"
        ),
        "prep_matrix" to arrayOf(
            "Preparando matriz de sectores...",
            "Preparing sector matrix...",
            "正在准备扇区矩阵...",
            "セクターマトリックスを準備中...",
            "सेक्टर मैट्रिक्स तैयार हो रहा है...",
            "Préparation de la matrice...",
            "جارٍ تجهيز مصفوفة القطاعات...",
            "Подготовка матрицы секторов..."
        ),
        "check_prev" to arrayOf(
            "Verificando progreso previo en disco (.QOI.PART)...",
            "Checking previous progress on disk (.QOI.PART)...",
            "正在检查磁盘上的进度 (.QOI.PART)...",
            "ディスク上の進行状況を確認中 (.QOI.PART)...",
            "डिस्क पर पिछली प्रगति की जाँच हो रही है...",
            "Vérification de la progression sur le disque...",
            "جارٍ التحقق من التقدم السابق...",
            "Проверка сохранённого прогресса (.QOI.PART)..."
        ),
        "compiling_hwy" to arrayOf(
            "Compilando grafo vial offline (.HWY)...",
            "Compiling offline road graph (.HWY)...",
            "正在编译离线道路网 (.HWY)...",
            "オフライン道路グラフをコンパイル中 (.HWY)...",
            "ऑफ़लाइन सड़क ग्राफ़ संकलित हो रहा है (.HWY)...",
            "Compilation du graphe routier hors-ligne (.HWY)...",
            "جارٍ تجميع شبكة الطرق (.HWY)...",
            "Компиляция графа дорог (.HWY)..."
        ),
        "routes_done" to arrayOf(
            "[RUTAS .HWY COMPLETADAS]",
            "[.HWY ROUTES COMPLETED]",
            "[.HWY 路线已完成]",
            "[.HWY ルート完了]",
            "[.HWY मार्ग पूर्ण]",
            "[ROUTES .HWY TERMINÉES]",
            "[اكتملت مسارات .HWY]",
            "[МАРШРУТЫ .HWY ГОТОВЫ]"
        ),
        "map_done" to arrayOf(
            "Mapa completado.",
            "Map completed.",
            "地图已完成。",
            "マップ完了。",
            "नक्शा पूरा हुआ।",
            "Carte terminée.",
            "اكتملت الخريطة.",
            "Карта готова."
        ),
        "auto_paused_net" to arrayOf(
            "[AUTO-PAUSADO] Esperando conexión a Internet...",
            "[AUTO-PAUSED] Waiting for Internet connection...",
            "[自动暂停] 正在等待网络连接...",
            "[自動一時停止] インターネット接続を待機中...",
            "[स्वतः रुका] इंटरनेट कनेक्शन की प्रतीक्षा है...",
            "[PAUSE AUTO] En attente d'Internet...",
            "[توقف تلقائي] في انتظار الاتصال بالإنترنت...",
            "[АВТОПАУЗА] Ожидание подключения к Интернету..."
        ),
        "net_restored" to arrayOf(
            "Red restablecida — Reanudando forja (.QOI)...",
            "Network restored — Resuming forge (.QOI)...",
            "网络已恢复 — 继续锻造 (.QOI)...",
            "通信回復 — ダウンロード再開 (.QOI)...",
            "नेटवर्क बहाल — निर्माण फिर से शुरू (.QOI)...",
            "Réseau rétabli — Reprise (.QOI)...",
            "عادت الشبكة — استئناف التحميل (.QOI)...",
            "Сеть восстановлена — Продолжение (.QOI)..."
        ),
        "paused_saved" to arrayOf(
            "Forja pausada y guardada en disco (.QOI.PART)",
            "Forge paused and saved on disk (.QOI.PART)",
            "已暂停并保存在磁盘 (.QOI.PART)",
            "一時停止・ディスク保存済み (.QOI.PART)",
            "रोका गया और डिस्क पर सहेजा गया (.QOI.PART)",
            "En pause et sauvegardé sur disque (.QOI.PART)",
            "تم الإيقاف المؤقت والحفظ (.QOI.PART)",
            "Пауза, прогресс сохранён (.QOI.PART)"
        ),
        "completed_100" to arrayOf(
            "[COMPLETADO AL 100%]",
            "[100% COMPLETED]",
            "[100% 已完成]",
            "[100% 完了]",
            "[100% पूर्ण]",
            "[TERMINÉ À 100%]",
            "[اكتمل 100%]",
            "[ЗАВЕРШЕНО НА 100%]"
        ),
        "forge_title_qoi" to arrayOf(
            "CENTRO DE FORJA Y AUDITORÍA (.QOI)",
            "FORGE & AUDIT CENTER (.QOI)",
            "地图制作与审核中心 (.QOI)",
            "マップ作成・監査センター (.QOI)",
            "निर्माण और परीक्षण केंद्र (.QOI)",
            "CENTRE DE FORGE ET D'AUDIT (.QOI)",
            "مركز الإنشاء والمراجعة (.QOI)",
            "ЦЕНТР СБОРКИ И ПРОВЕРКИ (.QOI)"
        ),
        "forge_title_hwy" to arrayOf(
            "CENTRO DE FORJA: SOLO RUTAS (.HWY)",
            "FORGE CENTER: ROUTES ONLY (.HWY)",
            "制作中心：仅路线 (.HWY)",
            "作成センター：ルートのみ (.HWY)",
            "निर्माण केंद्र: केवल मार्ग (.HWY)",
            "CENTRE DE FORGE : ROUTES SEULES (.HWY)",
            "مركز الإنشاء: مسارات فقط (.HWY)",
            "ЦЕНТР СБОРКИ: ТОЛЬКО МАРШРУТЫ (.HWY)"
        ),
        "fire_hdr" to arrayOf(
            "1. AUDITORÍA PRE-DESCARGA (LOOPBACK .QOI RETINA 2X)",
            "1. PRE-DOWNLOAD AUDIT (RETINA 2X .QOI LOOPBACK)",
            "1. 下载前预览测试 (RETINA 2X .QOI)",
            "1. 事前プレビュー監査 (RETINA 2X .QOI)",
            "1. डाउनलोड से पहले जाँच (RETINA 2X .QOI)",
            "1. AUDIT PRÉ-TÉLÉCHARGEMENT (RETINA 2X .QOI)",
            "1. معاينة ما قبل التحميل (RETINA 2X .QOI)",
            "1. ПРЕДПРОСМОТР (RETINA 2X .QOI)"
        ),
        "fire_desc" to arrayOf(
            "Descarga 4 sectores reales de tu selección, aplica el Shader en 512x512, comprime a .QOI y los descomprime en pantalla.",
            "Downloads 4 real sectors from your selection, applies the 512x512 Shader, compresses to .QOI and decodes them on screen.",
            "从所选区域下载 4 个真实扇区，应用着色器并压缩为 .QOI 进行预览。",
            "選択範囲から4つの実セクターを取得し、シェーダー適用・QOI圧縮後に画面へ展開します。",
            "आपके चयन से 4 वास्तविक सेक्टर डाउनलोड करके शेडर और .QOI की जाँच करता है।",
            "Télécharge 4 secteurs réels, applique le Shader, compresse en .QOI et affiche le résultat.",
            "يقوم بتحميل 4 قطاعات حقيقية وتطبيق المرشح وضغطها إلى .QOI للمعاينة.",
            "Скачивает 4 реальных сектора, применяет шейдер, сжимает в .QOI и декодирует на экран."
        ),
        "btn_fire_run" to arrayOf(
            "PROBAR 4 SECTORES REALES AHORA",
            "TEST 4 REAL SECTORS NOW",
            "立即测试 4 个真实扇区",
            "4つの実セクターを今すぐテスト",
            "अभी 4 सेक्टर परखें",
            "TESTER 4 SECTEURS MAINTENANT",
            "اختبر 4 قطاعات الآن",
            "ПРОТЕСТИРОВАТЬ 4 СЕКТОРА"
        ),
        "btn_fire_running" to arrayOf(
            "PROBANDO DESCARGA + SHADER + QOI...",
            "TESTING DOWNLOAD + SHADER + QOI...",
            "正在测试下载 + 着色器 + QOI...",
            "ダウンロード + シェーダー + QOI テスト中...",
            "डाउनलोड + शेडर + QOI जाँच जारी...",
            "TEST EN COURS...",
            "جارٍ اختبار التحميل والمرشح...",
            "ТЕСТИРОВАНИЕ СКАЧИВАНИЯ + ШЕЙДЕРА..."
        ),
        "btn_fire_retry" to arrayOf(
            "VOLVER A PROBAR 4 SECTORES",
            "TEST 4 SECTORS AGAIN",
            "重新测试 4 个扇区",
            "もう一度4セクターをテスト",
            "फिर से 4 सेक्टर परखें",
            "RETESTER 4 SECTEURS",
            "إعادة اختبار 4 قطاعات",
            "ПОВТОРИТЬ ТЕСТ 4 СЕКТОРОВ"
        ),
        "pc_sec_title" to arrayOf(
            "2. ENVIAR A COMPUTADORA (RECOMENDADO / MULTI-ESTADO)",
            "2. SEND TO COMPUTER (RECOMMENDED / MULTI-STATE)",
            "2. 发送到电脑（推荐 / 多州模式）",
            "2. パソコンへ送信（推奨 / 複数州対応）",
            "2. कंप्यूटर पर भेजें (सुझाया गया / बहु-राज्य)",
            "2. ENVOYER AU PC (RECOMMANDÉ / MULTI-ÉTATS)",
            "2. إرسال إلى الكمبيوتر (موصى به)",
            "2. ОТПРАВИТЬ НА ПК (РЕКОМЕНДУЕТСЯ)"
        ),
        "pc_sec_sub" to arrayOf(
            "Transmite tu selección a la PC por Wi-Fi, Hotspot o Cable USB para compilar y fusionar directo en la MicroSD.",
            "Transmit your selection to the PC via Wi-Fi, Hotspot or USB Cable to compile and merge directly onto the MicroSD.",
            "通过 Wi-Fi、热点或 USB 线将选区发送到电脑，直接合并到存储卡。",
            "Wi-Fi・テザリング・USB経由でPCへ送信し、MicroSDへ直接統合します。",
            "Wi-Fi, हॉटस्पॉट या USB केबल द्वारा सीधे PC पर भेजें और MicroSD में सेव करें।",
            "Transmettez votre sélection au PC via Wi-Fi, Hotspot ou USB pour fusionner sur la MicroSD.",
            "أرسل التحديد إلى الكمبيوتر عبر الواي فاي أو كابل USB للدمج في بطاقة الذاكرة.",
            "Передайте выбор на ПК по Wi-Fi, точке доступа или USB для записи на MicroSD."
        ),
        "btn_send_pc_live" to arrayOf(
            "ENVIAR A PC EN VIVO (WI-FI / HOTSPOT / ANCLAJE USB)",
            "SEND TO PC LIVE (WI-FI / HOTSPOT / USB TETHER)",
            "实时发送到电脑 (Wi-Fi / 热点 / USB 共享)",
            "PCへリアルタイム送信 (Wi-Fi / USBテザリング)",
            "PC पर लाइव भेजें (Wi-Fi / हॉटस्पॉट / USB)",
            "ENVOYER AU PC EN DIRECT (WI-FI / USB)",
            "إرسال مباشر للكمبيوتر (واي فاي / USB)",
            "ОТПРАВИТЬ НА ПК (WI-FI / ХОТСПОТ / USB)"
        ),
        "btn_save_json_usb" to arrayOf(
            "GUARDAR .JSON PARA CABLE USB (TRANSFERIR ARCHIVOS)",
            "SAVE .JSON FOR USB CABLE (FILE TRANSFER)",
            "保存 .JSON 文件（通过 USB 传文件）",
            "USBファイル転送用に .JSON を保存",
            "USB केबल के लिए .JSON फ़ाइल सेव करें",
            "ENREGISTRER .JSON POUR CÂBLE USB",
            "حفظ ملف .JSON لكابل USB",
            "СОХРАНИТЬ .JSON ДЛЯ USB-КАБЕЛЯ"
        ),
        "btn_guide_pc" to arrayOf(
            "¿CÓMO ENLAZAR TELÉFONO Y PC PASO A PASO?",
            "HOW TO LINK PHONE & PC STEP BY STEP?",
            "如何一步步连接手机与电脑？",
            "スマホとPCの接続手順を見る",
            "फ़ोन और PC को कदम-दर-कदम कैसे जोड़ें?",
            "COMMENT RELIER TÉLÉPHONE ET PC ÉTAPE PAR ÉTAPE ?",
            "كيفية ربط الهاتف والكمبيوتر خطوة بخطوة؟",
            "КАК СВЯЗАТЬ ТЕЛЕФОН И ПК ШАГ ЗА ШАГОМ?"
        ),
        "phone_sec_title" to arrayOf(
            "3. DESCARGAR EN ESTE TELÉFONO (SEGUNDO PLANO)",
            "3. DOWNLOAD ON THIS PHONE (BACKGROUND)",
            "3. 在本手机后台下载",
            "3. このスマホで直接ダウンロード（バックグラウンド）",
            "3. इसी फ़ोन में डाउनलोड करें (बैकग्राउंड)",
            "3. TÉLÉCHARGER SUR CE TÉLÉPHONE (ARRIÈRE-PLAN)",
            "3. التحميل على هذا الهاتف (في الخلفية)",
            "3. СКАЧАТЬ НА ЭТОТ ТЕЛЕФОН (В ФОНЕ)"
        ),
        "btn_open_vault" to arrayOf(
            "ABRIR BÓVEDA DE MAPAS (LIBERAR ESPACIO / VER PC)",
            "OPEN MAP VAULT (FREE SPACE / VIEW PC)",
            "打开地图库（释放空间 / 查看电脑）",
            "マップ保管庫を開く（容量解放 / PC確認）",
            "मैप वॉल्ट खोलें (जगह खाली करें / PC देखें)",
            "OUVRIR LA VOÛTE DES CARTES (ESPACE / PC)",
            "فتح خزنة الخرائط (توفير مساحة / عرض الكمبيوتر)",
            "ОТКРЫТЬ ХРАНИЛИЩЕ КАРТ (ПАМЯТЬ / ПК)"
        ),
        "btn_back_map" to arrayOf(
            "VOLVER AL MAPA",
            "BACK TO MAP",
            "返回地图",
            "マップに戻る",
            "नक्शे पर वापस जाएँ",
            "RETOUR À LA CARTE",
            "العودة إلى الخريطة",
            "ВЕРНУТЬСЯ К КАРТЕ"
        ),
        "vault_title" to arrayOf(
            "BÓVEDA DE MAPAS (TELÉFONO ⇄ PC)",
            "MAP VAULT (PHONE ⇄ PC)",
            "地图保险库 (手机 ⇄ 电脑)",
            "マップ保管庫 (スマホ ⇄ PC)",
            "मैप वॉल्ट (फ़ोन ⇄ PC)",
            "VOÛTE DES CARTES (TÉLÉPHONE ⇄ PC)",
            "خزنة الخرائط (الهاتف ⇄ الكمبيوتر)",
            "ХРАНИЛИЩЕ КАРТ (ТЕЛЕФОН ⇄ ПК)"
        ),
        "btn_close_vault" to arrayOf(
            "CERRAR BÓVEDA",
            "CLOSE VAULT",
            "关闭地图库",
            "保管庫を閉じる",
            "वॉल्ट बंद करें",
            "FERMER LA VOÛTE",
            "إغلاق الخزنة",
            "ЗАКРЫТЬ ХРАНИЛИЩЕ"
        ),
        "btn_pause" to arrayOf(
            "PAUSAR", "PAUSE", "暂停", "一時停止", "रोकें", "PAUSE", "إيقاف مؤقت", "ПАУЗА"
        ),
        "btn_resume" to arrayOf(
            "REANUDAR", "RESUME", "继续", "再開", "आगे बढ़ाएँ", "REPRENDRE", "متابعة", "ПРОДОЛЖИТЬ"
        ),
        "btn_save_exit" to arrayOf(
            "GUARDAR Y SALIR", "SAVE & EXIT", "保存并退出", "保存して終了", "सेव करें और निकलें", "SAUVEGARDER & QUITTER", "حفظ وخروج", "СОХРАНИТЬ И ВЫЙТИ"
        ),
        "btn_minimize_bg" to arrayOf(
            "MINIMIZAR (SEGUIR DESCARGANDO EN 2.º PLANO)",
            "MINIMIZE (KEEP DOWNLOADING IN BACKGROUND)",
            "最小化（继续在后台下载）",
            "最小化（バックグラウンドで継続）",
            "छोटा करें (बैकग्राउंड में डाउनलोड जारी रखें)",
            "RÉDUIRE (CONTINUER EN ARRIÈRE-PLAN)",
            "تصغير (استمرار التحميل في الخلفية)",
            "СВЕРНУТЬ (ПРОДОЛЖИТЬ В ФОНЕ)"
        ),
        "btn_prune_z15" to arrayOf(
            "BORRAR SOLO Z15-Z20",
            "DELETE Z15-Z20 ONLY",
            "仅删除 Z15-Z20",
            "Z15-Z20のみ削除",
            "केवल Z15-Z20 हटाएँ",
            "SUPPRIMER Z15-Z20 SEULEMENT",
            "حذف Z15-Z20 فقط",
            "УДАЛИТЬ ТОЛЬКО Z15-Z20"
        ),
        "btn_del_all" to arrayOf(
            "BORRAR MAPA COMPLETO",
            "DELETE ENTIRE MAP",
            "删除完整地图",
            "マップ全体を削除",
            "पूरा नक्शा हटाएँ",
            "SUPPRIMER TOUTE LA CARTE",
            "حذف الخريطة بالكامل",
            "УДАЛИТЬ ВСЮ КАРТУ"
        )
    )

    fun getLang(context: Context): String {
        val spMain = context.getSharedPreferences("exosys_prefs", Context.MODE_PRIVATE)
        val spLink = context.getSharedPreferences("exosys_pc_link_prefs", Context.MODE_PRIVATE)
        val code = spMain.getString("app_lang", null)
            ?: spMain.getString("lang", null)
            ?: spLink.getString("app_lang", null)
            ?: Locale.getDefault().language
        return if (LANG_INDEX.containsKey(code)) code!! else "es"
    }

    fun setLang(context: Context, langCode: String) {
        val clean = if (LANG_INDEX.containsKey(langCode)) langCode else "es"
        context.getSharedPreferences("exosys_prefs", Context.MODE_PRIVATE)
            .edit().putString("app_lang", clean).apply()
        context.getSharedPreferences("exosys_pc_link_prefs", Context.MODE_PRIVATE)
            .edit().putString("app_lang", clean).apply()
    }

    fun tr(context: Context, key: String): String {
        val idx = LANG_INDEX[getLang(context)] ?: 0
        val arr = DICT[key] ?: return key
        return arr.getOrElse(idx) { arr[0] }
    }

    fun styleDisplayName(styleIdx: Int): String = when (styleIdx) {
        0 -> "V1: Dark Standard"
        1 -> "V2: Arasaka Grid"
        2 -> "V3: Kiroshi Optics"
        3 -> "Satelital Híbrido"
        5 -> "V4: Pip-Boy 3000 CRT"
        else -> "Clásico Día"
    }
}

// =============================================================================
// 1. MODELO DE RECETA DE FORJA (.EXORECIPE) CON SOPORTE MULTI-ESTADO Y PIP-BOY
// =============================================================================
data class ExonedForgeRecipe(
    val jobId: String,
    val jobName: String,
    val createdAt: Long,
    val providerIdx: Int,
    val styleIdx: Int,
    val cartoApiKey: String,
    val cartoPremium: Boolean,
    val selectedZooms: List<Int>,
    val includeRoutes: Boolean,
    val routesOnly: Boolean = false,
    val routeProfileIdx: Int,
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
    val hasCustomPolygon: Boolean,
    val selectionRings: List<List<GeoPoint>>,
    val isolateUrbanHighZoom: Boolean,
    val urbanZ16Cells: Set<Long>,
    val estimatedTiles: Long,
    val estimatedWeightMb: Double
) {
    fun toJsonString(): String {
        val root = JSONObject()
        root.put("version", 4)
        root.put("job_id", jobId)
        root.put("job_name", jobName)
        root.put("created_at", createdAt)
        root.put("provider_idx", providerIdx)
        root.put("style_idx", styleIdx)
        root.put("carto_api_key", cartoApiKey)
        root.put("carto_premium", cartoPremium)
        root.put("include_routes", includeRoutes)
        root.put("routes_only", routesOnly)
        root.put("route_profile_idx", routeProfileIdx)
        root.put("north", north)
        root.put("south", south)
        root.put("east", east)
        root.put("west", west)
        root.put("has_custom_polygon", hasCustomPolygon)
        root.put("isolate_urban_high_zoom", isolateUrbanHighZoom)
        root.put("estimated_tiles", estimatedTiles)
        root.put("estimated_weight_mb", estimatedWeightMb)

        val zoomsArr = JSONArray()
        selectedZooms.sorted().forEach { zoomsArr.put(it) }
        root.put("selected_zooms", zoomsArr)

        val ringsArr = JSONArray()
        for (ring in selectionRings) {
            val rArr = JSONArray()
            for (pt in ring) {
                val p = JSONArray()
                p.put((pt.lat * 100000.0).toInt() / 100000.0)
                p.put((pt.lon * 100000.0).toInt() / 100000.0)
                rArr.put(p)
            }
            ringsArr.put(rArr)
        }
        root.put("selection_rings", ringsArr)

        val cellsArr = JSONArray()
        for (code in urbanZ16Cells) {
            val pair = JSONArray()
            pair.put(UrbanScannerEngine.unpackX(code))
            pair.put(UrbanScannerEngine.unpackY(code))
            cellsArr.put(pair)
        }
        root.put("urban_z16_cells", cellsArr)
        return root.toString()
    }
}

data class VaultMapEntry(
    val jobId: String,
    val jobName: String,
    val locationSource: String, // "PHONE", "PC_DISK", "PC_SD"
    val styleName: String,
    val zoomsSummary: String,
    val completedTiles: Long,
    val totalTiles: Long,
    val sizeMb: Double,
    val status: String, // "COMPLETADO", "PAUSADO", "FORJANDO"
    val hasHighResZooms: Boolean,
    val folderPath: String
)

data class DecodedTileSample(
    val z: Int,
    val x: Int,
    val y: Int,
    val sourceTag: String, // "GOOGLE", "CARTO" o "ESRI"
    val qoiSizeKb: Double,
    val bitmap: Bitmap,
    val timestamp: Long
)

data class HttpTileResponse(
    val httpCode: Int,
    val bytes: ByteArray?,
    val networkError: Boolean
)

// =============================================================================
// 2. CODEC BINARIO CUSTOM QOI-565 (SINCRONIZADO AL BYTE CON CARTOENGINE.CPP)
// =============================================================================
object QoiBinaryCodec {

    fun encodeToQoi565(src: Bitmap): ByteArray {
        val scaled = if (src.width != 256 || src.height != 256) {
            Bitmap.createScaledBitmap(src, 256, 256, true)
        } else {
            src
        }

        val totalPixels = 256 * 256
        val pixels = IntArray(totalPixels)
        scaled.getPixels(pixels, 0, 256, 0, 0, 256, 256)
        if (scaled !== src) scaled.recycle()

        val out = ByteArray(totalPixels * 3 + 16)
        var p = 0
        val cache = IntArray(64)
        var prevPx = 0
        var run = 0

        for (i in 0 until totalPixels) {
            val argb = pixels[i]
            val r5 = (argb ushr 19) and 0x1F
            val g6 = (argb ushr 10) and 0x3F
            val b5 = (argb ushr 3) and 0x1F
            val px = (r5 shl 11) or (g6 shl 5) or b5

            if (px == prevPx) {
                run++
                if (run == 64) {
                    out[p++] = 0x3F.toByte()
                    run = 0
                }
            } else {
                if (run > 0) {
                    out[p++] = (run - 1).toByte()
                    run = 0
                }
                val idx = (px xor (px ushr 3)) and 63
                if (cache[idx] == px) {
                    out[p++] = (0x40 or idx).toByte()
                } else {
                    out[p++] = 0x80.toByte()
                    out[p++] = (px and 0xFF).toByte()
                    out[p++] = ((px ushr 8) and 0xFF).toByte()
                    cache[idx] = px
                }
                prevPx = px
            }
        }

        if (run > 0) {
            out[p++] = (run - 1).toByte()
        }

        return out.copyOf(p)
    }

    fun decodeQoiToBitmap(qoiData: ByteArray): Bitmap? {
        if (qoiData.isEmpty()) return null
        val w = 256
        val h = 256
        val totalPixels = w * h
        val pixels = IntArray(totalPixels)
        val cache = IntArray(64)

        var inIdx = 0
        var outIdx = 0
        var prevPx = 0
        val compLen = qoiData.size

        fun rgb565ToArgb(px: Int): Int {
            val r8 = ((px ushr 11) and 0x1F) shl 3
            val g8 = ((px ushr 5) and 0x3F) shl 2
            val b8 = (px and 0x1F) shl 3
            return (0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
        }

        while (inIdx < compLen && outIdx < totalPixels) {
            val op = qoiData[inIdx++].toInt() and 0xFF
            val tag = op and 0xC0

            if (tag == 0x00) {
                val run = (op and 0x3F) + 1
                val argb = rgb565ToArgb(prevPx)
                for (k in 0 until run) {
                    if (outIdx < totalPixels) {
                        pixels[outIdx++] = argb
                    }
                }
            } else if (tag == 0x40) {
                val idx = op and 0x3F
                prevPx = cache[idx]
                pixels[outIdx++] = rgb565ToArgb(prevPx)
            } else if (op == 0x80) {
                if (inIdx + 1 >= compLen) break
                val lo = qoiData[inIdx++].toInt() and 0xFF
                val hi = qoiData[inIdx++].toInt() and 0xFF
                val px = lo or (hi shl 8)
                cache[(px xor (px ushr 3)) and 63] = px
                prevPx = px
                pixels[outIdx++] = rgb565ToArgb(px)
            }
        }

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }
}

// =============================================================================
// 3. AUDITORÍA DE MOSAICOS (SOPORTA RETINA 512x512 PARA SUPERSAMPLING LIMPIO)
// =============================================================================
object TileIntegrityAuditor {

    fun decodeAndAuditRawTile(rawBytes: ByteArray?, allowHiRes512: Boolean = false): Bitmap? {
        if (rawBytes == null || rawBytes.size < 85) return null

        val isPng = (rawBytes[0] == 0x89.toByte() && rawBytes[1] == 0x50.toByte() &&
                rawBytes[2] == 0x4E.toByte() && rawBytes[3] == 0x47.toByte())
        val isJpg = (rawBytes[0] == 0xFF.toByte() && rawBytes[1] == 0xD8.toByte() &&
                rawBytes[2] == 0xFF.toByte())

        if (!isPng && !isJpg) return null

        val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
        if (bmp.width < 128 || bmp.height < 128) {
            bmp.recycle()
            return null
        }

        if (allowHiRes512 && bmp.width == 512 && bmp.height == 512) {
            return bmp
        }

        if (bmp.width != 256 || bmp.height != 256) {
            val scaled = Bitmap.createScaledBitmap(bmp, 256, 256, true)
            if (scaled != bmp) bmp.recycle()
            return scaled
        }
        return bmp
    }

    fun esMosaicoWatermarkCarto(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w < 64 || h < 64) return true

        val p1 = bmp.getPixel(4, 4)
        val p2 = bmp.getPixel(w - 5, 4)
        val p3 = bmp.getPixel(4, h - 5)
        val p4 = bmp.getPixel(w - 5, h - 5)
        val r1 = (p1 shr 16) and 0xFF
        val g1 = (p1 shr 8) and 0xFF
        val b1 = p1 and 0xFF

        if (p1 == p2 && p1 == p3 && p1 == p4 && r1 == g1 && g1 == b1 && r1 in 28..64) {
            var brightTextHits = 0
            for (d in 50 until min(205, w - 10) step 2) {
                val pxDiag = bmp.getPixel(d, d)
                val pxVert = bmp.getPixel(w / 2, d)
                if ((pxDiag and 0xFF) > r1 + 35 || (pxVert and 0xFF) > r1 + 35) {
                    brightTextHits++
                }
            }
            if (brightTextHits >= 3) return true
        }
        return false
    }
}

// =============================================================================
// 4. ESCRITOR ATÓMICO DE CLÚSTERES 32x32 COLUMN-MAJOR ((x % 32) * 32 + (y % 32))
// =============================================================================
object ClusterQoiStorage {

    private const val HEADER_SIZE_BYTES = 8192

    private fun clusterLock(path: String): Any = path.intern()

    fun isSectorAlreadyWritten(jobDir: File, z: Int, x: Int, y: Int): Boolean {
        val cx = x / 32
        val cy = y / 32
        val zDir = File(jobDir, "Z$z")
        val finalFile = File(zDir, "C_${cx}_${cy}.QOI")
        val partFile = File(zDir, "C_${cx}_${cy}.QOI.PART")

        val target = when {
            finalFile.exists() && finalFile.length() > HEADER_SIZE_BYTES -> finalFile
            partFile.exists() && partFile.length() > HEADER_SIZE_BYTES -> partFile
            else -> return false
        }

        val localIdx = (x % 32) * 32 + (y % 32)
        val headerOffset = (localIdx * 8).toLong()

        synchronized(clusterLock(target.absolutePath)) {
            return try {
                RandomAccessFile(target, "r").use { raf ->
                    if (raf.length() < headerOffset + 8) return false
                    raf.seek(headerOffset)
                    val buf = ByteArray(8)
                    raf.readFully(buf)
                    val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                    val offset = bb.int
                    val length = bb.int
                    offset >= HEADER_SIZE_BYTES && length > 20 && (offset.toLong() + length.toLong()) <= raf.length()
                }
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun writeSectorToCluster(jobDir: File, z: Int, x: Int, y: Int, qoiBytes: ByteArray): Boolean {
        val cx = x / 32
        val cy = y / 32
        val zDir = File(jobDir, "Z$z")
        if (!zDir.exists()) zDir.mkdirs()

        val finalFile = File(zDir, "C_${cx}_${cy}.QOI")
        val partFile = File(zDir, "C_${cx}_${cy}.QOI.PART")
        val activeFile = if (finalFile.exists()) finalFile else partFile

        val localIdx = (x % 32) * 32 + (y % 32)
        val headerPos = (localIdx * 8).toLong()

        synchronized(clusterLock(activeFile.absolutePath)) {
            return try {
                RandomAccessFile(activeFile, "rw").use { raf ->
                    if (raf.length() < HEADER_SIZE_BYTES) {
                        raf.seek(0)
                        raf.write(ByteArray(HEADER_SIZE_BYTES))
                    }
                    val writeOffset = raf.length().toInt()
                    raf.seek(writeOffset.toLong())
                    raf.write(qoiBytes)

                    val pointerBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    pointerBuf.putInt(writeOffset)
                    pointerBuf.putInt(qoiBytes.size)

                    raf.seek(headerPos)
                    raf.write(pointerBuf.array())
                }
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun finalizeAllPartClusters(jobDir: File) {
        val subDirs = jobDir.listFiles() ?: return
        for (dir in subDirs) {
            if (dir.isDirectory && dir.name.startsWith("Z")) {
                val files = dir.listFiles() ?: continue
                for (f in files) {
                    if (f.name.endsWith(".QOI.PART")) {
                        val finalName = f.name.removeSuffix(".PART")
                        val dest = File(dir, finalName)
                        if (dest.exists()) dest.delete()
                        f.renameTo(dest)
                    }
                }
            }
        }
    }
}

// =============================================================================
// 5. MOTOR DE FORJA EN SEGUNDO PLANO (RESILIENCIA + PIP-BOY + JERARQUÍA PADRE-HIJA)
// =============================================================================
object ExonedForgeEngine {

    enum class ForgeState {
        IDLE,
        RUNNING,
        PAUSED_USER,
        PAUSED_WAITING_NETWORK,
        PAUSED_ENTROPY_ALERT,
        COMPLETED,
        ERROR
    }

    @Volatile var currentState: ForgeState = ForgeState.IDLE
    @Volatile var activeRecipe: ExonedForgeRecipe? = null
    @Volatile var statusHeadline: String = "Inactivo"
    @Volatile var detailMessage: String = ""

    val completedSectors = AtomicLong(0L)
    val totalSectors = AtomicLong(0L)
    val googleOkCount = AtomicLong(0L)
    val cartoRescueCount = AtomicLong(0L)
    val networkPauseCount = AtomicInteger(0)
    val writtenBytesDisk = AtomicLong(0L)

    private val currentGoogleMirror = AtomicInteger(0)
    private val consecutiveGoogleBanErrors = AtomicInteger(0)
    @Volatile private var googleCircuitBreakerUntilMs = 0L

    val liveRecentSamples = ConcurrentLinkedQueue<DecodedTileSample>()
    val witnessStripSamples = ConcurrentLinkedQueue<DecodedTileSample>()

    private val stopRequested = AtomicBoolean(false)
    private var networkCallbackRegistered = false
    @Volatile private var isDeviceNetworkValidated = true
    private var wakeLock: PowerManager.WakeLock? = null

    fun createRecipe(
        context: Context,
        jobName: String,
        providerIdx: Int,
        styleIdx: Int,
        selectedZooms: Set<Int>,
        includeRoutes: Boolean,
        routeProfileIdx: Int,
        north: Double,
        south: Double,
        east: Double,
        west: Double,
        hasCustomPolygon: Boolean,
        selectionRings: List<List<GeoPoint>>?,
        isolateUrbanHighZoom: Boolean,
        urbanZ16Cells: Set<Long>,
        telemetry: MatrixTelemetry,
        routesOnly: Boolean = false
    ): ExonedForgeRecipe {
        val cleanName = jobName.trim().ifBlank { "MAPA_EXOSYS" }
            .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            .uppercase(Locale.US)
        val stamp = SimpleDateFormat("MMdd_HHmm", Locale.US).format(Date())

        // El ID del trabajo ahora aísla las carpetas basándose en el estilo visual
        val jobId = "${cleanName}_ESTILO${styleIdx}_$stamp"

        val isRoutesOnlyEffective = routesOnly || (selectedZooms.isEmpty() && includeRoutes)

        return ExonedForgeRecipe(
            jobId = jobId,
            jobName = cleanName,
            createdAt = System.currentTimeMillis(),
            providerIdx = providerIdx,
            styleIdx = styleIdx,
            cartoApiKey = CartoApiManager.getApiKey(context),
            cartoPremium = CartoApiManager.isPremiumAccount(context),
            selectedZooms = if (isRoutesOnlyEffective) emptyList() else selectedZooms.sorted(),
            includeRoutes = if (isRoutesOnlyEffective) true else includeRoutes,
            routesOnly = isRoutesOnlyEffective,
            routeProfileIdx = routeProfileIdx,
            north = north,
            south = south,
            east = east,
            west = west,
            hasCustomPolygon = hasCustomPolygon,
            selectionRings = selectionRings ?: emptyList(),
            isolateUrbanHighZoom = isolateUrbanHighZoom,
            urbanZ16Cells = HashSet(urbanZ16Cells),
            estimatedTiles = if (isRoutesOnlyEffective) 0L else telemetry.totalTiles,
            estimatedWeightMb = telemetry.totalWeightMb
        )
    }

    fun runFourTileFireTest(
        context: Context,
        recipe: ExonedForgeRecipe,
        onResult: (Result<List<DecodedTileSample>>) -> Unit
    ) {
        thread(start = true, isDaemon = true) {
            try {
                googleCircuitBreakerUntilMs = 0L
                consecutiveGoogleBanErrors.set(0)

                var cLat = (recipe.north + recipe.south) * 0.5
                var cLon = (recipe.east + recipe.west) * 0.5

                if (recipe.urbanZ16Cells.isNotEmpty()) {
                    val sampleCell = recipe.urbanZ16Cells.elementAt(recipe.urbanZ16Cells.size / 2)
                    val cx = UrbanScannerEngine.unpackX(sampleCell)
                    val cy = UrbanScannerEngine.unpackY(sampleCell)
                    cLat = UrbanScannerEngine.tileYToLat(cy + 0.5, UrbanScannerEngine.Z_MASK)
                    cLon = UrbanScannerEngine.tileXToLon(cx + 0.5, UrbanScannerEngine.Z_MASK)
                }

                val maxZ = recipe.selectedZooms.maxOrNull() ?: 16
                val midZ = recipe.selectedZooms.minOrNull()?.let { (it + maxZ) / 2 } ?: 12

                val (cxMax, cyMax) = TileMatrixCalculator.deg2num(cLat, cLon, maxZ)
                val (cxMid, cyMid) = TileMatrixCalculator.deg2num(cLat, cLon, midZ)

                val coords = listOf(
                    Triple(midZ, cxMid, cyMid),
                    Triple(maxZ, cxMax, cyMax),
                    Triple(maxZ, cxMax + 1, cyMax),
                    Triple(maxZ, cxMax, cyMax + 1)
                )

                val samples = ArrayList<DecodedTileSample>(4)
                for ((z, x, y) in coords) {
                    val fetched = fetchAndProcessSingleTile(context, recipe, z, x, y)
                        ?: return@thread onResult(
                            Result.failure(Exception("No se pudo obtener el sector de prueba Z$z/$x/$y. Verifica tu conexión."))
                        )
                    samples.add(fetched.first)
                }
                onResult(Result.success(samples))
            } catch (t: Throwable) {
                onResult(Result.failure(Exception(t.localizedMessage ?: "Error en Prueba de Fuego")))
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    fun startOrResumePhoneForge(context: Context, recipe: ExonedForgeRecipe) {
        if (currentState == ForgeState.RUNNING) return

        val appCtx = context.applicationContext
        registerNetworkMonitor(appCtx)

        try {
            val pm = appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Exosys::ForgeWakeLock")
            }
            if (wakeLock?.isHeld == false) wakeLock?.acquire()
        } catch (_: Throwable) {}

        activeRecipe = recipe
        stopRequested.set(false)
        googleCircuitBreakerUntilMs = 0L
        consecutiveGoogleBanErrors.set(0)
        currentState = ForgeState.RUNNING
        statusHeadline = ExonedForgeI18n.tr(appCtx, "prep_matrix")
        detailMessage = ExonedForgeI18n.tr(appCtx, "check_prev")

        val jobDir = ExonedMapVault.getJobFolder(appCtx, recipe.jobId)
        if (!jobDir.exists()) jobDir.mkdirs()
        ExonedMapVault.saveVaultMetadata(jobDir, recipe, "FORJANDO", completedSectors.get())

        thread(start = true, name = "ExosysForgeMaster") {
            try {
                if (recipe.includeRoutes || recipe.routesOnly) {
                    val hwyFile = File(jobDir, "ARTERIAL.HWY")
                    if (!hwyFile.exists() || hwyFile.length() < 24L) {
                        statusHeadline = ExonedForgeI18n.tr(appCtx, "compiling_hwy")
                        compileOsmRoutesHwy(recipe, hwyFile)
                    }
                }

                if (recipe.routesOnly) {
                    currentState = ForgeState.COMPLETED
                    statusHeadline = "${ExonedForgeI18n.tr(appCtx, "routes_done")} (${recipe.jobName})"
                    ExonedMapVault.saveVaultMetadata(jobDir, recipe, "COMPLETADO", 0L)
                    return@thread
                }

                val queue = buildSectorTaskQueue(recipe)
                totalSectors.set(queue.size.toLong())
                completedSectors.set(0L)

                if (queue.isEmpty()) {
                    currentState = ForgeState.COMPLETED
                    statusHeadline = ExonedForgeI18n.tr(appCtx, "map_done")
                    ExonedMapVault.saveVaultMetadata(jobDir, recipe, "COMPLETADO", 0L)
                    return@thread
                }

                val workIndex = AtomicInteger(0)
                val workersCount = 4
                val latch = CountDownLatch(workersCount)

                for (wId in 0 until workersCount) {
                    thread(start = true, isDaemon = true, name = "ExoWorker-$wId") {
                        try {
                            while (!stopRequested.get()) {
                                while ((currentState == ForgeState.PAUSED_USER ||
                                            currentState == ForgeState.PAUSED_ENTROPY_ALERT) &&
                                    !stopRequested.get()
                                ) {
                                    Thread.sleep(400)
                                }
                                if (stopRequested.get()) break

                                if (!isDeviceOnlineConfirmed()) {
                                    if (currentState == ForgeState.RUNNING) {
                                        currentState = ForgeState.PAUSED_WAITING_NETWORK
                                        networkPauseCount.incrementAndGet()
                                        val mirrorIdx = currentGoogleMirror.get() and 3
                                        statusHeadline = ExonedForgeI18n.tr(appCtx, "auto_paused_net")
                                        detailMessage = "mt$mirrorIdx congelado sin pérdida."
                                    }
                                    Thread.sleep(1500)
                                    continue
                                } else if (currentState == ForgeState.PAUSED_WAITING_NETWORK) {
                                    currentState = ForgeState.RUNNING
                                    statusHeadline = ExonedForgeI18n.tr(appCtx, "net_restored")
                                }

                                val idx = workIndex.getAndIncrement()
                                if (idx >= queue.size) break

                                val packedCoord = queue[idx]
                                val z = ((packedCoord shr 48) and 0xFFFF).toInt()
                                val x = ((packedCoord shr 24) and 0xFFFFFF).toInt()
                                val y = (packedCoord and 0xFFFFFF).toInt()

                                val idxLang = mapOf(
                                    "es" to 0, "en" to 1, "zh" to 2, "ja" to 3,
                                    "hi" to 4, "fr" to 5, "ar" to 6, "ru" to 7
                                )[ExonedForgeI18n.getLang(appCtx)] ?: 0

                                if (ClusterQoiStorage.isSectorAlreadyWritten(jobDir, z, x, y)) {
                                    val done = completedSectors.incrementAndGet()
                                    if (done % 50L == 0L) {
                                        statusHeadline = arrayOf(
                                            "Saltando sectores ya grabados ($done/${totalSectors.get()})...",
                                            "Skipping already recorded sectors ($done/${totalSectors.get()})...",
                                            "正在跳过已写入的扇区 ($done/${totalSectors.get()})...",
                                            "記録済みセクターをスキップ中 ($done/${totalSectors.get()})...",
                                            "पहले से दर्ज सेक्टर छोड़े जा रहे हैं ($done/${totalSectors.get()})...",
                                            "Secteurs déjà enregistrés ignorés ($done/${totalSectors.get()})...",
                                            "تخطي القطاعات المسجلة مسبقًا ($done/${totalSectors.get()})...",
                                            "Пропуск уже записанных секторов ($done/${totalSectors.get()})..."
                                        )[idxLang]
                                    }
                                    continue
                                }

                                Thread.sleep((35L..85L).random())

                                var attempts = 0
                                var tileDone = false
                                while (!tileDone && !stopRequested.get()) {
                                    val pair = fetchAndProcessSingleTile(appCtx, recipe, z, x, y)
                                    if (pair != null) {
                                        val (sample, qoiBytes) = pair

                                        if (ClusterQoiStorage.writeSectorToCluster(jobDir, z, x, y, qoiBytes)) {
                                            writtenBytesDisk.addAndGet(qoiBytes.size.toLong())
                                            val doneNow = completedSectors.incrementAndGet()
                                            pushSampleToLiveMonitor(sample, doneNow)

                                            val pct = (doneNow * 100L) / totalSectors.get().coerceAtLeast(1L)
                                            val mirrorIdx = currentGoogleMirror.get() and 3
                                            val tForging = arrayOf("Forjando", "Forging", "正在制作", "作成中", "निर्मित हो रहा है", "Forge de", "جارٍ إنشاء", "Сборка")[idxLang]
                                            val tRescue = arrayOf("Rescate", "Fallback", "备用源", "バックアップ", "बैकअप", "Secours", "احتياطي", "Резерв")[idxLang]
                                            statusHeadline = "$tForging ${recipe.jobName} ($pct%) — Z$z/$x/$y"
                                            detailMessage = "GM OK: ${googleOkCount.get()} | $tRescue: ${cartoRescueCount.get()} | (n$mirrorIdx)"

                                            if (doneNow % 120L == 0L) {
                                                ExonedMapVault.saveVaultMetadata(jobDir, recipe, "FORJANDO", doneNow)
                                            }
                                            tileDone = true
                                        }
                                    } else {
                                        if (!isDeviceOnlineConfirmed()) {
                                            currentState = ForgeState.PAUSED_WAITING_NETWORK
                                            networkPauseCount.incrementAndGet()
                                            statusHeadline = ExonedForgeI18n.tr(appCtx, "auto_paused_net")
                                            Thread.sleep(1500)
                                        } else {
                                            attempts++
                                            val maxRetries = if (z <= 16) 6 else 2
                                            if (attempts >= maxRetries) {
                                                tileDone = true
                                            } else {
                                                Thread.sleep(250L * attempts)
                                            }
                                        }
                                    }
                                }
                            }
                        } finally {
                            latch.countDown()
                        }
                    }
                }

                latch.await()

                if (stopRequested.get()) {
                    currentState = ForgeState.PAUSED_USER
                    statusHeadline = ExonedForgeI18n.tr(appCtx, "paused_saved")
                    ExonedMapVault.saveVaultMetadata(jobDir, recipe, "PAUSADO", completedSectors.get())
                } else {
                    val idxLang = mapOf(
                        "es" to 0, "en" to 1, "zh" to 2, "ja" to 3,
                        "hi" to 4, "fr" to 5, "ar" to 6, "ru" to 7
                    )[ExonedForgeI18n.getLang(appCtx)] ?: 0
                    val tReadySectors = arrayOf(
                        "sectores .QOI listos.",
                        ".QOI sectors ready.",
                        "个 .QOI 扇区已就绪。",
                        "個の .QOI セクター完了。",
                        ".QOI सेक्टर तैयार हैं।",
                        "secteurs .QOI prêts.",
                        "قطاع .QOI جاهز.",
                        "секторов .QOI готово."
                    )[idxLang]
                    ClusterQoiStorage.finalizeAllPartClusters(jobDir)
                    currentState = ForgeState.COMPLETED
                    statusHeadline = "${ExonedForgeI18n.tr(appCtx, "completed_100")} (${recipe.jobName})"
                    detailMessage = "${completedSectors.get()} $tReadySectors"
                    ExonedMapVault.saveVaultMetadata(jobDir, recipe, "COMPLETADO", completedSectors.get())
                }
            } catch (t: Throwable) {
                currentState = ForgeState.ERROR
                statusHeadline = "[ERROR]: ${t.localizedMessage}"
                ExonedMapVault.saveVaultMetadata(jobDir, recipe, "PAUSADO", completedSectors.get())
            } finally {
                try {
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                } catch (_: Throwable) {}
            }
        }
    }

    fun pauseForge() {
        if (currentState == ForgeState.RUNNING || currentState == ForgeState.PAUSED_WAITING_NETWORK) {
            currentState = ForgeState.PAUSED_USER
            statusHeadline = "PAUSED (.QOI.PART)"
        }
    }

    fun resumeForgeFromPause() {
        if (currentState == ForgeState.PAUSED_USER || currentState == ForgeState.PAUSED_ENTROPY_ALERT) {
            currentState = ForgeState.RUNNING
            statusHeadline = "RUNNING..."
        }
    }

    fun stopAndSaveProgress() {
        stopRequested.set(true)
        currentState = ForgeState.PAUSED_USER
    }

    private fun pushSampleToLiveMonitor(sample: DecodedTileSample, doneCount: Long) {
        liveRecentSamples.add(sample)
        while (liveRecentSamples.size > 12) {
            liveRecentSamples.poll()
        }
        if (doneCount == 1L || doneCount % 200L == 0L) {
            witnessStripSamples.add(sample)
            while (witnessStripSamples.size > 24) {
                witnessStripSamples.poll()
            }
        }
    }

    private fun fetchAndProcessSingleTile(
        context: Context,
        recipe: ExonedForgeRecipe,
        z: Int,
        x: Int,
        y: Int
    ): Pair<DecodedTileSample, ByteArray>? {
        var rawBitmap: Bitmap? = null
        var usedSource = "GM"

        val now = System.currentTimeMillis()
        val tryGoogleFirst = (recipe.providerIdx == 0) &&
                (now >= googleCircuitBreakerUntilMs) &&
                CartoApiManager.hasPrimaryNode(context)
        val scaleParam = if (recipe.styleIdx in 0..2 || recipe.styleIdx == 5) 2 else 1

        if (tryGoogleFirst) {
            val primaryRef = CartoApiManager.getPrimaryReferer(context)

            val m1 = currentGoogleMirror.get() and 3
            val url1 = CartoApiManager.buildPrimaryTileUrl(
                ctx = context,
                x = x,
                y = y,
                z = z,
                styleIdx = recipe.styleIdx,
                mirrorOverride = m1,
                scale = scaleParam
            )
            val resp1 = if (url1 != null) {
                downloadTileHttpDetailed(url1, primaryRef)
            } else {
                HttpTileResponse(-1, null, false)
            }
            rawBitmap = TileIntegrityAuditor.decodeAndAuditRawTile(resp1.bytes, allowHiRes512 = (scaleParam == 2))

            if (rawBitmap == null) {
                if (resp1.networkError && !isDeviceOnlineConfirmed()) return null

                if (resp1.httpCode != 404) {
                    val m2 = currentGoogleMirror.incrementAndGet() and 3
                    Thread.sleep(180)
                    val url2 = CartoApiManager.buildPrimaryTileUrl(
                        ctx = context,
                        x = x,
                        y = y,
                        z = z,
                        styleIdx = recipe.styleIdx,
                        mirrorOverride = m2,
                        scale = scaleParam
                    )
                    val resp2 = if (url2 != null) {
                        downloadTileHttpDetailed(url2, primaryRef)
                    } else {
                        HttpTileResponse(-1, null, false)
                    }
                    rawBitmap = TileIntegrityAuditor.decodeAndAuditRawTile(resp2.bytes, allowHiRes512 = (scaleParam == 2))

                    if (rawBitmap == null && (resp2.httpCode == 429 || resp2.httpCode == 403 || resp2.httpCode == 503)) {
                        val strikes = consecutiveGoogleBanErrors.incrementAndGet()
                        if (strikes >= 4) {
                            googleCircuitBreakerUntilMs = System.currentTimeMillis() + 10_000L
                            consecutiveGoogleBanErrors.set(0)
                        }
                    }
                }
            }

            if (rawBitmap != null) {
                consecutiveGoogleBanErrors.set(0)
                googleOkCount.incrementAndGet()
            }
        }

        if (rawBitmap == null) {
            if (!isDeviceOnlineConfirmed()) return null
            usedSource = "CARTO"
            val sub = arrayOf("a", "b", "c")[(x + y) % 3]
            val keyParam = if (recipe.cartoApiKey.isNotBlank()) "?api_key=${URLEncoder.encode(recipe.cartoApiKey, "UTF-8")}" else ""
            val cartoUrl = "https://$sub.basemaps.cartocdn.com/dark_all/$z/$x/$y@2x.png$keyParam"
            val cResp = downloadTileHttpDetailed(cartoUrl, "https://carto.com/", recipe.cartoApiKey)
            rawBitmap = TileIntegrityAuditor.decodeAndAuditRawTile(cResp.bytes, allowHiRes512 = false)

            if (rawBitmap != null && TileIntegrityAuditor.esMosaicoWatermarkCarto(rawBitmap)) {
                rawBitmap.recycle()
                rawBitmap = null
            }

            if (rawBitmap != null) {
                cartoRescueCount.incrementAndGet()
                incrementCartoQuotaSafely(context)
            } else {
                usedSource = "ESRI"
                val esriBaseResp = downloadTileHttpDetailed(
                    "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/$z/$y/$x",
                    "https://www.arcgis.com/"
                )
                val esriBase = TileIntegrityAuditor.decodeAndAuditRawTile(esriBaseResp.bytes, allowHiRes512 = false)
                if (esriBase != null) {
                    val esriLabelsResp = downloadTileHttpDetailed(
                        "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Reference/MapServer/tile/$z/$y/$x",
                        "https://www.arcgis.com/"
                    )
                    val esriLabels = TileIntegrityAuditor.decodeAndAuditRawTile(esriLabelsResp.bytes, allowHiRes512 = false)
                    val combined = esriBase.copy(Bitmap.Config.ARGB_8888, true)
                    esriBase.recycle()
                    if (esriLabels != null) {
                        val canvas = android.graphics.Canvas(combined)
                        canvas.drawBitmap(esriLabels, 0f, 0f, null)
                        esriLabels.recycle()
                    }
                    rawBitmap = combined
                    cartoRescueCount.incrementAndGet()
                }
            }
        }

        val validBmp = rawBitmap ?: return null

        val shadedBmp = if ((usedSource == "GM" && (recipe.styleIdx in 0..2 || recipe.styleIdx == 5)) || recipe.styleIdx == 5) {
            val hiResShaded = CyberpunkShaderEngine.applyShader(
                validBmp,
                if (recipe.styleIdx == 5) 5 else recipe.styleIdx,
                quantizeForWatch = false
            )
            validBmp.recycle()
            if (hiResShaded.width != 256 || hiResShaded.height != 256) {
                val downsampled = Bitmap.createScaledBitmap(hiResShaded, 256, 256, true)
                if (downsampled !== hiResShaded) hiResShaded.recycle()
                downsampled
            } else {
                hiResShaded
            }
        } else {
            if (validBmp.width != 256 || validBmp.height != 256) {
                val downsampled = Bitmap.createScaledBitmap(validBmp, 256, 256, true)
                if (downsampled !== validBmp) validBmp.recycle()
                downsampled
            } else {
                validBmp
            }
        }

        val qoiBytes = QoiBinaryCodec.encodeToQoi565(shadedBmp)
        shadedBmp.recycle()

        val loopbackBmp = QoiBinaryCodec.decodeQoiToBitmap(qoiBytes) ?: return null
        val sample = DecodedTileSample(
            z = z,
            x = x,
            y = y,
            sourceTag = usedSource,
            qoiSizeKb = qoiBytes.size / 1024.0,
            bitmap = loopbackBmp,
            timestamp = System.currentTimeMillis()
        )
        return Pair(sample, qoiBytes)
    }

    private fun incrementCartoQuotaSafely(context: Context) {
        try {
            val method = CartoApiManager::class.java.methods.firstOrNull {
                it.name.contains("add", ignoreCase = true) || it.name.contains("increment", ignoreCase = true)
            }
            method?.invoke(CartoApiManager, context, 1L)
        } catch (_: Throwable) {}
    }

    private fun downloadTileHttpDetailed(
        urlStr: String,
        referer: String,
        bearerToken: String? = null
    ): HttpTileResponse {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6500
                readTimeout = 6500
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36")
                setRequestProperty("Referer", referer)
                if (!bearerToken.isNullOrBlank() && bearerToken.startsWith("eyJ")) {
                    setRequestProperty("Authorization", "Bearer $bearerToken")
                }
            }
            val code = conn.responseCode
            val bytes = if (code == 200) conn.inputStream.use { it.readBytes() } else null
            HttpTileResponse(httpCode = code, bytes = bytes, networkError = false)
        } catch (_: UnknownHostException) {
            isDeviceNetworkValidated = false
            HttpTileResponse(httpCode = -1, bytes = null, networkError = true)
        } catch (_: ConnectException) {
            isDeviceNetworkValidated = false
            HttpTileResponse(httpCode = -1, bytes = null, networkError = true)
        } catch (_: SocketTimeoutException) {
            HttpTileResponse(httpCode = -2, bytes = null, networkError = true)
        } catch (_: Throwable) {
            HttpTileResponse(httpCode = -3, bytes = null, networkError = true)
        } finally {
            conn?.disconnect()
        }
    }

    private fun isDeviceOnlineConfirmed(): Boolean {
        if (isDeviceNetworkValidated) {
            val ping = NetworkHealthMonitor.checkInternetPingMs()
            if (ping >= 0L) return true
            isDeviceNetworkValidated = false
            return false
        } else {
            val ping = NetworkHealthMonitor.checkInternetPingMs()
            if (ping >= 0L) {
                isDeviceNetworkValidated = true
                return true
            }
            return false
        }
    }

    private fun registerNetworkMonitor(context: Context) {
        if (networkCallbackRegistered) return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    isDeviceNetworkValidated = true
                }

                override fun onLost(network: Network) {
                    isDeviceNetworkValidated = false
                }
            })
            networkCallbackRegistered = true
        } catch (_: Throwable) {}
    }

    private fun buildSectorTaskQueue(recipe: ExonedForgeRecipe): LongArray {
        val sortedZooms = recipe.selectedZooms.sorted()
        if (sortedZooms.isEmpty()) return LongArray(0)

        val hasMask = recipe.isolateUrbanHighZoom && recipe.urbanZ16Cells.isNotEmpty()
        val tilesByZ = HashMap<Int, LinkedHashSet<Long>>()
        for (z in sortedZooms) {
            tilesByZ[z] = LinkedHashSet()
        }

        for (z in sortedZooms) {
            val (x0, y0) = TileMatrixCalculator.deg2num(recipe.north, recipe.west, z)
            val (x1, y1) = TileMatrixCalculator.deg2num(recipe.south, recipe.east, z)
            val minX = min(x0, x1)
            val maxX = max(x0, x1)
            val minY = min(y0, y1)
            val maxY = max(y0, y1)
            val setZ = tilesByZ[z]!!

            if (hasMask && z >= 15) {
                if (z == 15) {
                    for (c16 in recipe.urbanZ16Cells) {
                        val tx = UrbanScannerEngine.unpackX(c16) shr 1
                        val ty = UrbanScannerEngine.unpackY(c16) shr 1
                        if (tx in minX..maxX && ty in minY..maxY) {
                            setZ.add(UrbanScannerEngine.packXY(tx, ty))
                        }
                    }
                } else if (z == 16) {
                    for (c16 in recipe.urbanZ16Cells) {
                        val tx = UrbanScannerEngine.unpackX(c16)
                        val ty = UrbanScannerEngine.unpackY(c16)
                        if (tx in minX..maxX && ty in minY..maxY) {
                            setZ.add(c16)
                        }
                    }
                } else {
                    val shift = z - 16
                    val span = 1 shl shift
                    for (c16 in recipe.urbanZ16Cells) {
                        val baseX = UrbanScannerEngine.unpackX(c16) shl shift
                        val baseY = UrbanScannerEngine.unpackY(c16) shl shift
                        for (dx in 0 until span) {
                            val tx = baseX + dx
                            if (tx !in minX..maxX) continue
                            for (dy in 0 until span) {
                                val ty = baseY + dy
                                if (ty in minY..maxY) {
                                    setZ.add(UrbanScannerEngine.packXY(tx, ty))
                                }
                            }
                        }
                    }
                }
            } else {
                for (tx in minX..maxX) {
                    for (ty in minY..maxY) {
                        if (recipe.hasCustomPolygon && recipe.selectionRings.isNotEmpty()) {
                            val latC = UrbanScannerEngine.tileYToLat(ty + 0.5, z)
                            val lonC = UrbanScannerEngine.tileXToLon(tx + 0.5, z)
                            if (!UrbanScannerEngine.isPointInAnyRing(latC, lonC, recipe.selectionRings)) {
                                continue
                            }
                        }
                        setZ.add(UrbanScannerEngine.packXY(tx, ty))
                    }
                }
            }
        }

        // CIERRE JERÁRQUICO PADRE-HIJA OBLIGATORIO:
        // Garantiza que toda tesela hija en Z_alto tenga su tesela padre en Z_bajo
        for (idxHi in sortedZooms.size - 1 downTo 1) {
            val zHi = sortedZooms[idxHi]
            val zLo = sortedZooms[idxHi - 1]
            val shift = zHi - zLo
            val parentSet = tilesByZ[zLo] ?: continue
            val childSet = tilesByZ[zHi] ?: continue
            for (code in childSet) {
                val px = UrbanScannerEngine.unpackX(code) shr shift
                val py = UrbanScannerEngine.unpackY(code) shr shift
                parentSet.add(UrbanScannerEngine.packXY(px, py))
            }
        }

        val list = ArrayList<Long>()
        for (z in sortedZooms) {
            val setZ = tilesByZ[z] ?: continue
            for (code in setZ) {
                val tx = UrbanScannerEngine.unpackX(code)
                val ty = UrbanScannerEngine.unpackY(code)
                list.add(packZXY(z, tx, ty))
            }
        }

        val out = LongArray(list.size)
        for (i in list.indices) out[i] = list[i]
        return out
    }

    private fun packZXY(z: Int, x: Int, y: Int): Long {
        return ((z.toLong() and 0xFFFFL) shl 48) or
                ((x.toLong() and 0xFFFFFFL) shl 24) or
                (y.toLong() and 0xFFFFFFL)
    }

    private fun fastDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val x = (lon2 - lon1) * cos((lat1 + lat2) * 0.00872664626)
        val y = (lat2 - lat1)
        return (sqrt(x * x + y * y) * 111319.9).toFloat()
    }

    private fun compileOsmRoutesHwy(recipe: ExonedForgeRecipe, outFile: File) {
        try {
            val regex = when (recipe.routeProfileIdx) {
                0 -> "^(motorway|trunk|primary|secondary|motorway_link|trunk_link|primary_link|secondary_link)$"
                1 -> "^(motorway|trunk|primary|secondary|tertiary|residential|unclassified|living_street|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$"
                else -> "^(motorway|trunk|primary|secondary|tertiary|residential|unclassified|living_street|pedestrian|footway|path|track|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$"
            }
            val bbox = String.format(
                Locale.US, "%.4f,%.4f,%.4f,%.4f",
                min(recipe.south, recipe.north),
                min(recipe.west, recipe.east),
                max(recipe.south, recipe.north),
                max(recipe.west, recipe.east)
            )
            val query = "[out:json][timeout:35];way[\"highway\"~\"$regex\"]($bbox);out geom tags qt;"
            val postData = "data=" + URLEncoder.encode(query, "UTF-8")

            val conn = (URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10000
                readTimeout = 35000
            }
            conn.outputStream.use { it.write(postData.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode == 200) {
                val rawJson = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val root = JSONObject(rawJson)
                val elements = root.optJSONArray("elements") ?: JSONArray()

                val nodeMap = HashMap<Long, Int>()
                val nodesLat = ArrayList<Float>()
                val nodesLon = ArrayList<Float>()
                val edgesU = ArrayList<Int>()
                val edgesV = ArrayList<Int>()
                val edgesW = ArrayList<Float>()

                for (i in 0 until elements.length()) {
                    val el = elements.optJSONObject(i) ?: continue
                    val geom = el.optJSONArray("geometry") ?: continue
                    if (geom.length() < 2) continue

                    val tags = el.optJSONObject("tags")
                    val owRaw = tags?.optString("oneway", "no")?.lowercase(Locale.US) ?: "no"
                    val onewayMode = when {
                        owRaw == "yes" || owRaw == "1" || owRaw == "true" -> 1
                        owRaw == "-1" || owRaw == "reverse" -> -1
                        else -> 0
                    }

                    var prevIdx = -1
                    var prevLat = 0.0
                    var prevLon = 0.0

                    for (j in 0 until geom.length()) {
                        val pt = geom.optJSONObject(j) ?: continue
                        val lat = pt.optDouble("lat", Double.NaN)
                        val lon = pt.optDouble("lon", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue

                        val kLat = (lat * 100000.0).roundToInt().toLong() and 0xFFFFFFFFL
                        val kLon = (lon * 100000.0).roundToInt().toLong() and 0xFFFFFFFFL
                        val key = (kLat shl 32) or kLon

                        var uIdx = nodeMap[key] ?: -1
                        if (uIdx == -1) {
                            uIdx = nodesLat.size
                            nodeMap[key] = uIdx
                            nodesLat.add(lat.toFloat())
                            nodesLon.add(lon.toFloat())
                        }

                        if (prevIdx != -1 && prevIdx != uIdx) {
                            val dM = fastDistanceMeters(prevLat, prevLon, lat, lon).coerceAtLeast(1.0f)
                            when (onewayMode) {
                                -1 -> {
                                    edgesU.add(uIdx); edgesV.add(prevIdx); edgesW.add(dM)
                                }
                                1 -> {
                                    edgesU.add(prevIdx); edgesV.add(uIdx); edgesW.add(dM)
                                }
                                else -> {
                                    edgesU.add(prevIdx); edgesV.add(uIdx); edgesW.add(dM)
                                    edgesU.add(uIdx); edgesV.add(prevIdx); edgesW.add(dM)
                                }
                            }
                        }
                        prevIdx = uIdx
                        prevLat = lat
                        prevLon = lon
                    }
                }

                val numN = nodesLat.size
                val numE = edgesU.size
                if (numN > 0 && numE > 0) {
                    val buf = ByteBuffer.allocate(8 + numN * 12 + numE * 12).order(ByteOrder.LITTLE_ENDIAN)
                    buf.putInt(numN)
                    buf.putInt(numE)
                    for (i in 0 until numN) {
                        buf.putFloat(nodesLat[i])
                        buf.putFloat(nodesLon[i])
                        buf.putInt(0)
                    }
                    for (i in 0 until numE) {
                        buf.putInt(edgesU[i])
                        buf.putInt(edgesV[i])
                        buf.putFloat(edgesW[i])
                    }
                    FileOutputStream(outFile).use { fos ->
                        fos.write(buf.array())
                    }
                }
            }
            conn.disconnect()
        } catch (_: Throwable) {}
    }
}

// =============================================================================
// 6. BÓVEDA DE MAPAS Y CLIENTE SOCKET TCP
// =============================================================================
object ExonedMapVault {

    private const val PREFS_PC_LINK = "exosys_pc_link_prefs"

    fun getSavedPcIp(context: Context): String {
        val sp = context.getSharedPreferences(PREFS_PC_LINK, Context.MODE_PRIVATE)
        return sp.getString("pc_ip", "192.168.100.98") ?: "192.168.100.98"
    }

    fun getSavedPcPin(context: Context): String {
        val sp = context.getSharedPreferences(PREFS_PC_LINK, Context.MODE_PRIVATE)
        return sp.getString("pc_pin", "2077") ?: "2077"
    }

    fun savePcLinkCredentials(context: Context, ip: String, pin: String) {
        context.getSharedPreferences(PREFS_PC_LINK, Context.MODE_PRIVATE).edit()
            .putString("pc_ip", ip.trim())
            .putString("pc_pin", pin.trim())
            .apply()
    }

    fun getVaultRootDir(context: Context): File {
        val root = File(context.getExternalFilesDir(null), "EXOSYS_VAULT")
        if (!root.exists()) root.mkdirs()
        return root
    }

    fun getJobFolder(context: Context, jobId: String): File {
        return File(getVaultRootDir(context), jobId)
    }

    fun saveVaultMetadata(
        jobDir: File,
        recipe: ExonedForgeRecipe,
        status: String,
        completedTiles: Long
    ) {
        try {
            if (!jobDir.exists()) jobDir.mkdirs()
            val styleName = ExonedForgeI18n.styleDisplayName(recipe.styleIdx)
            val zSummary = if (recipe.routesOnly || recipe.selectedZooms.isEmpty()) {
                "SOLO RUTAS .HWY"
            } else {
                val zMin = recipe.selectedZooms.minOrNull() ?: 6
                val zMax = recipe.selectedZooms.maxOrNull() ?: 18
                "Z$zMin-Z$zMax"
            }

            val meta = JSONObject()
            meta.put("job_id", recipe.jobId)
            meta.put("job_name", recipe.jobName)
            meta.put("style_name", styleName)
            meta.put("zooms_summary", zSummary)
            meta.put("completed_tiles", completedTiles)
            meta.put("total_tiles", recipe.estimatedTiles)
            meta.put("status", status)
            meta.put("recipe_json", JSONObject(recipe.toJsonString()))

            File(jobDir, "vault_meta.json").writeText(meta.toString(2), Charsets.UTF_8)
        } catch (_: Throwable) {}
    }

    fun listLocalPhoneMaps(context: Context): List<VaultMapEntry> {
        val root = getVaultRootDir(context)
        val dirs = root.listFiles() ?: return emptyList()
        val list = ArrayList<VaultMapEntry>()

        for (d in dirs) {
            if (!d.isDirectory) continue
            val metaFile = File(d, "vault_meta.json")
            var jobName = d.name
            var styleName = "Shader Cyberpunk"
            var zoomsSummary = "Z6 - Z18"
            var completed = 0L
            var total = 0L
            var status = "GUARDADO"

            if (metaFile.exists()) {
                try {
                    val obj = JSONObject(metaFile.readText(Charsets.UTF_8))
                    jobName = obj.optString("job_name", d.name)
                    styleName = obj.optString("style_name", styleName)
                    zoomsSummary = obj.optString("zooms_summary", zoomsSummary)
                    completed = obj.optLong("completed_tiles", 0L)
                    total = obj.optLong("total_tiles", 0L)
                    status = obj.optString("status", status)
                } catch (_: Throwable) {}
            }

            val sizeBytes = computeFolderSizeBytes(d)
            val hasHighRes = (15..20).any { File(d, "Z$it").exists() }

            list.add(
                VaultMapEntry(
                    jobId = d.name,
                    jobName = jobName,
                    locationSource = "PHONE",
                    styleName = styleName,
                    zoomsSummary = zoomsSummary,
                    completedTiles = completed,
                    totalTiles = total,
                    sizeMb = sizeBytes.toDouble() / (1024.0 * 1024.0),
                    status = status,
                    hasHighResZooms = hasHighRes,
                    folderPath = d.absolutePath
                )
            )
        }
        return list.sortedByDescending { it.sizeMb }
    }

    fun pruneHighResZoomsOnly(context: Context, jobId: String): Double {
        val dir = getJobFolder(context, jobId)
        if (!dir.exists()) return 0.0
        val before = computeFolderSizeBytes(dir)
        for (z in 15..20) {
            val zFolder = File(dir, "Z$z")
            if (zFolder.exists()) zFolder.deleteRecursively()
        }
        val after = computeFolderSizeBytes(dir)
        return max(0L, before - after).toDouble() / (1024.0 * 1024.0)
    }

    fun deleteEntireMap(context: Context, jobId: String): Boolean {
        val dir = getJobFolder(context, jobId)
        return if (dir.exists()) dir.deleteRecursively() else false
    }

    private fun computeFolderSizeBytes(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var total = 0L
        val children = file.listFiles() ?: return 0L
        for (c in children) total += computeFolderSizeBytes(c)
        return total
    }

    fun exportRecipeToPublicDownloads(context: Context, recipe: ExonedForgeRecipe): Result<String> {
        val fileName = "${recipe.jobName}.exorecipe.json"
        val jsonBytes = recipe.toJsonString().toByteArray(Charsets.UTF_8)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/EXOSYS_LINK")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return Result.failure(Exception("No se pudo crear el archivo en Descargas."))
                resolver.openOutputStream(uri)?.use { it.write(jsonBytes) }
                Result.success("Download/EXOSYS_LINK/$fileName")
            } else {
                val dlDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "EXOSYS_LINK"
                )
                if (!dlDir.exists()) dlDir.mkdirs()
                val outFile = File(dlDir, fileName)
                outFile.writeBytes(jsonBytes)
                Result.success(outFile.absolutePath)
            }
        } catch (t: Throwable) {
            Result.failure(Exception(t.localizedMessage ?: "Error al guardar en Descargas"))
        }
    }

    private fun executeRawSocketHttp(
        rawIpInput: String,
        pin: String,
        method: String,
        path: String,
        bodyBytes: ByteArray = ByteArray(0),
        timeoutMs: Int = 6000
    ): Pair<Int, String> {
        val clean = rawIpInput.trim().removePrefix("http://").removePrefix("https://").removeSuffix("/")
        val host = clean.substringBefore(":")
        val port = clean.substringAfter(":", "8890").toIntOrNull() ?: 8890

        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs

            val headerBuilder = StringBuilder()
            headerBuilder.append("$method $path HTTP/1.1\r\n")
            headerBuilder.append("Host: $host:$port\r\n")
            headerBuilder.append("X-Exosys-Pin: ${pin.trim()}\r\n")
            headerBuilder.append("Connection: close\r\n")
            if (bodyBytes.isNotEmpty()) {
                headerBuilder.append("Content-Type: application/json; charset=UTF-8\r\n")
                headerBuilder.append("Content-Length: ${bodyBytes.size}\r\n")
            } else {
                headerBuilder.append("Content-Length: 0\r\n")
            }
            headerBuilder.append("\r\n")

            val out = socket.getOutputStream()
            out.write(headerBuilder.toString().toByteArray(Charsets.UTF_8))
            if (bodyBytes.isNotEmpty()) {
                out.write(bodyBytes)
            }
            out.flush()

            val rawResponse = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val statusLine = rawResponse.lineSequence().firstOrNull() ?: ""
            val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1

            val separatorIdx = rawResponse.indexOf("\r\n\r\n")
            val bodyStr = if (separatorIdx >= 0) {
                rawResponse.substring(separatorIdx + 4)
            } else {
                ""
            }
            return Pair(statusCode, bodyStr)
        }
    }

    fun autoDiscoverPcIpOnLan(onFound: (String?) -> Unit) {
        thread(start = true, isDaemon = true) {
            try {
                var phoneIp: String? = null
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val nif = interfaces.nextElement()
                    if (!nif.isUp || nif.isLoopback) continue
                    val addrs = nif.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                                phoneIp = ip
                                break
                            }
                        }
                    }
                    if (phoneIp != null) break
                }

                val subnet = phoneIp?.substringBeforeLast(".") ?: return@thread onFound(null)
                val foundIp = AtomicReference<String?>(null)
                val latch = CountDownLatch(254)
                val pool = Executors.newFixedThreadPool(36)

                for (i in 1..254) {
                    pool.execute {
                        try {
                            if (foundIp.get() == null) {
                                val cand = "$subnet.$i"
                                val (code, body) = executeRawSocketHttp(cand, "2077", "GET", "/", timeoutMs = 280)
                                if (code == 200 || body.contains("EXOSYS")) {
                                    foundIp.compareAndSet(null, cand)
                                }
                            }
                        } catch (_: Throwable) {
                        } finally {
                            latch.countDown()
                        }
                    }
                }
                latch.await(2200, TimeUnit.MILLISECONDS)
                pool.shutdownNow()
                onFound(foundIp.get())
            } catch (_: Throwable) {
                onFound(null)
            }
        }
    }

    fun sendRecipeToPcOverNetwork(
        pcIp: String,
        pcPin: String,
        recipe: ExonedForgeRecipe,
        onResult: (Result<String>) -> Unit
    ) {
        thread(start = true, isDaemon = true) {
            try {
                val payload = recipe.toJsonString().toByteArray(Charsets.UTF_8)
                val (code, respBody) = executeRawSocketHttp(
                    rawIpInput = pcIp,
                    pin = pcPin,
                    method = "POST",
                    path = "/api/submit_job",
                    bodyBytes = payload,
                    timeoutMs = 7000
                )
                if (code == 200) {
                    onResult(Result.success(respBody))
                } else {
                    onResult(Result.failure(Exception("HTTP $code ($respBody). Verifica el PIN.")))
                }
            } catch (t: Throwable) {
                onResult(
                    Result.failure(
                        Exception("No se pudo conectar con $pcIp:8890 (${t.localizedMessage}).")
                    )
                )
            }
        }
    }

    fun fetchRemotePcVault(
        pcIp: String,
        pcPin: String,
        onResult: (Result<List<VaultMapEntry>>) -> Unit
    ) {
        thread(start = true, isDaemon = true) {
            try {
                val (code, raw) = executeRawSocketHttp(
                    rawIpInput = pcIp,
                    pin = pcPin,
                    method = "GET",
                    path = "/api/vault_list",
                    timeoutMs = 4000
                )
                if (code != 200) {
                    return@thread onResult(Result.failure(Exception("HTTP $code")))
                }
                val root = JSONObject(raw)
                val arr = root.optJSONArray("maps") ?: JSONArray()
                val list = ArrayList<VaultMapEntry>()

                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        VaultMapEntry(
                            jobId = o.optString("job_id"),
                            jobName = o.optString("job_name"),
                            locationSource = o.optString("source", "PC_DISK"),
                            styleName = o.optString("style_name", "Cyberpunk"),
                            zoomsSummary = o.optString("zooms_summary", "Z6-Z18"),
                            completedTiles = o.optLong("completed_tiles", 0L),
                            totalTiles = o.optLong("total_tiles", 0L),
                            sizeMb = o.optDouble("size_mb", 0.0),
                            status = o.optString("status", "COMPLETADO"),
                            hasHighResZooms = o.optBoolean("has_high_res", true),
                            folderPath = o.optString("folder_path", "")
                        )
                    )
                }
                onResult(Result.success(list))
            } catch (t: Throwable) {
                onResult(Result.failure(t))
            }
        }
    }

    fun requestRemotePcDelete(
        pcIp: String,
        pcPin: String,
        jobId: String,
        pruneHighResOnly: Boolean,
        onResult: (Result<String>) -> Unit
    ) {
        thread(start = true, isDaemon = true) {
            try {
                val mode = if (pruneHighResOnly) "prune_high_res" else "delete_all"
                val path = "/api/vault_delete?job_id=${URLEncoder.encode(jobId, "UTF-8")}&mode=$mode"
                val (code, _) = executeRawSocketHttp(
                    rawIpInput = pcIp,
                    pin = pcPin,
                    method = "POST",
                    path = path,
                    timeoutMs = 5000
                )
                if (code == 200) {
                    onResult(Result.success("OK"))
                } else {
                    onResult(Result.failure(Exception("HTTP $code")))
                }
            } catch (t: Throwable) {
                onResult(Result.failure(t))
            }
        }
    }
}

// =============================================================================
// 7. INTERFACES GRÁFICAS DE ENVÍO Y MONITOR EN ANDROID (MULTILENGUAJE)
// =============================================================================
object ExonedForgeUi {

    private val COLOR_PANEL = Color.parseColor("#0A1114")
    private val COLOR_CARD = Color.parseColor("#061418")
    private val COLOR_CYAN = Color.parseColor("#00E5FF")
    private val COLOR_GREEN = Color.parseColor("#00FF41")
    private val COLOR_AMBER = Color.parseColor("#FFB300")
    private val COLOR_DARK = Color.parseColor("#061A1E")
    private val COLOR_CRITICO = Color.parseColor("#FF003C")

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun langIdx(ctx: Context): Int {
        return mapOf(
            "es" to 0, "en" to 1, "zh" to 2, "ja" to 3,
            "hi" to 4, "fr" to 5, "ar" to 6, "ru" to 7
        )[ExonedForgeI18n.getLang(ctx)] ?: 0
    }

    private fun obtenerEspacioLibreTelefonoMb(): Double {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val bytes = stat.availableBlocksLong * stat.blockSizeLong
            (bytes.toDouble() / (1024.0 * 1024.0)) - 400.0
        } catch (_: Throwable) {
            4096.0
        }
    }

    fun mostrarModalDestinoYForja(context: Context, recipe: ExonedForgeRecipe, forcePcOnly: Boolean = false) {
        val mainHandler = Handler(Looper.getMainLooper())
        val idxLang = langIdx(context)
        val freePhoneMb = obtenerEspacioLibreTelefonoMb().coerceAtLeast(0.0)
        val fitsInPhone = !forcePcOnly && (recipe.estimatedWeightMb <= freePhoneMb)

        val scroll = ScrollView(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
            background = GradientDrawable().apply {
                setColor(COLOR_PANEL)
                setStroke(dp(context, 2), COLOR_CYAN)
                cornerRadius = dp(context, 10).toFloat()
            }
        }
        scroll.addView(root)

        val txtTitle = TextView(context).apply {
            text = if (recipe.routesOnly) {
                ExonedForgeI18n.tr(context, "forge_title_hwy")
            } else {
                ExonedForgeI18n.tr(context, "forge_title_qoi")
            }
            setTextColor(if (recipe.routesOnly) COLOR_AMBER else COLOR_CYAN)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(context, 6))
        }

        val tilesFmt = String.format(Locale.US, "%,d", recipe.estimatedTiles)
        val weightFmt = String.format(Locale.US, "%,.2f", recipe.estimatedWeightMb)
        val freeFmt = String.format(Locale.US, "%,.0f", freePhoneMb)
        val styleLabel = ExonedForgeI18n.styleDisplayName(recipe.styleIdx)

        val txtSummary = TextView(context).apply {
            text = arrayOf(
                "Paquete: ${recipe.jobName} ($styleLabel)\nSectores QOI: $tilesFmt  |  Peso estimado: $weightFmt MB\nEspacio libre en teléfono: $freeFmt MB",
                "Package: ${recipe.jobName} ($styleLabel)\nQOI Sectors: $tilesFmt  |  Estimated size: $weightFmt MB\nFree space on phone: $freeFmt MB",
                "数据包：${recipe.jobName} ($styleLabel)\nQOI 扇区：$tilesFmt  |  预计大小：$weightFmt MB\n手机可用空间：$freeFmt MB",
                "パッケージ: ${recipe.jobName} ($styleLabel)\nQOIセクター: $tilesFmt  |  推定サイズ: $weightFmt MB\nスマホ空き容量: $freeFmt MB",
                "पैकेज: ${recipe.jobName} ($styleLabel)\nQOI सेक्टर: $tilesFmt  |  अनुमानित आकार: $weightFmt MB\nफ़ोन में खाली जगह: $freeFmt MB",
                "Paquet : ${recipe.jobName} ($styleLabel)\nSecteurs QOI : $tilesFmt  |  Poids estimé : $weightFmt MB\nEspace libre sur téléphone : $freeFmt MB",
                "الحزمة: ${recipe.jobName} ($styleLabel)\nقطاعات QOI: $tilesFmt  |  الحجم المقدر: $weightFmt MB\nالمساحة الحرة في الهاتف: $freeFmt MB",
                "Пакет: ${recipe.jobName} ($styleLabel)\nСекторы QOI: $tilesFmt  |  Расчётный вес: $weightFmt MB\nСвободно на телефоне: $freeFmt MB"
            )[idxLang]
            setTextColor(Color.WHITE)
            textSize = 12f
            setLineSpacing(0f, 1.15f)
            setPadding(0, 0, 0, dp(context, 10))
        }

        val fireBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (recipe.routesOnly) View.GONE else View.VISIBLE
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
            background = GradientDrawable().apply {
                setColor(COLOR_CARD)
                setStroke(dp(context, 1), COLOR_GREEN)
                cornerRadius = dp(context, 6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 10) }
        }

        val txtFireHeader = TextView(context).apply {
            text = ExonedForgeI18n.tr(context, "fire_hdr")
            setTextColor(COLOR_GREEN)
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
        }

        val txtFireStatus = TextView(context).apply {
            text = ExonedForgeI18n.tr(context, "fire_desc")
            setTextColor(Color.LTGRAY)
            textSize = 11f
            setPadding(0, dp(context, 4), 0, dp(context, 6))
        }

        val gridPreviewRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(context, 4), 0, dp(context, 4))
        }

        val btnRunFireTest = Button(context).apply {
            text = ExonedForgeI18n.tr(context, "btn_fire_run")
            setBackgroundColor(COLOR_DARK)
            setTextColor(COLOR_GREEN)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 11f
            setOnClickListener {
                isEnabled = false
                text = ExonedForgeI18n.tr(context, "btn_fire_running")
                ExonedForgeEngine.runFourTileFireTest(context, recipe) { res ->
                    mainHandler.post {
                        isEnabled = true
                        text = ExonedForgeI18n.tr(context, "btn_fire_retry")
                        res.onSuccess { samples ->
                            gridPreviewRow.removeAllViews()
                            gridPreviewRow.visibility = View.VISIBLE
                            for (smp in samples) {
                                val col = LinearLayout(context).apply {
                                    orientation = LinearLayout.VERTICAL
                                    gravity = Gravity.CENTER
                                    setPadding(dp(context, 3), 0, dp(context, 3), 0)
                                }
                                val img = ImageView(context).apply {
                                    layoutParams = LinearLayout.LayoutParams(dp(context, 64), dp(context, 64))
                                    setImageBitmap(smp.bitmap)
                                    scaleType = ImageView.ScaleType.FIT_XY
                                }
                                val lbl = TextView(context).apply {
                                    text = "Z${smp.z} (${smp.sourceTag})\n${String.format(Locale.US, "%.1f", smp.qoiSizeKb)}KB"
                                    setTextColor(if (smp.sourceTag == "GM") COLOR_GREEN else COLOR_CYAN)
                                    textSize = 9.0f
                                    gravity = Gravity.CENTER
                                }
                                col.addView(img)
                                col.addView(lbl)
                                gridPreviewRow.addView(col)
                            }
                            val gmHits = samples.count { it.sourceTag == "GM" }
                            txtFireStatus.setTextColor(COLOR_GREEN)
                            txtFireStatus.text = "[OK] ($gmHits/4 GM) -> .QOI ($styleLabel)"
                        }.onFailure { err ->
                            txtFireStatus.setTextColor(COLOR_CRITICO)
                            txtFireStatus.text = "[ERROR]: ${err.localizedMessage}"
                        }
                    }
                }
            }
        }

        fireBox.addView(txtFireHeader)
        fireBox.addView(txtFireStatus)
        fireBox.addView(gridPreviewRow)
        fireBox.addView(btnRunFireTest)

        val pcBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
            background = GradientDrawable().apply {
                setColor(COLOR_CARD)
                setStroke(dp(context, 1), COLOR_CYAN)
                cornerRadius = dp(context, 6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 10) }
        }

        val txtPcTitle = TextView(context).apply {
            text = ExonedForgeI18n.tr(context, "pc_sec_title")
            setTextColor(COLOR_CYAN)
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
        }

        val txtPcSub = TextView(context).apply {
            text = ExonedForgeI18n.tr(context, "pc_sec_sub")
            setTextColor(Color.LTGRAY)
            textSize = 11f
            setPadding(0, dp(context, 4), 0, dp(context, 8))
        }

        val btnSendWifiPc = Button(context).apply {
            text = ExonedForgeI18n.tr(context, "btn_send_pc_live")
            setBackgroundColor(COLOR_CYAN)
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 11f
            setOnClickListener {
                mostrarDialogoEnvioDirectoPc(context, recipe)
            }
        }

        val tSavedAt = arrayOf("Guardado en:", "Saved at:", "已保存至：", "保存先:", "यहाँ सहेजा गया:", "Enregistré dans :", "تم الحفظ في:", "Сохранено в:")[idxLang]

        val btnExportUsbFile = Button(context).apply {
            text = ExonedForgeI18n.tr(context, "btn_save_json_usb")
            setBackgroundColor(COLOR_DARK)
            setTextColor(COLOR_CYAN)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 10.5f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 6) }
            setOnClickListener {
                val res = ExonedMapVault.exportRecipeToPublicDownloads(context, recipe)
                res.onSuccess { path ->
                    Toast.makeText(context, "$tSavedAt $path", Toast.LENGTH_LONG).show()
                    mostrarGuiaEnlacePcDialog(context, tabInicial = 2)
                }.onFailure { err ->
                    Toast.makeText(context, "Error: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }

        val btnGuidePc = Button(context).apply {
            text = ExonedForgeI18n.tr(context, "btn_guide_pc")
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(COLOR_AMBER)
            textSize = 10.5f
            setOnClickListener { mostrarGuiaEnlacePcDialog(context, tabInicial = 0) }
        }

        pcBox.addView(txtPcTitle)
        pcBox.addView(txtPcSub)
        pcBox.addView(btnSendWifiPc)
        pcBox.addView(btnExportUsbFile)
        pcBox.addView(btnGuidePc)

        val phoneBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
            background = GradientDrawable().apply {
                setColor(COLOR_CARD)
                setStroke(dp(context, 1), if (fitsInPhone) COLOR_GREEN else COLOR_CRITICO)
                cornerRadius = dp(context, 6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 10) }
        }

        val txtPhoneTitle = TextView(context).apply {
            text = ExonedForgeI18n.tr(context, "phone_sec_title")
            setTextColor(if (fitsInPhone) COLOR_GREEN else COLOR_CRITICO)
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
        }

        val txtPhoneDesc = TextView(context).apply {
            text = when {
                forcePcOnly -> arrayOf(
                    "Selección Multi-Estado activa: Por volumen se requiere compilar en la PC (Opción 2).",
                    "Multi-State selection active: Due to volume, compiling on PC is required (Option 2).",
                    "多州/省选区已激活：由于数据量巨大，需要在电脑端编译（选项 2）。",
                    "複数州選択が有効：大容量のためPCでのコンパイルが必要です（オプション2）。",
                    "बहु-राज्य चयन सक्रिय: अधिक आकार के कारण PC पर संकलन आवश्यक है (विकल्प 2)।",
                    "Sélection Multi-États active : En raison du volume, la compilation sur PC est requise (Option 2).",
                    "تحديد عدة ولايات نشط: نظرًا للحجم الكبير، يلزم التجميع على الكمبيوتر (الخيار 2).",
                    "Активен выбор нескольких регионов: из-за объёма требуется сборка на ПК (Опция 2)."
                )[idxLang]
                fitsInPhone -> arrayOf(
                    "Auto-pausa si pierdes señal en movimiento y guarda cada sector al instante (.QOI.PART).",
                    "Auto-pauses if you lose signal on the move and saves every sector instantly (.QOI.PART).",
                    "移动中若丢失信号会自动暂停，并即时保存每个扇区 (.QOI.PART)。",
                    "移動中に電波が途切れると自動一時停止し、各セクターを即座に保存します (.QOI.PART)。",
                    "चलते समय सिग्नल खोने पर स्वतः रुक जाता है और हर सेक्टर को तुरंत सहेजता है (.QOI.PART)।",
                    "Pause auto en cas de perte de signal et sauvegarde instantanée de chaque secteur (.QOI.PART).",
                    "يتوقف تلقائيًا عند فقدان الإشارة أثناء التنقل ويحفظ كل قطاع فورًا (.QOI.PART).",
                    "Автопауза при потере сигнала в пути и мгновенное сохранение каждого сектора (.QOI.PART)."
                )[idxLang]
                else -> arrayOf(
                    "Aviso: Espacio insuficiente en el teléfono ($weightFmt MB requeridos vs $freeFmt MB libres). Usa la Opción 2 (PC).",
                    "Notice: Insufficient space on phone ($weightFmt MB required vs $freeFmt MB free). Use Option 2 (PC).",
                    "提示：手机存储空间不足（需要 $weightFmt MB，剩余 $freeFmt MB）。请使用选项 2（电脑端）。",
                    "注意：スマホの空き容量が不足しています（必要: $weightFmt MB / 空き: $freeFmt MB）。オプション2（PC）を使用してください。",
                    "सूचना: फ़ोन में अपर्याप्त स्थान ($weightFmt MB आवश्यक बनाम $freeFmt MB खाली)। विकल्प 2 (PC) का उपयोग करें।",
                    "Avis : Espace insuffisant sur le téléphone ($weightFmt MB requis vs $freeFmt MB libres). Utilisez l'Option 2 (PC).",
                    "تنبيه: مساحة غير كافية في الهاتف (مطلوب $weightFmt MB مقابل $freeFmt MB متاح). استخدم الخيار 2 (الكمبيوتر).",
                    "Внимание: Недостаточно места на телефоне (требуется $weightFmt MB, свободно $freeFmt MB). Используйте Опцию 2 (ПК)."
                )[idxLang]
            }
            setTextColor(if (fitsInPhone) Color.LTGRAY else COLOR_AMBER)
            textSize = 11f
            setPadding(0, dp(context, 4), 0, dp(context, 8))
        }

        var parentDialog: AlertDialog? = null

        val btnStartPhoneForge = Button(context).apply {
            text = when {
                forcePcOnly -> arrayOf(
                    "REQUIERE PC (MODO MULTI-ESTADO ACTIVO)",
                    "PC REQUIRED (MULTI-STATE MODE ACTIVE)",
                    "需要电脑（多州模式已激活）",
                    "PC必須（複数州モード有効）",
                    "PC आवश्यक (बहु-राज्य मोड सक्रिय)",
                    "PC REQUIS (MODE MULTI-ÉTATS ACTIF)",
                    "يتطلب كمبيوتر (وضع عدة ولايات نشط)",
                    "ТРЕБУЕТСЯ ПК (АКТИВЕН МУЛЬТИ-РЕГИОН)"
                )[idxLang]
                fitsInPhone -> arrayOf(
                    "INICIAR DESCARGA EN TELÉFONO ($weightFmt MB)",
                    "START DOWNLOAD ON PHONE ($weightFmt MB)",
                    "在手机上开始下载 ($weightFmt MB)",
                    "スマホでダウンロード開始 ($weightFmt MB)",
                    "फ़ोन पर डाउनलोड शुरू करें ($weightFmt MB)",
                    "DÉMARRER TÉLÉCHARGEMENT SUR TÉLÉPHONE ($weightFmt MB)",
                    "بدء التنزيل على الهاتف ($weightFmt MB)",
                    "НАЧАТЬ ЗАГРУЗКУ НА ТЕЛЕФОНЕ ($weightFmt MB)"
                )[idxLang]
                else -> arrayOf(
                    "BLOQUEADO EN TELÉFONO (FALTA ESPACIO LOCAL)",
                    "LOCKED ON PHONE (INSUFFICIENT LOCAL SPACE)",
                    "手机端已锁定（本地空间不足）",
                    "スマホ制限中（ローカル容量不足）",
                    "फ़ोन पर लॉक (स्थानीय स्थान अपर्याप्त)",
                    "BLOQUÉ SUR TÉLÉPHONE (ESPACE LOCAL INSUFFISANT)",
                    "مقفل على الهاتف (مساحة محلية غير كافية)",
                    "ЗАБЛОКИРОВАНО (НЕДОСТАТОЧНО МЕСТА)"
                )[idxLang]
            }
            isEnabled = fitsInPhone
            setBackgroundColor(if (fitsInPhone) COLOR_GREEN else Color.DKGRAY)
            setTextColor(if (fitsInPhone) Color.BLACK else Color.LTGRAY)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 11.5f
            setOnClickListener {
                parentDialog?.dismiss()
                ExonedForgeEngine.startOrResumePhoneForge(context, recipe)
                mostrarMonitorForjaEnVivoDialog(context)
            }
        }

        val btnOpenVault = Button(context).apply {
            text = ExonedForgeI18n.tr(context, "btn_open_vault")
            setBackgroundColor(COLOR_DARK)
            setTextColor(COLOR_AMBER)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 10.5f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 6) }
            setOnClickListener {
                parentDialog?.dismiss()
                mostrarBovedaSincronizadaDialog(context)
            }
        }

        phoneBox.addView(txtPhoneTitle)
        phoneBox.addView(txtPhoneDesc)
        phoneBox.addView(btnStartPhoneForge)
        phoneBox.addView(btnOpenVault)

        val btnClose = Button(context).apply {
            text = ExonedForgeI18n.tr(context, "btn_back_map")
            setBackgroundColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            setOnClickListener { parentDialog?.dismiss() }
        }

        root.addView(txtTitle)
        root.addView(txtSummary)
        root.addView(fireBox)
        root.addView(pcBox)
        root.addView(phoneBox)
        root.addView(btnClose)

        parentDialog = AlertDialog.Builder(context).setView(scroll).create()
        parentDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        parentDialog.show()
    }

    fun mostrarGuiaEnlacePcDialog(context: Context, tabInicial: Int = 0) {
        val idxLang = langIdx(context)
        val scroll = ScrollView(context)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
            background = GradientDrawable().apply {
                setColor(COLOR_PANEL)
                setStroke(dp(context, 2), COLOR_AMBER)
                cornerRadius = dp(context, 10).toFloat()
            }
        }
        scroll.addView(box)

        val title = TextView(context).apply {
            text = arrayOf(
                "GUÍA DE ENLACE: TELÉFONO ⇄ PC",
                "LINK GUIDE: PHONE ⇄ PC",
                "连接指南：手机 ⇄ 电脑",
                "接続ガイド：スマホ ⇄ PC",
                "लिंक गाइड: फ़ोन ⇄ PC",
                "GUIDE DE LIAISON : TÉLÉPHONE ⇄ PC",
                "دليل الربط: الهاتف ⇄ الكمبيوتر",
                "РУКОВОДСТВО ПО СВЯЗИ: ТЕЛЕФОН ⇄ ПК"
            )[idxLang]
            setTextColor(COLOR_AMBER)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(context, 10))
        }

        val tabsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(context, 10))
        }

        val btnTabWifi = Button(context).apply {
            text = arrayOf("1. WI-FI / HOTSPOT", "1. WI-FI / HOTSPOT", "1. WI-FI / 热点", "1. WI-FI / テザリング", "1. WI-FI / हॉटस्पॉट", "1. WI-FI / HOTSPOT", "1. واي فاي / نقطة اتصال", "1. WI-FI / ХОТСПОТ")[idxLang]
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 36), 1f).apply { rightMargin = dp(context, 3) }
        }
        val btnTabUsbNet = Button(context).apply {
            text = arrayOf("2. ANCLAJE USB", "2. USB TETHERING", "2. USB 网络共享", "2. USBテザリング", "2. USB टेदरिंग", "2. PARTAGE USB", "2. تقييد USB", "2. USB-МОДЕМ")[idxLang]
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 36), 1f).apply { rightMargin = dp(context, 3) }
        }
        val btnTabUsbMtp = Button(context).apply {
            text = arrayOf("3. CABLE ARCHIVOS", "3. USB FILE TRANSFER", "3. USB 传文件", "3. USBファイル転送", "3. USB फ़ाइल ट्रांसफर", "3. CÂBLE FICHIERS", "3. نقل ملفات USB", "3. КАБЕЛЬ (ФАЙЛЫ)")[idxLang]
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 36), 1f)
        }

        tabsRow.addView(btnTabWifi)
        tabsRow.addView(btnTabUsbNet)
        tabsRow.addView(btnTabUsbMtp)

        val txtBody = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setLineSpacing(0f, 1.22f)
            setPadding(dp(context, 6), dp(context, 4), dp(context, 6), dp(context, 12))
        }

        fun selectTab(idx: Int) {
            btnTabWifi.setBackgroundColor(if (idx == 0) COLOR_CYAN else COLOR_DARK)
            btnTabWifi.setTextColor(if (idx == 0) Color.BLACK else COLOR_CYAN)

            btnTabUsbNet.setBackgroundColor(if (idx == 1) COLOR_GREEN else COLOR_DARK)
            btnTabUsbNet.setTextColor(if (idx == 1) Color.BLACK else COLOR_GREEN)

            btnTabUsbMtp.setBackgroundColor(if (idx == 2) COLOR_AMBER else COLOR_DARK)
            btnTabUsbMtp.setTextColor(if (idx == 2) Color.BLACK else COLOR_AMBER)

            txtBody.text = when (idx) {
                0 -> arrayOf(
                    "OPCIÓN 1: MISMA RED LOCAL (WI-FI / ETHERNET) O HOTSPOT PERSONAL\n\n• EN CASA / OFICINA:\n  [1] Conecta tu teléfono al Wi-Fi y tu PC al mismo módem (por Wi-Fi o por cable Ethernet).\n  [2] Abre el programa en la computadora.\n  [3] En el Paso 1 de la PC verás la IP única y el PIN (2077).\n\n• SI ESTÁS FUERA DE CASA (MODO HOTSPOT):\n  [1] En tu teléfono enciende Zona Wi-Fi personal / Hotspot.\n  [2] Conecta el Wi-Fi de tu PC a la red de tu teléfono.",
                    "OPTION 1: SAME LOCAL NETWORK (WI-FI / ETHERNET) OR PERSONAL HOTSPOT\n\n• AT HOME / OFFICE:\n  [1] Connect your phone to Wi-Fi and your PC to the same router (via Wi-Fi or Ethernet cable).\n  [2] Open the program on your computer.\n  [3] In Step 1 on the PC you will see the unique IP and PIN (2077).\n\n• IF YOU ARE AWAY FROM HOME (HOTSPOT MODE):\n  [1] Turn on Personal Hotspot on your phone.\n  [2] Connect your PC's Wi-Fi to your phone's network.",
                    "选项 1：同一局域网（WI-FI / 网线）或手机个人热点\n\n• 在家 / 办公室：\n  [1] 将手机连接到 Wi-Fi，并将电脑连接到同一路由器（Wi-Fi 或网线均可）。\n  [2] 打开电脑端程序。\n  [3] 在电脑端第 1 步中即可看到专属 IP 和 PIN (2077)。\n\n• 如果在户外（热点模式）：\n  [1] 在手机上开启个人热点。\n  [2] 将电脑 Wi-Fi 连接到手机热点。",
                    "オプション 1：同一ローカルネットワーク（WI-FI / 有線LAN）またはテザリング\n\n• 自宅 / オフィス：\n  [1] スマホをWi-Fiに、PCを同じルーター（Wi-FiまたはLANケーブル）に接続します。\n  [2] PCでプログラムを開きます。\n  [3] PCのステップ1に固有のIPとPIN（2077）が表示されます。\n\n• 外出先の場合（ホットスポットモード）：\n  [1] スマホのインターネット共有（テザリング）をオンにします。\n  [2] PCのWi-Fiをスマホに接続します。",
                    "विकल्प 1: एक ही लोकल नेटवर्क (WI-FI / ETHERNET) या पर्सनल हॉटस्पॉट\n\n• घर / ऑफिस में:\n  [1] अपने फ़ोन को Wi-Fi से और PC को उसी राउटर (Wi-Fi या Ethernet केबल) से जोड़ें।\n  [2] कंप्यूटर पर प्रोग्राम खोलें।\n  [3] PC के चरण 1 में आपको IP और PIN (2077) दिखाई देगा।\n\n• घर से बाहर (हॉटस्पॉट मोड):\n  [1] अपने फ़ोन में पर्सनल हॉटस्पॉट चालू करें।\n  [2] अपने PC के Wi-Fi को फ़ोन के नेटवर्क से जोड़ें।",
                    "OPTION 1 : MÊME RÉSEAU LOCAL (WI-FI / ETHERNET) OU HOTSPOT PERSONNEL\n\n• À LA MAISON / BUREAU :\n  [1] Connectez votre téléphone au Wi-Fi et votre PC au même routeur (Wi-Fi ou câble Ethernet).\n  [2] Ouvrez le programme sur l'ordinateur.\n  [3] À l'Étape 1 du PC, vous verrez l'IP unique et le PIN (2077).\n\n• EN DÉPLACEMENT (MODE HOTSPOT) :\n  [1] Activez le Point d'accès Wi-Fi sur votre téléphone.\n  [2] Connectez le Wi-Fi du PC au réseau de votre téléphone.",
                    "الخيار 1: نفس الشبكة المحلية (واي فاي / إيثرنت) أو نقطة اتصال شخصية\n\n• في المنزل / المكتب:\n  [1] قم بتوصيل هاتفك بالواي فاي والكمبيوتر بنفس الراوتر.\n  [2] افتح البرنامج على الكمبيوتر.\n  [3] في الخطوة 1 على الكمبيوتر ستظهر لك عنوان IP والرمز (2077).\n\n• خارج المنزل (وضع نقطة الاتصال):\n  [1] شغّل نقطة الاتصال الشخصية في هاتفك.\n  [2] وصّل واي فاي الكمبيوتر بشبكة هاتفك.",
                    "ОПЦИЯ 1: ОДНА ЛОКАЛЬНАЯ СЕТЬ (WI-FI / ETHERNET) ИЛИ ТОЧКА ДОСТУПА\n\n• ДОМА / В ОФИСЕ:\n  [1] Подключите телефон к Wi-Fi, а ПК к тому же роутеру (по Wi-Fi или кабелю Ethernet).\n  [2] Откройте программу на компьютере.\n  [3] На Шаге 1 на ПК вы увидите IP и PIN-код (2077).\n\n• ВНЕ ДОМА (РЕЖИМ ХОТСПОТА):\n  [1] Включите точку доступа Wi-Fi на телефоне.\n  [2] Подключите ПК по Wi-Fi к сети телефона."
                )[idxLang]
                1 -> arrayOf(
                    "OPCIÓN 2: CABLE USB EN TIEMPO REAL (ANCLAJE DE RED USB)\n\n  [1] Conecta el teléfono a la PC usando un cable USB.\n  [2] En tu teléfono entra a: Ajustes -> Conexiones -> Zona Wi-Fi y Anclaje de red.\n  [3] Activa 'Anclaje por USB' (Compartir conexión por USB).\n  [4] En la ventana de tu computadora aparecerá la IP de cable.",
                    "OPTION 2: REAL-TIME USB CABLE (USB NETWORK TETHERING)\n\n  [1] Connect your phone to the PC using a USB cable.\n  [2] On your phone go to: Settings -> Connections -> Mobile Hotspot and Tethering.\n  [3] Enable 'USB Tethering'.\n  [4] The wired IP will appear in your computer window.",
                    "选项 2：实时 USB 数据线连接（USB 网络共享）\n\n  [1] 使用 USB 数据线将手机连接到电脑。\n  [2] 在手机上进入：设置 -> 移动网络/连接 -> 个人热点。\n  [3] 开启“USB 网络共享”。\n  [4] 电脑窗口中将显示有线 IP。",
                    "オプション 2：リアルタイムUSBケーブル接続（USBテザリング）\n\n  [1] USBケーブルでスマホとPCを接続します。\n  [2] スマホの「設定 -> 接続 -> テザリング」を開きます。\n  [3] 「USBテザリング」をオンにします。\n  [4] PCのウィンドウに有線IPが表示されます。",
                    "विकल्प 2: रीयल-टाइम USB केबल (USB नेटवर्क टेदरिंग)\n\n  [1] USB केबल का उपयोग करके फ़ोन को PC से जोड़ें।\n  [2] फ़ोन में जाएं: सेटिंग्स -> कनेक्शन -> मोबाइल हॉटस्पॉट और टेदरिंग।\n  [3] 'USB टेदरिंग' चालू करें।\n  [4] आपके कंप्यूटर विंडो में केबल IP दिखाई देगा।",
                    "OPTION 2 : CÂBLE USB EN TEMPS RÉEL (PARTAGE DE CONNEXION USB)\n\n  [1] Connectez le téléphone au PC avec un câble USB.\n  [2] Sur le téléphone : Paramètres -> Connexions -> Point d'accès mobile et modem.\n  [3] Activez 'Modem USB' (Partage par USB).\n  [4] L'IP filaire apparaîtra dans la fenêtre de votre ordinateur.",
                    "الخيار 2: كابل USB في الوقت الفعلي (تقييد شبكة USB)\n\n  [1] وصّل الهاتف بالكمبيوتر باستخدام كابل USB.\n  [2] في هاتفك ادخل إلى: الإعدادات -> الاتصالات -> نقطة الاتصال والتقييد.\n  [3] فعّل 'تقييد USB'.\n  [4] سيظهر عنوان IP السلكي في نافذة الكمبيوتر.",
                    "ОПЦИЯ 2: USB-КАБЕЛЬ В РЕАЛЬНОМ ВРЕМЕНИ (USB-МОДЕМ)\n\n  [1] Подключите телефон к ПК с помощью USB-кабеля.\n  [2] На телефоне откройте: Настройки -> Подключения -> Точка доступа и модем.\n  [3] Включите 'USB-модем'.\n  [4] В окне программы на ПК появится кабельный IP-адрес."
                )[idxLang]
                else -> arrayOf(
                    "OPCIÓN 3: CABLE USB MANUAL (MODO 'TRANSFERIR ARCHIVOS')\n\n  [1] En esta app, toca el botón 'GUARDAR .JSON PARA CABLE USB'. El archivo quedará en Descargas/EXOSYS_LINK.\n  [2] Conecta el cable USB entre tu teléfono y la PC en modo 'Transferir archivos (MTP)'.\n  [3] En la PC, abre el archivo desde el Paso 1.",
                    "OPTION 3: MANUAL USB CABLE ('FILE TRANSFER' MODE)\n\n  [1] In this app, tap 'SAVE .JSON FOR USB CABLE'. The file will be saved in Downloads/EXOSYS_LINK.\n  [2] Connect the USB cable between your phone and PC in 'File Transfer (MTP)' mode.\n  [3] On the PC, open the file from Step 1.",
                    "选项 3：手动 USB 数据线（“传输文件”模式）\n\n  [1] 在本应用中点击“保存 .JSON 文件（通过 USB 传文件）”。文件将保存在 Download/EXOSYS_LINK 中。\n  [2] 用 USB 线连接手机与电脑，并选择“传输文件 (MTP)”模式。\n  [3] 在电脑端第 1 步中打开该文件。",
                    "オプション 3：手動USBケーブル（「ファイル転送」モード）\n\n  [1] このアプリで「USBファイル転送用に .JSON を保存」をタップします（Download/EXOSYS_LINKに保存されます）。\n  [2] スマホとPCをUSBケーブルで「ファイル転送 (MTP)」モードで接続します。\n  [3] PCのステップ1からファイルを開きます。",
                    "विकल्प 3: मैनुअल USB केबल ('फ़ाइल ट्रांसफर' मोड)\n\n  [1] इस ऐप में 'USB केबल के लिए .JSON फ़ाइल सेव करें' पर टैप करें। फ़ाइल Download/EXOSYS_LINK में सेव होगी।\n  [2] फ़ोन और PC के बीच USB केबल को 'File Transfer (MTP)' मोड में कनेक्ट करें।\n  [3] PC पर चरण 1 से फ़ाइल खोलें।",
                    "OPTION 3 : CÂBLE USB MANUEL (MODE 'TRANSFERT DE FICHIERS')\n\n  [1] Dans cette appli, touchez 'ENREGISTRER .JSON POUR CÂBLE USB'. Le fichier sera dans Téléchargements/EXOSYS_LINK.\n  [2] Branchez le câble USB entre le téléphone et le PC en mode 'Transfert de fichiers (MTP)'.\n  [3] Sur le PC, ouvrez le fichier depuis l'Étape 1.",
                    "الخيار 3: كابل USB يدوي (وضع 'نقل الملفات')\n\n  [1] في هذا التطبيق، اضغط على 'حفظ ملف .JSON لكابل USB'. سيُحفظ الملف في Download/EXOSYS_LINK.\n  [2] وصّل كابل USB بين الهاتف والكمبيوتر في وضع 'نقل الملفات (MTP)'.\n  [3] في الكمبيوتر، افتح الملف من الخطوة 1.",
                    "ОПЦИЯ 3: ВРУЧНУЮ ЧЕРЕЗ USB-КАБЕЛЬ (РЕЖИМ 'ПЕРЕДАЧА ФАЙЛОВ')\n\n  [1] В приложении нажмите 'СОХРАНИТЬ .JSON ДЛЯ USB-КАБЕЛЯ'. Файл сохранится в Загрузки/EXOSYS_LINK.\n  [2] Подключите телефон к ПК по USB в режиме 'Передача файлов (MTP)'.\n  [3] На ПК откройте этот файл на Шаге 1."
                )[idxLang]
            }
        }

        btnTabWifi.setOnClickListener { selectTab(0) }
        btnTabUsbNet.setOnClickListener { selectTab(1) }
        btnTabUsbMtp.setOnClickListener { selectTab(2) }
        selectTab(tabInicial)

        val ipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 4), 0, dp(context, 8))
        }

        val edtIp = EditText(context).apply {
            hint = arrayOf("IP de la PC (ej. 192.168.100.98)", "PC IP (e.g. 192.168.100.98)", "电脑 IP (例: 192.168.100.98)", "PCのIP (例: 192.168.100.98)", "PC का IP (उदा. 192.168.100.98)", "IP du PC (ex. 192.168.100.98)", "عنوان IP للكمبيوتر", "IP ПК (напр. 192.168.100.98)")[idxLang]
            setText(ExonedMapVault.getSavedPcIp(context))
            setTextColor(COLOR_CYAN)
            setHintTextColor(Color.GRAY)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val edtPin = EditText(context).apply {
            hint = "PIN"
            setText(ExonedMapVault.getSavedPcPin(context))
            setTextColor(COLOR_GREEN)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(dp(context, 75), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(context, 6)
            }
        }

        ipRow.addView(edtIp)
        ipRow.addView(edtPin)

        var dlg: AlertDialog? = null
        val btnSaveClose = Button(context).apply {
            text = arrayOf("GUARDAR IP/PIN Y CERRAR", "SAVE IP/PIN & CLOSE", "保存 IP/PIN 并关闭", "IP/PINを保存して閉じる", "IP/PIN सहेजें और बंद करें", "SAUVEGARDER IP/PIN ET FERMER", "حفظ IP/PIN وإغلاق", "СОХРАНИТЬ IP/PIN И ЗАКРЫТЬ")[idxLang]
            setBackgroundColor(COLOR_GREEN)
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                ExonedMapVault.savePcLinkCredentials(context, edtIp.text.toString(), edtPin.text.toString())
                val tUpdated = arrayOf("Enlace PC actualizado:", "PC Link updated:", "电脑连接已更新：", "PC接続を更新しました:", "PC लिंक अपडेट किया गया:", "Lien PC mis à jour :", "تم تحديث ربط الكمبيوتر:", "Связь с ПК обновлена:")[idxLang]
                Toast.makeText(context, "$tUpdated ${edtIp.text}", Toast.LENGTH_SHORT).show()
                dlg?.dismiss()
            }
        }

        box.addView(title)
        box.addView(tabsRow)
        box.addView(txtBody)
        box.addView(ipRow)
        box.addView(btnSaveClose)

        dlg = AlertDialog.Builder(context).setView(scroll).create()
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.show()
    }

    private fun mostrarDialogoEnvioDirectoPc(context: Context, recipe: ExonedForgeRecipe) {
        val mainHandler = Handler(Looper.getMainLooper())
        val idxLang = langIdx(context)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
            background = GradientDrawable().apply {
                setColor(COLOR_PANEL)
                setStroke(dp(context, 2), COLOR_CYAN)
                cornerRadius = dp(context, 10).toFloat()
            }
        }

        val title = TextView(context).apply {
            text = arrayOf("TRANSMITIR ORDEN A LA PC", "TRANSMIT JOB TO PC", "向电脑发送任务指令", "PCへジョブ指令を送信", "PC को आदेश भेजें", "TRANSMETTRE L'ORDRE AU PC", "إرسال الأمر إلى الكمبيوتر", "ОТПРАВИТЬ ЗАДАНИЕ НА ПК")[idxLang]
            setTextColor(COLOR_CYAN)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(context, 8))
        }

        val edtIp = EditText(context).apply {
            hint = arrayOf("IP mostrada en la PC (ej. 192.168.100.98)", "IP shown on PC (e.g. 192.168.100.98)", "电脑上显示的 IP (例: 192.168.100.98)", "PCに表示されたIP (例: 192.168.100.98)", "PC पर दिखाया गया IP (उदा. 192.168.100.98)", "IP affichée sur le PC (ex. 192.168.100.98)", "عنوان IP المعروض على الكمبيوتر", "IP на экране ПК (напр. 192.168.100.98)")[idxLang]
            setText(ExonedMapVault.getSavedPcIp(context))
            setTextColor(Color.WHITE)
            textSize = 13f
        }

        val edtPin = EditText(context).apply {
            hint = arrayOf("PIN de 4 dígitos (ej. 2077)", "4-digit PIN (e.g. 2077)", "4 位 PIN 码 (例: 2077)", "4桁のPIN (例: 2077)", "4-अंकीय PIN (उदा. 2077)", "PIN à 4 chiffres (ex. 2077)", "رمز PIN من 4 أرقام (2077)", "4-значный PIN (напр. 2077)")[idxLang]
            setText(ExonedMapVault.getSavedPcPin(context))
            setTextColor(COLOR_GREEN)
            textSize = 13f
        }

        val txtStatus = TextView(context).apply {
            text = arrayOf(
                "Buscando tu PC en la red local automáticamente...",
                "Automatically searching for your PC on the local network...",
                "正在局域网内自动搜索您的电脑...",
                "ローカルネットワーク上のPCを自動検索中...",
                "लोकल नेटवर्क पर आपका PC स्वतः खोजा जा रहा है...",
                "Recherche automatique de votre PC sur le réseau local...",
                "جارٍ البحث تلقائيًا عن الكمبيوتر في الشبكة المحلية...",
                "Автоматический поиск ПК в локальной сети..."
            )[idxLang]
            setTextColor(COLOR_AMBER)
            textSize = 11.5f
            setPadding(0, dp(context, 6), 0, dp(context, 10))
        }

        ExonedMapVault.autoDiscoverPcIpOnLan { discoveredIp ->
            mainHandler.post {
                if (!discoveredIp.isNullOrBlank()) {
                    edtIp.setText(discoveredIp)
                    txtStatus.setTextColor(COLOR_GREEN)
                    txtStatus.text = arrayOf(
                        "[OK] PC detectada automáticamente en $discoveredIp:8890. Pulsa enviar abajo.",
                        "[OK] PC auto-detected at $discoveredIp:8890. Tap send below.",
                        "[OK] 已在 $discoveredIp:8890 自动检测到电脑。请点击下方发送。",
                        "[OK] $discoveredIp:8890 でPCを自動検出しました。下の送信を押してください。",
                        "[OK] $discoveredIp:8890 पर PC स्वतः मिल गया। नीचे भेजें पर टैप करें।",
                        "[OK] PC détecté automatiquement sur $discoveredIp:8890. Appuyez sur envoyer ci-dessous.",
                        "[OK] تم اكتشاف الكمبيوتر تلقائيًا في $discoveredIp:8890. اضغط إرسال بالأسفل.",
                        "[ОК] ПК автоматически обнаружен на $discoveredIp:8890. Нажмите отправить ниже."
                    )[idxLang]
                } else {
                    txtStatus.setTextColor(Color.LTGRAY)
                    txtStatus.text = arrayOf(
                        "Verifica que la IP coincida con la mostrada en el Paso 1 de tu PC.",
                        "Verify that the IP matches the one shown in Step 1 on your PC.",
                        "请确认 IP 与电脑端第 1 步中显示的一致。",
                        "IPがPCのステップ1に表示されているものと一致するか確認してください。",
                        "पुष्टि करें कि IP आपके PC के चरण 1 में दिखाए गए IP से मेल खाता है।",
                        "Vérifiez que l'IP correspond à celle affichée à l'Étape 1 de votre PC.",
                        "تأكد من تطابق عنوان IP مع المعروض في الخطوة 1 على الكمبيوتر.",
                        "Убедитесь, что IP совпадает с указанным на Шаге 1 на вашем ПК."
                    )[idxLang]
                }
            }
        }

        val btnSend = Button(context).apply {
            text = arrayOf("ENVIAR INSTRUCCIONES AHORA", "SEND INSTRUCTIONS NOW", "立即发送指令", "今すぐ指令を送信", "अभी निर्देश भेजें", "ENVOYER LES INSTRUCTIONS", "إرسال التعليمات الآن", "ОТПРАВИТЬ ИНСТРУКЦИИ СЕЙЧАС")[idxLang]
            setBackgroundColor(COLOR_CYAN)
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
        }

        btnSend.setOnClickListener {
            val ip = edtIp.text.toString()
            val pin = edtPin.text.toString()
            ExonedMapVault.savePcLinkCredentials(context, ip, pin)
            btnSend.isEnabled = false
            btnSend.text = arrayOf("CONECTANDO CON $ip:8890...", "CONNECTING TO $ip:8890...", "正在连接 $ip:8890...", "$ip:8890 に接続中...", "$ip:8890 से जुड़ रहा है...", "CONNEXION À $ip:8890...", "جارٍ الاتصال بـ $ip:8890...", "ПОДКЛЮЧЕНИЕ К $ip:8890...")[idxLang]

            ExonedMapVault.sendRecipeToPcOverNetwork(ip, pin, recipe) { res ->
                mainHandler.post {
                    btnSend.isEnabled = true
                    btnSend.text = arrayOf("REINTENTAR ENVÍO", "RETRY SEND", "重试发送", "再送信", "पुनः भेजें", "RÉESSAYER L'ENVOI", "إعادة المحاولة", "ПОВТОРИТЬ ОТПРАВКУ")[idxLang]
                    res.onSuccess {
                        txtStatus.setTextColor(COLOR_GREEN)
                        txtStatus.text = arrayOf(
                            "[OK] ORDEN RECIBIDA EN LA PC. La ventana de tu computadora ya avanzó al Paso 2.",
                            "[OK] JOB RECEIVED ON PC. Your computer window has advanced to Step 2.",
                            "[OK] 电脑已收到指令。电脑端窗口已自动进入第 2 步。",
                            "[OK] PCが指令を受信しました。PC画面がステップ2に進みました。",
                            "[OK] PC पर आदेश प्राप्त हुआ। आपके कंप्यूटर की विंडो चरण 2 पर आगे बढ़ गई है।",
                            "[OK] ORDRE REÇU SUR LE PC. La fenêtre de votre ordinateur est passée à l'Étape 2.",
                            "[OK] تم استلام الأمر على الكمبيوتر. انتقلت نافذة الكمبيوتر إلى الخطوة 2.",
                            "[ОК] ЗАДАНИЕ ПОЛУЧЕНО НА ПК. Окно на компьютере перешло к Шагу 2."
                        )[idxLang]
                    }.onFailure { err ->
                        txtStatus.setTextColor(COLOR_CRITICO)
                        txtStatus.text = "[ERROR] ${err.localizedMessage}"
                    }
                }
            }
        }

        box.addView(title)
        box.addView(edtIp)
        box.addView(edtPin)
        box.addView(txtStatus)
        box.addView(btnSend)

        val dlg = AlertDialog.Builder(context).setView(box).create()
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.show()
    }

    fun mostrarMonitorForjaEnVivoDialog(context: Context) {
        val mainHandler = Handler(Looper.getMainLooper())
        val idxLang = langIdx(context)
        val alive = AtomicBoolean(true)

        val scroll = ScrollView(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14))
            background = GradientDrawable().apply {
                setColor(COLOR_PANEL)
                setStroke(dp(context, 2), COLOR_GREEN)
                cornerRadius = dp(context, 10).toFloat()
            }
        }
        scroll.addView(root)

        val title = TextView(context).apply {
            text = arrayOf(
                "MONITOR EN VIVO E INSPECTOR .QOI",
                "LIVE MONITOR & .QOI INSPECTOR",
                "实时监控与 .QOI 检查器",
                "ライブモニター＆.QOIインスペクター",
                "लाइव मॉनिटर और .QOI इंस्पेक्टर",
                "MONITEUR EN DIRECT ET INSPECTEUR .QOI",
                "المراقب المباشر وفاحص .QOI",
                "МОНИТОР В РЕАЛЬНОМ ВРЕМЕНИ И ИНСПЕКТОР .QOI"
            )[idxLang]
            setTextColor(COLOR_GREEN)
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val txtHeadline = TextView(context).apply {
            text = ExonedForgeEngine.statusHeadline
            setTextColor(COLOR_CYAN)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(context, 6), 0, dp(context, 2))
        }

        val txtDetails = TextView(context).apply {
            text = ExonedForgeEngine.detailMessage
            setTextColor(Color.LTGRAY)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(context, 6))
        }

        val pb = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 12))
        }

        val tSectorsLbl = arrayOf("Sectores:", "Sectors:", "扇区：", "セクター:", "सेक्टर:", "Secteurs :", "القطاعات:", "Секторы:")[idxLang]
        val tWrittenLbl = arrayOf("Grabado:", "Written:", "已写入：", "書込済:", "लिखा गया:", "Écrit :", "تم حفظ:", "Записано:")[idxLang]
        val tNetPausesLbl = arrayOf("Pausas red:", "Net pauses:", "网络暂停：", "通信一時停止:", "नेटवर्क विराम:", "Pauses réseau :", "توقفات الشبكة:", "Паузы сети:")[idxLang]

        val txtCounters = TextView(context).apply {
            text = "$tSectorsLbl 0 / 0"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setPadding(0, dp(context, 6), 0, dp(context, 8))
        }

        val lblLive = TextView(context).apply {
            text = arrayOf(
                "ÚLTIMOS SECTORES DECODIFICADOS DESDE EL ARCHIVO .QOI:",
                "LATEST SECTORS DECODED FROM .QOI FILE:",
                "从 .QOI 文件解码的最新扇区：",
                ".QOIファイルからデコードされた最新セクター:",
                ".QOI फ़ाइल से डिकोड किए गए नवीनतम सेक्टर:",
                "DERNIERS SECTEURS DÉCODÉS DEPUIS LE FICHIER .QOI :",
                "أحدث القطاعات المفكوكة من ملف .QOI:",
                "ПОСЛЕДНИЕ СЕКТОРЫ, ДЕКОДИРОВАННЫЕ ИЗ .QOI:"
            )[idxLang]
            setTextColor(COLOR_AMBER)
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
        }

        val liveHScroll = HorizontalScrollView(context).apply {
            setPadding(0, dp(context, 6), 0, dp(context, 8))
        }
        val liveStrip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        liveHScroll.addView(liveStrip)

        val lblWitness = TextView(context).apply {
            text = arrayOf(
                "CINTA TESTIGO DE LA SESIÓN (1 MUESTRA CADA 200 SECTORES):",
                "SESSION WITNESS STRIP (1 SAMPLE EVERY 200 SECTORS):",
                "会话抽样带（每 200 个扇区保留 1 个样本）：",
                "セッション証跡ストリップ（200セクターごとに1サンプル）:",
                "सत्र साक्षी पट्टी (प्रत्येक 200 सेक्टर पर 1 नमूना):",
                "BANDE TÉMOIN DE SESSION (1 ÉCHANTILLON TOUS LES 200 SECTEURS) :",
                "شريط عينات الجلسة (عينة واحدة كل 200 قطاع):",
                "КОНТРОЛЬНАЯ ЛЕНТА СЕССИИ (1 ОБРАЗЕЦ КАЖДЫЕ 200 СЕКТОРОВ):"
            )[idxLang]
            setTextColor(COLOR_CYAN)
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
        }

        val witnessHScroll = HorizontalScrollView(context).apply {
            setPadding(0, dp(context, 6), 0, dp(context, 10))
        }
        val witnessStrip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        witnessHScroll.addView(witnessStrip)

        val controlsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 4), 0, dp(context, 6))
        }

        val btnPauseResume = Button(context).apply {
            text = ExonedForgeI18n.tr(context, "btn_pause")
            setBackgroundColor(COLOR_AMBER)
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 38), 1f).apply { rightMargin = dp(context, 4) }
            setOnClickListener {
                if (ExonedForgeEngine.currentState == ExonedForgeEngine.ForgeState.RUNNING) {
                    ExonedForgeEngine.pauseForge()
                } else {
                    ExonedForgeEngine.resumeForgeFromPause()
                }
            }
        }

        val btnStopSave = Button(context).apply {
            text = ExonedForgeI18n.tr(context, "btn_save_exit")
            setBackgroundColor(COLOR_CRITICO)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 38), 1f).apply { leftMargin = dp(context, 4) }
            setOnClickListener {
                ExonedForgeEngine.stopAndSaveProgress()
            }
        }

        controlsRow.addView(btnPauseResume)
        controlsRow.addView(btnStopSave)

        var dlg: AlertDialog? = null
        val btnHideBg = Button(context).apply {
            text = ExonedForgeI18n.tr(context, "btn_minimize_bg")
            setBackgroundColor(COLOR_DARK)
            setTextColor(COLOR_CYAN)
            textSize = 11f
            setOnClickListener {
                alive.set(false)
                dlg?.dismiss()
            }
        }

        root.addView(title)
        root.addView(txtHeadline)
        root.addView(txtDetails)
        root.addView(pb)
        root.addView(txtCounters)
        root.addView(lblLive)
        root.addView(liveHScroll)
        root.addView(lblWitness)
        root.addView(witnessHScroll)
        root.addView(controlsRow)
        root.addView(btnHideBg)

        dlg = AlertDialog.Builder(context).setView(scroll).setOnDismissListener {
            alive.set(false)
        }.create()
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.show()

        thread(start = true, isDaemon = true) {
            var lastRenderedTimestamp = 0L
            while (alive.get()) {
                val done = ExonedForgeEngine.completedSectors.get()
                val tot = ExonedForgeEngine.totalSectors.get().coerceAtLeast(1L)
                val pct = ((done * 100L) / tot).toInt().coerceIn(0, 100)
                val mbWritten = ExonedForgeEngine.writtenBytesDisk.get().toDouble() / (1024.0 * 1024.0)
                val netPauses = ExonedForgeEngine.networkPauseCount.get()
                val recentList = ExonedForgeEngine.liveRecentSamples.toList()
                val witnessList = ExonedForgeEngine.witnessStripSamples.toList()
                val newestTs = recentList.lastOrNull()?.timestamp ?: 0L

                mainHandler.post {
                    if (!alive.get()) return@post
                    txtHeadline.text = ExonedForgeEngine.statusHeadline
                    txtDetails.text = ExonedForgeEngine.detailMessage
                    pb.progress = pct
                    txtCounters.text = "$tSectorsLbl ${String.format(Locale.US, "%,d", done)} / ${String.format(Locale.US, "%,d", tot)}  |  " +
                            "$tWrittenLbl ${String.format(Locale.US, "%.2f", mbWritten)} MB  |  $tNetPausesLbl $netPauses"

                    btnPauseResume.text = if (ExonedForgeEngine.currentState == ExonedForgeEngine.ForgeState.RUNNING) {
                        ExonedForgeI18n.tr(context, "btn_pause")
                    } else {
                        ExonedForgeI18n.tr(context, "btn_resume")
                    }

                    if (newestTs != lastRenderedTimestamp) {
                        lastRenderedTimestamp = newestTs
                        liveStrip.removeAllViews()
                        for (smp in recentList.asReversed()) {
                            liveStrip.addView(buildSampleCard(context, smp))
                        }
                        witnessStrip.removeAllViews()
                        for (ws in witnessList.asReversed()) {
                            witnessStrip.addView(buildSampleCard(context, ws))
                        }
                    }
                }
                try {
                    Thread.sleep(600)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun buildSampleCard(context: Context, smp: DecodedTileSample): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 4), dp(context, 2), dp(context, 4), dp(context, 2))
            val img = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(context, 68), dp(context, 68))
                setImageBitmap(smp.bitmap)
                scaleType = ImageView.ScaleType.FIT_XY
            }
            val txt = TextView(context).apply {
                text = "Z${smp.z}/${smp.x}/${smp.y}\n${smp.sourceTag} (${String.format(Locale.US, "%.1f", smp.qoiSizeKb)}K)"
                setTextColor(if (smp.sourceTag == "GM") COLOR_GREEN else COLOR_AMBER)
                textSize = 8.8f
                gravity = Gravity.CENTER
            }
            addView(img)
            addView(txt)
        }
    }

    fun mostrarBovedaSincronizadaDialog(context: Context) {
        val mainHandler = Handler(Looper.getMainLooper())
        val idxLang = langIdx(context)
        val scroll = ScrollView(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14))
            background = GradientDrawable().apply {
                setColor(COLOR_PANEL)
                setStroke(dp(context, 2), COLOR_CYAN)
                cornerRadius = dp(context, 10).toFloat()
            }
        }
        scroll.addView(root)

        val title = TextView(context).apply {
            text = ExonedForgeI18n.tr(context, "vault_title")
            setTextColor(COLOR_CYAN)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val txtFree = TextView(context).apply {
            setTextColor(COLOR_GREEN)
            textSize = 11.5f
            gravity = Gravity.CENTER
            setPadding(0, dp(context, 4), 0, dp(context, 8))
        }

        if (ExonedForgeEngine.currentState != ExonedForgeEngine.ForgeState.IDLE) {
            val tViewActive = arrayOf(
                "VER DESCARGA ACTIVA",
                "VIEW ACTIVE DOWNLOAD",
                "查看当前下载任务",
                "進行中のダウンロードを表示",
                "सक्रिय डाउनलोड देखें",
                "VOIR TÉLÉCHARGEMENT ACTIF",
                "عرض التنزيل النشط",
                "АКТИВНАЯ ЗАГРУЗКА"
            )[idxLang]
            val btnActiveMonitor = Button(context).apply {
                text = "$tViewActive (${ExonedForgeEngine.activeRecipe?.jobName ?: ""})"
                setBackgroundColor(COLOR_GREEN)
                setTextColor(Color.BLACK)
                typeface = Typeface.DEFAULT_BOLD
                textSize = 11f
                setOnClickListener { mostrarMonitorForjaEnVivoDialog(context) }
            }
            root.addView(btnActiveMonitor)
        }

        val itemsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun renderVaultLists() {
            val freeMb = obtenerEspacioLibreTelefonoMb()
            val tFreePhone = arrayOf("Espacio libre en teléfono:", "Free space on phone:", "手机可用空间：", "スマホ空き容量:", "फ़ोन में खाली जगह:", "Espace libre téléphone :", "المساحة الحرة في الهاتف:", "Свободно на телефоне:")[idxLang]
            txtFree.text = "$tFreePhone ${String.format(Locale.US, "%,.0f", freeMb)} MB  |  IP PC: ${ExonedMapVault.getSavedPcIp(context)}"
            itemsContainer.removeAllViews()

            val localMaps = ExonedMapVault.listLocalPhoneMaps(context)
            val tMapsInPhone = arrayOf("MAPAS EN ESTE TELÉFONO", "MAPS ON THIS PHONE", "本手机上的地图", "このスマホ内のマップ", "इस फ़ोन में नक्शे", "CARTES SUR CE TÉLÉPHONE", "الخرائط في هذا الهاتف", "КАРТЫ НА ЭТОМ ТЕЛЕФОНЕ")[idxLang]
            val hdrPhone = TextView(context).apply {
                text = "$tMapsInPhone (${localMaps.size}):"
                setTextColor(COLOR_CYAN)
                typeface = Typeface.DEFAULT_BOLD
                textSize = 12f
                setPadding(0, dp(context, 6), 0, dp(context, 4))
            }
            itemsContainer.addView(hdrPhone)

            if (localMaps.isEmpty()) {
                val empty = TextView(context).apply {
                    text = arrayOf(
                        "No tienes mapas guardados en el almacenamiento del teléfono.",
                        "You have no maps saved in phone storage.",
                        "手机存储中暂无已保存的地图。",
                        "スマホのストレージに保存されたマップはありません。",
                        "फ़ोन स्टोरेज में कोई नक्शा सहेजा नहीं गया है।",
                        "Aucune carte enregistrée dans la mémoire du téléphone.",
                        "لا توجد خرائط محفوظة في ذاكرة الهاتف.",
                        "В памяти телефона нет сохранённых карт."
                    )[idxLang]
                    setTextColor(Color.LTGRAY)
                    textSize = 11f
                    setPadding(0, 0, 0, dp(context, 8))
                }
                itemsContainer.addView(empty)
            } else {
                for (entry in localMaps) {
                    itemsContainer.addView(buildVaultCard(context, entry, isRemotePc = false) { renderVaultLists() })
                }
            }

            val hdrPc = TextView(context).apply {
                text = arrayOf(
                    "MAPAS EN COMPUTADORA / MICRO-SD (SINCRONIZANDO...):",
                    "MAPS ON COMPUTER / MICRO-SD (SYNCING...):",
                    "电脑 / MICRO-SD 上的地图（正在同步...）：",
                    "PC / MICRO-SD 内のマップ（同期中...）:",
                    "कंप्यूटर / MICRO-SD पर नक्शे (सिंक हो रहा है...):",
                    "CARTES SUR PC / MICRO-SD (SYNCHRONISATION...) :",
                    "الخرائط في الكمبيوتر / MICRO-SD (جارٍ المزامنة...):",
                    "КАРТЫ НА ПК / MICRO-SD (СИНХРОНИЗАЦИЯ...):"
                )[idxLang]
                setTextColor(COLOR_AMBER)
                typeface = Typeface.DEFAULT_BOLD
                textSize = 12f
                setPadding(0, dp(context, 10), 0, dp(context, 4))
            }
            itemsContainer.addView(hdrPc)

            val ip = ExonedMapVault.getSavedPcIp(context)
            val pin = ExonedMapVault.getSavedPcPin(context)
            ExonedMapVault.fetchRemotePcVault(ip, pin) { res ->
                mainHandler.post {
                    res.onSuccess { pcMaps ->
                        hdrPc.text = arrayOf(
                            "MAPAS EN COMPUTADORA / MICRO-SD (${pcMaps.size} ENLAZADOS):",
                            "MAPS ON COMPUTER / MICRO-SD (${pcMaps.size} LINKED):",
                            "电脑 / MICRO-SD 上的地图（已连接 ${pcMaps.size} 个）：",
                            "PC / MICRO-SD 内のマップ（${pcMaps.size} 件リンク済）:",
                            "कंप्यूटर / MICRO-SD पर नक्शे (${pcMaps.size} जुड़े हुए):",
                            "CARTES SUR PC / MICRO-SD (${pcMaps.size} LIÉES) :",
                            "الخرائط في الكمبيوتر / MICRO-SD (${pcMaps.size} مرتبطة):",
                            "КАРТЫ НА ПК / MICRO-SD (СВЯЗАНО: ${pcMaps.size}):"
                        )[idxLang]
                        hdrPc.setTextColor(COLOR_GREEN)
                        for (entry in pcMaps) {
                            itemsContainer.addView(buildVaultCard(context, entry, isRemotePc = true) { renderVaultLists() })
                        }
                    }.onFailure {
                        hdrPc.text = arrayOf(
                            "PC NO CONECTADA ($ip:8890)",
                            "PC NOT CONNECTED ($ip:8890)",
                            "电脑未连接 ($ip:8890)",
                            "PC未接続 ($ip:8890)",
                            "PC कनेक्ट नहीं है ($ip:8890)",
                            "PC NON CONNECTÉ ($ip:8890)",
                            "الكمبيوتر غير متصل ($ip:8890)",
                            "ПК НЕ ПОДКЛЮЧЁН ($ip:8890)"
                        )[idxLang]
                        hdrPc.setTextColor(Color.GRAY)
                    }
                }
            }
        }

        renderVaultLists()

        var dlg: AlertDialog? = null
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 10), 0, 0)
        }

        val btnLinkCfg = Button(context).apply {
            text = arrayOf("GUÍA Y CONFIGURAR PC", "PC GUIDE & SETUP", "电脑连接指南与设置", "PCガイド＆設定", "PC गाइड और सेटअप", "GUIDE ET CONFIG PC", "دليل وإعداد الكمبيوتر", "ГИД И НАСТРОЙКА ПК")[idxLang]
            setBackgroundColor(COLOR_DARK)
            setTextColor(COLOR_AMBER)
            textSize = 10.5f
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 38), 1f).apply { rightMargin = dp(context, 4) }
            setOnClickListener {
                dlg?.dismiss()
                mostrarGuiaEnlacePcDialog(context, 0)
            }
        }

        val btnClose = Button(context).apply {
            text = ExonedForgeI18n.tr(context, "btn_close_vault")
            setBackgroundColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            textSize = 10.5f
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 38), 1f).apply { leftMargin = dp(context, 4) }
            setOnClickListener { dlg?.dismiss() }
        }

        bottomRow.addView(btnLinkCfg)
        bottomRow.addView(btnClose)

        root.addView(title)
        root.addView(txtFree)
        root.addView(itemsContainer)
        root.addView(bottomRow)

        dlg = AlertDialog.Builder(context).setView(scroll).create()
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.show()
    }

    private fun buildVaultCard(
        context: Context,
        entry: VaultMapEntry,
        isRemotePc: Boolean,
        onRefresh: () -> Unit
    ): View {
        val mainHandler = Handler(Looper.getMainLooper())
        val idxLang = langIdx(context)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
            background = GradientDrawable().apply {
                setColor(COLOR_CARD)
                setStroke(dp(context, 1), if (isRemotePc) COLOR_GREEN else COLOR_CYAN)
                cornerRadius = dp(context, 6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 6) }
        }

        val badge = when (entry.locationSource) {
            "PC_SD" -> arrayOf("[TARJETA MICRO-SD EN PC]", "[MICRO-SD CARD ON PC]", "[电脑端 MICRO-SD 卡]", "[PC上のMICRO-SDカード]", "[PC में MICRO-SD कार्ड]", "[CARTE MICRO-SD SUR PC]", "[بطاقة MICRO-SD في الكمبيوتر]", "[КАРТА MICRO-SD НА ПК]")[idxLang]
            "PC_DISK" -> arrayOf("[DISCO DURO PC]", "[PC HARD DRIVE]", "[电脑硬盘]", "[PCハードドライブ]", "[PC हार्ड ड्राइव]", "[DISQUE DUR PC]", "[قرص الكمبيوتر]", "[ДИСК ПК]")[idxLang]
            else -> arrayOf("[MEMORIA TELÉFONO]", "[PHONE STORAGE]", "[手机存储]", "[スマホ内部ストレージ]", "[फ़ोन मेमोरी]", "[MÉMOIRE TÉLÉPHONE]", "[ذاكرة الهاتف]", "[ПАМЯТЬ ТЕЛЕФОНА]")[idxLang]
        }

        val title = TextView(context).apply {
            text = "$badge ${entry.jobName} (${entry.status})"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12f
        }

        val tWeightLbl = arrayOf("Peso:", "Size:", "大小：", "サイズ:", "आकार:", "Poids :", "الحجم:", "Вес:")[idxLang]
        val details = TextView(context).apply {
            text = "${entry.styleName} | ${entry.zoomsSummary} | $tWeightLbl ${String.format(Locale.US, "%,.2f", entry.sizeMb)} MB"
            setTextColor(Color.LTGRAY)
            textSize = 11f
            setPadding(0, dp(context, 2), 0, dp(context, 6))
        }

        val btnsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        if (entry.hasHighResZooms) {
            val btnPrune = Button(context).apply {
                text = ExonedForgeI18n.tr(context, "btn_prune_z15")
                setBackgroundColor(COLOR_DARK)
                setTextColor(COLOR_AMBER)
                textSize = 9.5f
                layoutParams = LinearLayout.LayoutParams(0, dp(context, 32), 1f).apply { rightMargin = dp(context, 4) }
                setOnClickListener {
                    if (!isRemotePc) {
                        val freed = ExonedMapVault.pruneHighResZoomsOnly(context, entry.jobId)
                        val tFreedMsg = arrayOf(
                            "Liberados ${String.format(Locale.US, "%.1f", freed)} MB (Z6-Z14 conservado).",
                            "Freed ${String.format(Locale.US, "%.1f", freed)} MB (Z6-Z14 kept).",
                            "已释放 ${String.format(Locale.US, "%.1f", freed)} MB（保留 Z6-Z14）。",
                            "${String.format(Locale.US, "%.1f", freed)} MB 解放しました（Z6-Z14保持）。",
                            "${String.format(Locale.US, "%.1f", freed)} MB खाली किया गया (Z6-Z14 सुरक्षित)।",
                            "${String.format(Locale.US, "%.1f", freed)} MB libérés (Z6-Z14 conservés).",
                            "تم توفير ${String.format(Locale.US, "%.1f", freed)} MB (تم الاحتفاظ بـ Z6-Z14).",
                            "Освобождено ${String.format(Locale.US, "%.1f", freed)} MB (Z6-Z14 сохранены)."
                        )[idxLang]
                        Toast.makeText(context, tFreedMsg, Toast.LENGTH_LONG).show()
                        onRefresh()
                    } else {
                        val ip = ExonedMapVault.getSavedPcIp(context)
                        val pin = ExonedMapVault.getSavedPcPin(context)
                        ExonedMapVault.requestRemotePcDelete(ip, pin, entry.jobId, pruneHighResOnly = true) {
                            mainHandler.post { onRefresh() }
                        }
                    }
                }
            }
            btnsRow.addView(btnPrune)
        }

        val btnDeleteAll = Button(context).apply {
            text = ExonedForgeI18n.tr(context, "btn_del_all")
            setBackgroundColor(COLOR_CRITICO)
            setTextColor(Color.WHITE)
            textSize = 9.5f
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 32), 1f)
            setOnClickListener {
                if (!isRemotePc) {
                    ExonedMapVault.deleteEntireMap(context, entry.jobId)
                    val tDeletedMsg = arrayOf(
                        "Mapa eliminado del teléfono.",
                        "Map deleted from phone.",
                        "地图已从手机删除。",
                        "スマホからマップを削除しました。",
                        "फ़ोन से नक्शा हटा दिया गया।",
                        "Carte supprimée du téléphone.",
                        "تم حذف الخريطة من الهاتف.",
                        "Карта удалена с телефона."
                    )[idxLang]
                    Toast.makeText(context, tDeletedMsg, Toast.LENGTH_SHORT).show()
                    onRefresh()
                } else {
                    val ip = ExonedMapVault.getSavedPcIp(context)
                    val pin = ExonedMapVault.getSavedPcPin(context)
                    ExonedMapVault.requestRemotePcDelete(ip, pin, entry.jobId, pruneHighResOnly = false) {
                        mainHandler.post { onRefresh() }
                    }
                }
            }
        }

        btnsRow.addView(btnDeleteAll)
        card.addView(title)
        card.addView(details)
        card.addView(btnsRow)
        return card
    }
}