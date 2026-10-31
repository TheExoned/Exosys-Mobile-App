package com.example.exosys

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.JsonReader
import android.util.JsonToken
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

data class GeoPoint(val lat: Double, val lon: Double)

data class MatrixTelemetry(
    val totalTiles: Long,
    val unmaskedTotalTiles: Long,
    val mapWeightMb: Double,
    val unmaskedMapWeightMb: Double,
    val routeWeightMb: Double,
    val totalWeightMb: Double,
    val areaKm2: Double,
    val clustersCount: Long,
    val urbanFilterActive: Boolean
)

data class UrbanScanResult(
    val z16Cells: Set<Long>,
    val citiesCount: Int,
    val townsCount: Int,
    val communitiesCount: Int,
    val polygonsWithHolesJson: String,
    val stage2Completed: Boolean = true
)

data class StateBoundaryInfo(
    val relationId: Long,
    val name: String,
    val rings: List<List<GeoPoint>>,
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double
)

data class SavedAreaPreset(
    val id: String,
    val name: String,
    val timestamp: Long,
    val mode: String, // "BOX", "POLY", "STATE"
    val statesList: List<StateBoundaryInfo>,
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
    val rings: List<List<GeoPoint>>,
    val urbanCellsZ16: Set<Long>,
    val isolateUrbanHighZoom: Boolean,
    val routeProfileIdx: Int
)

// =============================================================================
// ALMACENAMIENTO PERSISTENTE DE ÁREAS, MULTI-ESTADOS Y PUEBLOS SELECCIONADOS
// =============================================================================
object AreaPresetStorage {
    private const val FILE_NAME = "exosys_saved_areas.json"

    private fun getFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun savePreset(context: Context, preset: SavedAreaPreset) {
        val current = listPresets(context)
            .filterNot { it.id == preset.id || it.name.equals(preset.name, ignoreCase = true) }
            .toMutableList()
        current.add(0, preset)
        writeAll(context, current)
    }

    fun deletePreset(context: Context, presetId: String) {
        val current = listPresets(context).filterNot { it.id == presetId }
        writeAll(context, current)
    }

    fun listPresets(context: Context): List<SavedAreaPreset> {
        val f = getFile(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val out = ArrayList<SavedAreaPreset>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)

                val ringsJson = o.optJSONArray("rings") ?: JSONArray()
                val rings = parseRingsArray(ringsJson)

                val statesJson = o.optJSONArray("states_list") ?: JSONArray()
                val parsedStates = ArrayList<StateBoundaryInfo>()
                for (sIdx in 0 until statesJson.length()) {
                    val so = statesJson.optJSONObject(sIdx) ?: continue
                    val sRings = parseRingsArray(so.optJSONArray("rings") ?: JSONArray())
                    if (sRings.isNotEmpty()) {
                        parsedStates.add(
                            StateBoundaryInfo(
                                relationId = so.optLong("rel_id", 0L),
                                name = so.optString("name", "Estado"),
                                rings = sRings,
                                north = so.optDouble("north", 0.0),
                                south = so.optDouble("south", 0.0),
                                east = so.optDouble("east", 0.0),
                                west = so.optDouble("west", 0.0)
                            )
                        )
                    }
                }

                if (parsedStates.isEmpty() && o.optString("mode") == "STATE" && rings.isNotEmpty()) {
                    parsedStates.add(
                        StateBoundaryInfo(
                            relationId = o.optLong("state_rel_id", 0L),
                            name = o.optString("state_name", o.optString("name", "Estado")),
                            rings = rings,
                            north = o.getDouble("north"),
                            south = o.getDouble("south"),
                            east = o.getDouble("east"),
                            west = o.getDouble("west")
                        )
                    )
                }

                val cellsJson = o.optJSONArray("urban_z16_cells") ?: JSONArray()
                val cells = HashSet<Long>(cellsJson.length())
                for (cIdx in 0 until cellsJson.length()) {
                    val pair = cellsJson.optJSONArray(cIdx) ?: continue
                    cells.add(UrbanScannerEngine.packXY(pair.getInt(0), pair.getInt(1)))
                }

                out.add(
                    SavedAreaPreset(
                        id = o.optString("id", "AREA_$i"),
                        name = o.optString("name", "Zona Guardada"),
                        timestamp = o.optLong("timestamp", System.currentTimeMillis()),
                        mode = o.optString("mode", "BOX"),
                        statesList = parsedStates,
                        north = o.getDouble("north"),
                        south = o.getDouble("south"),
                        east = o.getDouble("east"),
                        west = o.getDouble("west"),
                        rings = rings,
                        urbanCellsZ16 = cells,
                        isolateUrbanHighZoom = o.optBoolean("isolate_urban", true),
                        routeProfileIdx = o.optInt("route_profile_idx", 1)
                    )
                )
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun parseRingsArray(ringsJson: JSONArray): List<List<GeoPoint>> {
        val rings = ArrayList<List<GeoPoint>>()
        for (rIdx in 0 until ringsJson.length()) {
            val rArr = ringsJson.optJSONArray(rIdx) ?: continue
            val pts = ArrayList<GeoPoint>(rArr.length())
            for (pIdx in 0 until rArr.length()) {
                val pt = rArr.optJSONArray(pIdx) ?: continue
                pts.add(GeoPoint(pt.getDouble(0), pt.getDouble(1)))
            }
            if (pts.isNotEmpty()) rings.add(pts)
        }
        return rings
    }

    private fun writeAll(context: Context, list: List<SavedAreaPreset>) {
        try {
            val arr = JSONArray()
            for (p in list) {
                val o = JSONObject()
                o.put("id", p.id)
                o.put("name", p.name)
                o.put("timestamp", p.timestamp)
                o.put("mode", p.mode)
                o.put("north", p.north)
                o.put("south", p.south)
                o.put("east", p.east)
                o.put("west", p.west)
                o.put("isolate_urban", p.isolateUrbanHighZoom)
                o.put("route_profile_idx", p.routeProfileIdx)

                val ringsArr = JSONArray()
                for (r in p.rings) {
                    ringsArr.put(GeoBoundaryEngine.pointsToJsonArray(r, maxPoints = 2500))
                }
                o.put("rings", ringsArr)

                val statesArr = JSONArray()
                for (st in p.statesList) {
                    val so = JSONObject()
                    so.put("rel_id", st.relationId)
                    so.put("name", st.name)
                    so.put("north", st.north)
                    so.put("south", st.south)
                    so.put("east", st.east)
                    so.put("west", st.west)
                    val stRingsArr = JSONArray()
                    for (r in st.rings) {
                        stRingsArr.put(GeoBoundaryEngine.pointsToJsonArray(r, maxPoints = 2500))
                    }
                    so.put("rings", stRingsArr)
                    statesArr.put(so)
                }
                o.put("states_list", statesArr)

                val cellsArr = JSONArray()
                for (code in p.urbanCellsZ16) {
                    val pair = JSONArray()
                    pair.put(UrbanScannerEngine.unpackX(code))
                    pair.put(UrbanScannerEngine.unpackY(code))
                    cellsArr.put(pair)
                }
                o.put("urban_z16_cells", cellsArr)
                arr.put(o)
            }
            getFile(context).writeText(arr.toString(), Charsets.UTF_8)
        } catch (_: Throwable) {}
    }
}

// =============================================================================
// DIAGNÓSTICO DE RED EN TIEMPO REAL
// =============================================================================
class CountingInputStream(
    inStream: InputStream,
    private val counter: AtomicLong
) : FilterInputStream(inStream) {
    override fun read(): Int {
        val b = super.read()
        if (b != -1) counter.incrementAndGet()
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) counter.addAndGet(n.toLong())
        return n
    }
}

object NetworkHealthMonitor {
    fun checkInternetPingMs(): Long {
        var conn: HttpURLConnection? = null
        return try {
            val t0 = System.currentTimeMillis()
            conn = (URL("https://cp.cloudflare.com/generate_204").openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 3000
                readTimeout = 3000
                useCaches = false
            }
            val code = conn.responseCode
            val dt = System.currentTimeMillis() - t0
            if (code in 200..399) dt else -1L
        } catch (_: Throwable) {
            -1L
        } finally {
            conn?.disconnect()
        }
    }

    fun formatNetTag(pingMs: Long): String = when {
        pingMs < 0L -> "[SIN SEÑAL / RED CAÍDA]"
        pingMs <= 220L -> "[Red OK: ${pingMs}ms]"
        pingMs <= 650L -> "[Red Lenta: ${pingMs}ms]"
        else -> "[Internet Inestable: ${pingMs}ms]"
    }
}

// =============================================================================
// DICCIONARIO MULTILENGUAJE DEL ENRUTADOR (ES, EN, ZH, JA, HI, FR, AR, RU)
// =============================================================================
object ExonedRouterI18n {
    private val LANG_INDEX = mapOf(
        "es" to 0, "en" to 1, "zh" to 2, "ja" to 3,
        "hi" to 4, "fr" to 5, "ar" to 6, "ru" to 7
    )

    private val DICT: Map<String, Array<String>> = mapOf(
        "btn_back" to arrayOf("< VOLVER", "< BACK", "< 返回", "< 戻る", "< वापस", "< RETOUR", "< رجوع", "< НАЗАД"),
        "hdr_title" to arrayOf("MAPAS Y RUTAS", "MAPS & ROUTES", "地图与路线", "マップ＆ルート", "नक्शे और मार्ग", "CARTES ET ROUTES", "الخرائط والمسارات", "КАРТЫ И МАРШРУТЫ"),
        "btn_pc_link" to arrayOf("ENLACE PC", "PC LINK", "连接电脑", "PC接続", "PC लिंक", "LIEN PC", "ربط الكمبيوتر", "СВЯЗЬ С ПК"),
        "btn_vault" to arrayOf("BÓVEDA", "VAULT", "地图库", "保管庫", "वॉल्ट", "VOÛTE", "الخزنة", "ХРАНИЛИЩЕ"),
        "mode_bbox" to arrayOf("BOUNDING BOX", "BOUNDING BOX", "矩形/多边形", "範囲ボックス", "बाउंडिंग बॉक्स", "ZONE BBOX", "منطقة محددة", "РАМКА / ПОЛИГОН"),
        "mode_state" to arrayOf("PAÍS / MULTI-ESTADO", "COUNTRY / MULTI-STATE", "国家 / 多州省", "国・複数州選択", "देश / बहु-राज्य", "PAYS / MULTI-ÉTATS", "دولة / عدة ولايات", "СТРАНА / РЕГИОНЫ"),
        "mode_box_fixed" to arrayOf("CAJA FIJA", "FIXED BOX", "固定框选", "固定ボックス", "स्थिर बॉक्स", "CADRE FIXE", "مربع ثابت", "РАМКА"),
        "mode_poly_sas" to arrayOf("TRAZAR POLÍGONO (SAS)", "DRAW POLYGON (SAS)", "绘制多边形 (SAS)", "ポリゴン描画 (SAS)", "बहुभुज बनाएँ (SAS)", "TRACER POLYGONE (SAS)", "رسم مضلع (SAS)", "ПОЛИГОН (SAS)"),
        "poly_hint_init" to arrayOf(
            "Toca para añadir o arrastra los puntos",
            "Tap to add or drag points",
            "点击添加或拖动顶点",
            "タップで追加・ドラッグで移動",
            "बिंदु जोड़ने के लिए टैप करें या खींचें",
            "Touchez pour ajouter ou glissez les points",
            "المس للإضافة أو اسحب النقاط",
            "Коснитесь для добавления или перетащите точки"
        ),
        "btn_undo" to arrayOf("DESHACER", "UNDO", "撤销", "元に戻す", "पूर्ववत", "ANNULER", "تراجع", "ОТМЕНИТЬ"),
        "btn_clear" to arrayOf("LIMPIAR", "CLEAR", "清除", "クリア", "साफ़ करें", "EFFACER", "مسح", "ОЧИСТИТЬ"),
        "state_hint_step1" to arrayOf(
            "Paso 1: Toca cualquier país en el mapa para cargar sus estados.",
            "Step 1: Tap any country on the map to load its states.",
            "第 1 步：点击地图上的任意国家以加载其州/省。",
            "ステップ1：地図上の国をタップして州を読み込みます。",
            "चरण 1: राज्यों को लोड करने के लिए नक्शे पर किसी भी देश को टैप करें।",
            "Étape 1 : Touchez un pays sur la carte pour charger ses états.",
            "الخطوة 1: المس أي دولة على الخريطة لتحميل ولاياتها.",
            "Шаг 1: Коснитесь любой страны на карте для загрузки регионов."
        ),
        "state_hint_select" to arrayOf(
            "Toca uno o varios estados en el mapa para seleccionarlos en verde.",
            "Tap one or more states on the map to select them in green.",
            "点击地图上的一个或多个州以用绿色选中。",
            "1つまたは複数の州をタップして緑色で選択します。",
            "हरे रंग में चुनने के लिए एक या अधिक राज्यों पर टैप करें।",
            "Touchez un ou plusieurs états pour les sélectionner en vert.",
            "المس ولاية أو أكثر لتحديدها باللون الأخضر.",
            "Коснитесь одного или нескольких регионов для выделения зелёным."
        ),
        "btn_retry" to arrayOf("REINTENTAR", "RETRY", "重试", "再試行", "पुनः प्रयास", "RÉESSAYER", "إعادة المحاولة", "ПОВТОРИТЬ"),
        "btn_reset_country" to arrayOf("LIMPIAR / PAÍS", "RESET COUNTRY", "重置国家", "国をリセット", "देश रीसेट करें", "RÉINIT. PAYS", "إعادة تعيين", "СБРОС СТРАНЫ"),
        "btn_show_map" to arrayOf("MOSTRAR MAPA INTERACTIVO", "SHOW INTERACTIVE MAP", "显示交互式地图", "マップを表示", "इंटरैक्टिव नक्शा दिखाएँ", "AFFICHER LA CARTE", "إظهار الخريطة التفاعلية", "ПОКАЗАТЬ КАРТУ"),
        "btn_hide_map" to arrayOf("OCULTAR MAPA", "HIDE MAP", "隐藏地图", "マップを隠す", "नक्शा छिपाएँ", "MASQUER CARTE", "إخفاء الخريطة", "СКРЫТЬ КАРТУ"),
        "btn_expand" to arrayOf("EXPANDIR", "EXPAND", "放大地图", "拡大", "बड़ा करें", "AGRANDIR", "توسيع", "РАЗВЕРНУТЬ"),
        "btn_view_menu" to arrayOf("VER MENÚ", "VIEW MENU", "查看菜单", "メニュー表示", "मेनू देखें", "VOIR MENU", "عرض القائمة", "МЕНЮ"),
        "sec1_title" to arrayOf(
            "1. PROVEEDOR Y ESTÉTICA VISUAL",
            "1. PROVIDER & VISUAL AESTHETICS",
            "1. 地图源与视觉风格",
            "1. データ提供元と視覚スタイル",
            "1. प्रदाता और दृश्य शैली",
            "1. FOURNISSEUR ET ESTHÉTIQUE VISUELLE",
            "1. المزود والمظهر البصري",
            "1. ИСТОЧНИК И ВИЗУАЛЬНЫЙ СТИЛЬ"
        ),
        "lbl_prov" to arrayOf(
            "Fuente de datos cartográficos:",
            "Cartographic data source:",
            "地图数据源：",
            "地図データソース：",
            "मानचित्र डेटा स्रोत:",
            "Source des données cartographiques :",
            "مصدر بيانات الخريطة:",
            "Источник картографических данных:"
        ),
        "prov_google" to arrayOf(
            "Red Cartográfica GM (Con respaldo automático Carto)",
            "GM Mapping Network (With automatic Carto fallback)",
            "GM 地图网络（带 Carto 自动备份）",
            "GM マップネットワーク（Carto自動バックアップ付）",
            "GM मानचित्र नेटवर्क (Carto बैकअप के साथ)",
            "Réseau Cartographique GM (Secours auto Carto)",
            "شبكة الخرائط GM (مع دعم تلقائي من Carto)",
            "Глобальная сеть GM (С авто-резервом Carto)"
        ),
        "prov_carto" to arrayOf(
            "Carto Dark Matter (Servidor Oscuro)",
            "Carto Dark Matter (Dark Server)",
            "Carto Dark Matter（暗黑服务器）",
            "Carto Dark Matter（ダークサーバー）",
            "Carto Dark Matter (डार्क सर्वर)",
            "Carto Dark Matter (Serveur Sombre)",
            "Carto Dark Matter (خادم داكن)",
            "Carto Dark Matter (Тёмный сервер)"
        ),
        "lbl_shader" to arrayOf(
            "Estilo visual en el reloj (Shader Píxel a Píxel):",
            "Watch visual style (Pixel-by-Pixel Shader):",
            "手表视觉风格（逐像素着色器）：",
            "ウォッチ表示スタイル（ピクセルシェーダー）：",
            "घड़ी की दृश्य शैली (पिक्सेल शेडर):",
            "Style visuel sur la montre (Shader Pixel) :",
            "النمط البصري في الساعة (مرشح البكسل):",
            "Визуальный стиль на часах (Шейдер):"
        ),
        "style_v1" to arrayOf(
            "Cyberpunk V1: Dark Standard (Inversión Nocturna + Esmeralda)",
            "Cyberpunk V1: Dark Standard (Night Inversion + Emerald)",
            "赛博朋克 V1：暗黑标准（夜视反色 + 翡翠绿）",
            "Cyberpunk V1：ダーク標準（夜間反転＋エメラルド）",
            "Cyberpunk V1: डार्क स्टैंडर्ड (रात्रि पन्ना)",
            "Cyberpunk V1 : Dark Standard (Inversion Nocturne)",
            "سايبربانك V1: قياسي داكن (زمردي ليلي)",
            "Cyberpunk V1: Dark Standard (Ночной изумруд)"
        ),
        "style_v2" to arrayOf(
            "Cyberpunk V2: Arasaka Grid (Azul Noche/Carmesí/Cian)",
            "Cyberpunk V2: Arasaka Grid (Night Blue/Crimson/Cyan)",
            "赛博朋克 V2：荒坂网格（夜蓝 / 绯红 / 青色）",
            "Cyberpunk V2：アラサカ・グリッド（紺碧/深紅/シアン）",
            "Cyberpunk V2: अरासाका ग्रिड (नीला/लाल/सियान)",
            "Cyberpunk V2 : Grille Arasaka (Bleu Nuit/Cramoisi/Cyan)",
            "سايبربانك V2: شبكة أراساكا (أزرق/قرمزي/سماوي)",
            "Cyberpunk V2: Arasaka Grid (Синий/Алый/Циан)"
        ),
        "style_v3" to arrayOf(
            "Cyberpunk V3: Kiroshi Optics (Violeta/Magenta/Rojo)",
            "Cyberpunk V3: Kiroshi Optics (Violet/Magenta/Red)",
            "赛博朋克 V3：岐路司光学（紫罗兰 / 品红 / 红）",
            "Cyberpunk V3：キロシ光学（バイオレット/マゼンタ）",
            "Cyberpunk V3: किरोशी ऑप्टिक्स (बैंगनी/मैजेंटा/लाल)",
            "Cyberpunk V3 : Optique Kiroshi (Violet/Magenta/Rouge)",
            "سايبربانك V3: بصريات كيروشي (بنفسجي/أرجواني)",
            "Cyberpunk V3: Kiroshi Optics (Фиолетовый/Пурпурный)"
        ),
        "style_v4_pipboy" to arrayOf(
            "Fallout V4: Pip-Boy 3000 CRT (Fósforo Verde + Scanlines)",
            "Fallout V4: Pip-Boy 3000 CRT (Green Phosphor + Scanlines)",
            "辐射 V4：Pip-Boy 3000 CRT（绿色荧光 + 扫描线）",
            "Fallout V4：Pip-Boy 3000 CRT（緑色蛍光＋走査線）",
            "Fallout V4: Pip-Boy 3000 CRT (हरा फॉस्फोर + स्कैनलाइन)",
            "Fallout V4 : Pip-Boy 3000 CRT (Phosphore Vert + Scanlines)",
            "فول آوت V4: Pip-Boy 3000 CRT (فوسفور أخضر + خطوط مسح)",
            "Fallout V4: Pip-Boy 3000 CRT (Зелёный люминофор + ЭЛТ)"
        ),
        "style_sat" to arrayOf(
            "Satelital Híbrido GM (Fotografía + Calles)",
            "Hybrid Satellite GM (Imagery + Roads)",
            "GM 混合卫星图（卫星影像 + 街道）",
            "GM ハイブリッド衛星写真（衛星＋道路）",
            "GM हाइब्रिड सैटेलाइट (उपग्रह + सड़कें)",
            "Satellite Hybride GM (Photo + Rues)",
            "قمر صناعي هجين GM (صور + شوارع)",
            "Гибрид GM (Спутник + Улицы)"
        ),
        "style_day" to arrayOf(
            "Clásico GM (Día)",
            "Classic GM (Daylight)",
            "GM 经典日间模式",
            "GM クラシック（昼間）",
            "GM क्लासिक (दिन)",
            "Classique GM (Jour)",
            "GM كلاسيكي (نهاري)",
            "Классический GM (День)"
        ),
        "sec2_title" to arrayOf(
            "2. NIVELES DE RESOLUCIÓN Y ÁREAS GUARDADAS",
            "2. RESOLUTION LEVELS & SAVED AREAS",
            "2. 分辨率级别与已保存区域",
            "2. 解像度レベルと保存済みエリア",
            "2. रिज़ॉल्यूशन स्तर और सहेजे गए क्षेत्र",
            "2. NIVEAUX DE RÉSOLUTION ET ZONES SAUVEGARDÉES",
            "2. مستويات الدقة والمناطق المحفوظة",
            "2. УРОВНИ МАСШТАБА И СОХРАНЁННЫЕ ЗОНЫ"
        ),
        "btn_manual_z" to arrayOf(
            "AJUSTE MANUAL POR NIVEL (Z6 A Z20)",
            "MANUAL LEVEL ADJUSTMENT (Z6 TO Z20)",
            "按级别手动调整 (Z6 至 Z20)",
            "レベル別手動調整 (Z6〜Z20)",
            "मैनुअल ज़ूम स्तर चयन (Z6 से Z20)",
            "RÉGLAGE MANUEL PAR NIVEAU (Z6 À Z20)",
            "ضبط يدوي للمستويات (Z6 إلى Z20)",
            "РУЧНОЙ ВЫБОР УРОВНЕЙ (Z6 - Z20)"
        ),
        "btn_scan_urban" to arrayOf("ESCANEAR POBLACIONES", "SCAN POPULATED ZONES", "扫描城镇居民区", "市街地をスキャン", "आबादी क्षेत्र स्कैन करें", "SCANNER LES VILLES", "مسح المناطق المأهولة", "СКАНИРОВАТЬ ГОРОДА"),
        "btn_rescan_urban" to arrayOf("RE-ESCANEAR POBLACIONES", "RE-SCAN POPULATED ZONES", "重新扫描城镇", "市街地を再スキャン", "फिर से स्कैन करें", "RE-SCANNER LES VILLES", "إعادة مسح المدن", "ПЕРЕСКАНИРОВАТЬ"),
        "btn_brush_add" to arrayOf("+ TOCAR MAPA: AÑADIR", "+ TAP MAP: ADD TOWN", "+ 点击地图：添加城镇", "+ マップタップ：追加", "+ नक्शा टैप: जोड़ें", "+ TOUCHER : AJOUTER", "+ المس الخريطة: إضافة", "+ КАРТА: ДОБАВИТЬ"),
        "btn_brush_del" to arrayOf("- TOCAR MAPA: QUITAR", "- TAP MAP: ERASE", "- 点击地图：擦除", "- マップタップ：消去", "- नक्शा टैप: हटाएँ", "- TOUCHER : EFFACER", "- المس الخريطة: مسح", "- КАРТА: СТЕРЕТЬ"),
        "btn_save_preset" to arrayOf("GUARDAR SELECCIÓN", "SAVE SELECTION", "保存当前选区", "選択範囲を保存", "चयन सहेजें", "SAUVEGARDER ZONE", "حفظ التحديد", "СОХРАНИТЬ ЗОНУ"),
        "btn_my_areas" to arrayOf("MIS ÁREAS", "MY AREAS", "我的区域", "保存エリア", "मेरे क्षेत्र", "MES ZONES", "مناطقي", "МОИ ЗОНЫ"),
        "sec3_title" to arrayOf(
            "3. RUTAS DE NAVEGACIÓN (OPENSTREETMAP)",
            "3. NAVIGATION ROUTES (OPENSTREETMAP)",
            "3. 离线导航路线 (OPENSTREETMAP)",
            "3. ナビゲーションルート (OPENSTREETMAP)",
            "3. नेविगेशन मार्ग (OPENSTREETMAP)",
            "3. ROUTES DE NAVIGATION (OPENSTREETMAP)",
            "3. مسارات الملاحة (OPENSTREETMAP)",
            "3. МАРШРУТЫ НАВИГАЦИИ (OPENSTREETMAP)"
        ),
        "cb_include_routes" to arrayOf(
            "Incluir rutas para trazado de caminos offline",
            "Include routes for offline pathfinding",
            "包含用于离线路径规划的道路网",
            "オフライン経路探索用の道路データを含める",
            "ऑफ़लाइन रास्ता खोजने के लिए मार्ग शामिल करें",
            "Inclure les routes pour le calcul d'itinéraire hors-ligne",
            "تضمين المسارات للملاحة بدون إنترنت",
            "Включить граф дорог для офлайн-маршрутов"
        ),
        "rprof_0" to arrayOf("Red Troncal (Carreteras)", "Backbone Network (Highways)", "主干路网（高速公路/国道）", "幹線道路網（高速・国道）", "मुख्य राजमार्ग नेटवर्क", "Réseau Principal (Autoroutes)", "الشبكة الرئيسية (طرق سريعة)", "Магистрали (Трассы)"),
        "rprof_1" to arrayOf("Malla Vehicular (Calles)", "Vehicular Grid (All Streets)", "城市街道网（全部街道）", "市街地道路網（全街路）", "शहरी सड़क जाल (सभी गलियाँ)", "Réseau Urbain (Toutes les rues)", "شبكة الشوارع (كل المدن)", "Уличная сеть (Все улицы)"),
        "rprof_2" to arrayOf("Exploración Total (Detalle Extremo)", "Total Exploration (Footpaths & Trails)", "全地形探索（含步道/山路）", "完全探索（歩道・登山道含む）", "पूर्ण अन्वेषण (पगडंडी सहित)", "Exploration Totale (Sentiers inclus)", "استكشاف شامل (شوارع وممرات)", "Полная сеть (Улицы и тропы)"),
        "sec4_title" to arrayOf(
            "4. LÍMITE DE CUOTA CARTO",
            "4. CARTO QUOTA LIMIT",
            "4. CARTO 配额限制",
            "4. CARTO クォータ制限",
            "4. CARTO कोटा सीमा",
            "4. LIMITE DE QUOTA CARTO",
            "4. حد حصة CARTO",
            "4. ЛИМИТ КВОТЫ CARTO"
        ),
        "btn_forge_map" to arrayOf("MAPA + RUTAS", "MAP + ROUTES", "地图 + 路线", "マップ＋ルート", "नक्शा + मार्ग", "CARTE + ROUTES", "خريطة + مسارات", "КАРТА + МАРШРУТЫ"),
        "btn_forge_routes" to arrayOf("SOLO RUTAS (.HWY)", "ROUTES ONLY (.HWY)", "仅路线 (.HWY)", "ルートのみ (.HWY)", "केवल मार्ग (.HWY)", "ROUTES SEULES (.HWY)", "مسارات فقط (.HWY)", "ТОЛЬКО МАРШРУТЫ (.HWY)"),
    )

    fun tr(context: Context, key: String): String {
        val lang = ExonedForgeI18n.getLang(context)
        val idx = LANG_INDEX[lang] ?: 0
        val arr = DICT[key] ?: return key
        return arr.getOrElse(idx) { arr[0] }
    }
}

// =============================================================================
// MOTOR DE SHADERS CYBERPUNK CONTINUO (V1, V2, V3 + V4 PIP-BOY CLARO Y NÍTIDO)
// =============================================================================
object CyberpunkShaderEngine {

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0 + 1e-6f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun quantizeChannel(v: Float, quantize565: Boolean, bits: Int): Int {
        val c = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        if (!quantize565) return c
        val shift = 8 - bits
        return (c shr shift) shl shift
    }

    fun applyShader(src: Bitmap, styleIdx: Int, quantizeForWatch: Boolean = false): Bitmap {
        val w = src.width
        val h = src.height
        val total = w * h
        val pixels = IntArray(total)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        if (styleIdx == 0) {
            for (i in 0 until total) {
                val px = pixels[i]
                val r = ((px shr 16) and 0xFF) / 255f
                val g = ((px shr 8) and 0xFF) / 255f
                val b = (px and 0xFF) / 255f

                val invR = (1f - b).coerceIn(0f, 1f).pow(1.45f)
                val invG = (1f - g).coerceIn(0f, 1f).pow(1.42f)
                val invB = (1f - r).coerceIn(0f, 1f).pow(1.35f)

                val qr = quantizeChannel(invR, quantizeForWatch, 5)
                val qg = quantizeChannel(invG, quantizeForWatch, 6)
                val qb = quantizeChannel(invB, quantizeForWatch, 5)
                pixels[i] = (0xFF shl 24) or (qr shl 16) or (qg shl 8) or qb
            }
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            return out
        }

        val isKiroshi = (styleIdx == 2)
        val isPipBoy = (styleIdx == 5)

        for (i in 0 until total) {
            val px = pixels[i]
            val r = ((px shr 16) and 0xFF) / 255f
            val g = ((px shr 8) and 0xFF) / 255f
            val b = (px and 0xFF) / 255f

            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            val dark = 1f - lum

            val veg = smoothstep(0.012f, 0.075f, g - max(r, b))
            val poi = smoothstep(0.055f, 0.20f, min(r - g, b - g))
            val water = smoothstep(0.045f, 0.14f, b - r) * smoothstep(0.77f, 0.87f, lum) * (1f - veg)

            // Misma máscara limpia de V2 y V3: evita que el polígono urbano de las ciudades se pinte como calle
            val roadGate = (1f - veg) * (1f - poi) * (1f - water * 0.92f)
            val blueRamp = max(0f, b - r) * roadGate

            val highway = smoothstep(0.075f, 0.175f, blueRamp)
            val street = smoothstep(0.018f, 0.075f, blueRamp) * (1f - highway)

            val terrainTone = (0.38f + 0.62f * smoothstep(0.68f, 0.95f, lum)) * (1f - veg * 0.45f)
            val textMask = smoothstep(0.18f, 0.68f, dark) * (1f - highway * 0.85f) * (1f - poi * 0.75f)

            var outR: Float
            var outG: Float
            var outB: Float

            if (isPipBoy) {
                // V4: Pip-Boy Claro (Misma estructura y nitidez que V2/V3 en paleta verde Fallout)
                // 1. Terreno oscuro limpio y cuerpos de agua diferenciados
                outR = (0.01f * terrainTone) + water * 0.01f
                outG = (0.18f * terrainTone) + water * 0.30f
                outB = (0.03f * terrainTone) + water * 0.10f

                // 2. Calles secundarias en verde medio
                outR = outR * (1f - street) + street * 0.05f
                outG = outG * (1f - street) + street * 0.48f
                outB = outB * (1f - street) + street * 0.12f

                // 3. Carreteras principales en verde neón brillante
                outR = outR * (1f - highway) + highway * 0.14f
                outG = outG * (1f - highway) + highway * 0.92f
                outB = outB * (1f - highway) + highway * 0.22f

                // 4. POIs y escudos carreteros en verde lima
                outR = outR * (1f - poi) + poi * 0.28f
                outG = outG * (1f - poi) + poi * 0.85f
                outB = outB * (1f - poi) + poi * 0.20f

                // 5. Textos ("Xalapa-Enríquez") y números ("140") en blanco-menta para contraste total
                outR = outR * (1f - textMask) + textMask * 0.86f
                outG = outG * (1f - textMask) + textMask * 1.00f
                outB = outB * (1f - textMask) + textMask * 0.88f
            } else {
                if (isKiroshi) {
                    outR = (0.24f * terrainTone) + water * 0.05f
                    outG = (0.04f * terrainTone) + water * 0.18f
                    outB = (0.20f * terrainTone) + water * 0.35f

                    outR = outR * (1f - street) + street * 0.58f
                    outG = outG * (1f - street) + street * 0.10f
                    outB = outB * (1f - street) + street * 0.44f

                    outR = outR * (1f - highway) + highway * 1.00f
                    outG = outG * (1f - highway) + highway * 0.12f
                    outB = outB * (1f - highway) + highway * 0.22f
                } else {
                    outR = (0.02f * terrainTone) + water * 0.02f
                    outG = (0.20f * terrainTone) + water * 0.30f
                    outB = (0.34f * terrainTone) + water * 0.48f

                    outR = outR * (1f - street) + street * 0.08f
                    outG = outG * (1f - street) + street * 0.46f
                    outB = outB * (1f - street) + street * 0.64f

                    outR = outR * (1f - highway) + highway * 1.00f
                    outG = outG * (1f - highway) + highway * 0.08f
                    outB = outB * (1f - highway) + highway * 0.20f
                }

                outR = outR * (1f - poi) + poi * 0.10f
                outG = outG * (1f - poi) + poi * 0.95f
                outB = outB * (1f - poi) + poi * 0.48f

                outR = outR * (1f - textMask) + textMask * 0.94f
                outG = outG * (1f - textMask) + textMask * 0.98f
                outB = outB * (1f - textMask) + textMask * 1.00f
            }

            val qr = quantizeChannel(outR.coerceIn(0f, 1f), quantizeForWatch, 5)
            val qg = quantizeChannel(outG.coerceIn(0f, 1f), quantizeForWatch, 6)
            val qb = quantizeChannel(outB.coerceIn(0f, 1f), quantizeForWatch, 5)
            pixels[i] = (0xFF shl 24) or (qr shl 16) or (qg shl 8) or qb
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}

// =============================================================================
// MOTOR GEOPOLÍTICO DE PRECISIÓN 1:1
// =============================================================================
object GeoBoundaryEngine {

    private val OVERPASS_MIRRORS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://lz4.overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    data class CountryIdentification(
        val countryName: String,
        val countryCodeIso2: String,
        val ringsJson: String,
        val south: Double,
        val north: Double,
        val west: Double,
        val east: Double
    )

    fun identifyCountryAt(
        lat: Double,
        lon: Double,
        onStatus: (String) -> Unit
    ): Result<CountryIdentification> {
        val ping = NetworkHealthMonitor.checkInternetPingMs()
        val netLabel = NetworkHealthMonitor.formatNetTag(ping)
        if (ping < 0L) {
            return Result.failure(Exception("Conexión a Internet caída o sin salida de datos."))
        }
        onStatus("$netLabel | Descargando frontera nacional exacta...")

        var conn: HttpURLConnection? = null
        return try {
            val url = "https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&zoom=3&format=json&polygon_geojson=1&polygon_threshold=0.0001"
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 20000
                setRequestProperty("User-Agent", "ExonedRouter/2.0 (Tactical Mapping)")
                setRequestProperty("Accept-Encoding", "gzip")
            }
            if (conn.responseCode != 200) {
                return Result.failure(Exception("Nominatim HTTP ${conn.responseCode} ($netLabel)"))
            }
            val rawStream = if ((conn.contentEncoding ?: "").contains("gzip", true)) {
                GZIPInputStream(conn.inputStream)
            } else conn.inputStream

            val raw = rawStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val obj = JSONObject(raw)
            val address = obj.optJSONObject("address")
            val code = (address?.optString("country_code") ?: "").uppercase(Locale.US)
            val name = address?.optString("country") ?: obj.optString("name", "País")
            if (code.isBlank()) {
                return Result.failure(Exception("Toca tierra firme dentro de un país."))
            }

            val bboxArr = obj.optJSONArray("boundingbox")
            val s = bboxArr?.optString(0)?.toDoubleOrNull() ?: (lat - 5.0)
            val n = bboxArr?.optString(1)?.toDoubleOrNull() ?: (lat + 5.0)
            val w = bboxArr?.optString(2)?.toDoubleOrNull() ?: (lon - 5.0)
            val e = bboxArr?.optString(3)?.toDoubleOrNull() ?: (lon + 5.0)

            val geojson = obj.optJSONObject("geojson")
            val rings = extractRingsFromGeoJson(geojson)
            val ringsJsonArr = JSONArray()
            for (r in rings) {
                ringsJsonArr.put(pointsToJsonArray(r, maxPoints = 8000))
            }

            Result.success(
                CountryIdentification(
                    countryName = name,
                    countryCodeIso2 = code,
                    ringsJson = ringsJsonArr.toString(),
                    south = s,
                    north = n,
                    west = w,
                    east = e
                )
            )
        } catch (ex: Throwable) {
            Result.failure(Exception("${ex.localizedMessage ?: "Timeout"} ($netLabel)"))
        } finally {
            conn?.disconnect()
        }
    }

    fun fetchStatesForCountry(
        country: CountryIdentification,
        onProgress: (Int, String) -> Unit
    ): Result<List<StateBoundaryInfo>> {
        val iso2 = country.countryCodeIso2

        val fastIsoQuery = """
            [out:json][timeout:45];
            (
              relation["ISO3166-2"~"^${iso2}-"]["boundary"="administrative"];
            );
            out geom qt;
        """.trimIndent()

        val bboxStr = String.format(
            Locale.US,
            "%.4f,%.4f,%.4f,%.4f",
            country.south, country.west, country.north, country.east
        )
        val fallbackBboxQuery = """
            [out:json][timeout:45];
            (
              relation["admin_level"="4"]["boundary"="administrative"]($bboxStr);
            );
            out geom qt;
        """.trimIndent()

        var lastErr = "Sin respuesta de Overpass."

        for ((idx, mirror) in OVERPASS_MIRRORS.withIndex()) {
            val ping = NetworkHealthMonitor.checkInternetPingMs()
            val netTag = NetworkHealthMonitor.formatNetTag(ping)
            if (ping < 0L) {
                onProgress(15, "$netTag — Esperando recuperación de tu red...")
                Thread.sleep(1200)
            }

            val queryToUse = if (idx < 2) fastIsoQuery else fallbackBboxQuery
            val bytesRead = AtomicLong(0L)
            val stopTicker = AtomicBoolean(false)
            val startTime = System.currentTimeMillis()
            var currentVision = "Esperando respuesta del servidor Overpass (Nodo ${idx + 1})..."

            val watcher = thread(start = true, isDaemon = true) {
                while (!stopTicker.get()) {
                    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
                    val kb = bytesRead.get() / 1024L
                    if (kb == 0L) {
                        val livePing = if (elapsedSec > 4.0) NetworkHealthMonitor.checkInternetPingMs() else ping
                        val liveNet = NetworkHealthMonitor.formatNetTag(livePing)
                        val waitMsg = if (livePing < 0L) {
                            "[ALERTA: RED INESTABLE] Sin respuesta de datos (${String.format(Locale.US, "%.1f", elapsedSec)}s)..."
                        } else {
                            "$liveNet | Servidor OSM preparando fronteras (${String.format(Locale.US, "%.1f", elapsedSec)}s)..."
                        }
                        onProgress(min(45, 20 + (elapsedSec * 2).toInt()), waitMsg)
                    } else {
                        val speedKbs = (kb / max(0.5, elapsedSec)).toInt()
                        val pct = min(94, 45 + (kb / 22).toInt().coerceAtMost(49))
                        onProgress(pct, "[$kb KB | $speedKbs KB/s] $currentVision")
                    }
                    try {
                        Thread.sleep(450)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }

            var conn: HttpURLConnection? = null
            try {
                val postData = "data=" + URLEncoder.encode(queryToUse, "UTF-8")
                conn = (URL(mirror).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 12000
                    readTimeout = 45000
                    setRequestProperty("User-Agent", "ExonedRouter/2.0")
                    setRequestProperty("Accept-Encoding", "gzip")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                }
                conn.outputStream.use { it.write(postData.toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode == 200) {
                    val countedCompressed = CountingInputStream(conn.inputStream, bytesRead)
                    val stream = if ((conn.contentEncoding ?: "").contains("gzip", true)) {
                        GZIPInputStream(countedCompressed)
                    } else countedCompressed

                    val statesList = streamParseStatesJson(stream, iso2) { visionMsg ->
                        currentVision = visionMsg
                    }
                    stopTicker.set(true)

                    if (statesList.isNotEmpty()) {
                        onProgress(100, "${statesList.size} estados/provincias delimitados con precisión 1:1.")
                        return Result.success(statesList)
                    } else {
                        lastErr = "Consulta vacía en Nodo ${idx + 1}, probando siguiente..."
                    }
                } else {
                    lastErr = "Servidor OSM ocupado (HTTP ${conn.responseCode})"
                }
            } catch (t: Throwable) {
                val postPing = NetworkHealthMonitor.checkInternetPingMs()
                lastErr = if (postPing < 0L) {
                    "Tu conexión a Internet se cortó durante la descarga."
                } else {
                    "Servidor OSM Nodo ${idx + 1} tardó demasiado (${t.localizedMessage ?: "Timeout"})."
                }
            } finally {
                stopTicker.set(true)
                watcher.interrupt()
                conn?.disconnect()
            }
        }

        return Result.failure(Exception(lastErr))
    }

    private fun streamParseStatesJson(
        inputStream: InputStream,
        expectedIso2: String,
        onVisionUpdate: (String) -> Unit
    ): List<StateBoundaryInfo> {
        val statesList = ArrayList<StateBoundaryInfo>()
        val reader = JsonReader(BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8), 32768))

        reader.beginObject()
        while (reader.hasNext()) {
            val rootKey = reader.nextName()
            if (rootKey == "elements") {
                reader.beginArray()
                var count = 0
                while (reader.hasNext()) {
                    val stateInfo = parseSingleStateRelation(reader, expectedIso2, count + 1, onVisionUpdate)
                    if (stateInfo != null) {
                        statesList.add(stateInfo)
                        count++
                    }
                }
                reader.endArray()
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        reader.close()
        return statesList
    }

    private fun parseSingleStateRelation(
        reader: JsonReader,
        expectedIso2: String,
        idxNum: Int,
        onVisionUpdate: (String) -> Unit
    ): StateBoundaryInfo? {
        var isRelation = false
        var relId = 0L
        var stateName = ""
        var stateNameEs = ""
        var stateNameEn = ""
        var iso3166_2 = ""
        val outerSegments = ArrayList<List<GeoPoint>>()
        var totalVerticesRead = 0

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "type" -> isRelation = (reader.nextString() == "relation")
                "id" -> relId = reader.nextLong()
                "tags" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "name:es" -> stateNameEs = reader.nextString()
                            "name:en" -> stateNameEn = reader.nextString()
                            "name" -> stateName = reader.nextString()
                            "ISO3166-2" -> iso3166_2 = reader.nextString()
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                "members" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        var role = "outer"
                        var segPts: List<GeoPoint>? = null
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "role" -> role = reader.nextString()
                                "geometry" -> {
                                    segPts = readHighPrecisionGeometryArray(reader, minStepDeg = 0.0001)
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        if (role != "inner" && segPts != null && segPts.size >= 2) {
                            outerSegments.add(segPts)
                            totalVerticesRead += segPts.size
                        }
                    }
                    reader.endArray()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (!isRelation || outerSegments.isEmpty()) return null
        if (iso3166_2.isNotBlank() && !iso3166_2.startsWith("$expectedIso2-", ignoreCase = true)) {
            return null
        }

        val finalName = stateNameEs.ifBlank { stateNameEn.ifBlank { stateName.ifBlank { "Región #$relId" } } }
        onVisionUpdate("Leyendo #$idxNum: $finalName ($totalVerticesRead pts)...")

        val stitchedRings = stitchWaySegmentsExact(outerSegments)
        if (stitchedRings.isEmpty()) return null

        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        for (ring in stitchedRings) {
            for (p in ring) {
                if (p.lat < minLat) minLat = p.lat
                if (p.lat > maxLat) maxLat = p.lat
                if (p.lon < minLon) minLon = p.lon
                if (p.lon > maxLon) maxLon = p.lon
            }
        }

        return StateBoundaryInfo(
            relationId = relId,
            name = finalName,
            rings = stitchedRings,
            north = maxLat,
            south = minLat,
            east = maxLon,
            west = minLon
        )
    }

    private fun readHighPrecisionGeometryArray(reader: JsonReader, minStepDeg: Double): List<GeoPoint> {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return emptyList()
        }
        val pts = ArrayList<GeoPoint>()
        var lastKeptLat = Double.NaN
        var lastKeptLon = Double.NaN
        var latestLat = Double.NaN
        var latestLon = Double.NaN

        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull()
                continue
            }
            var lat = Double.NaN
            var lon = Double.NaN
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "lat" -> lat = reader.nextDouble()
                    "lon" -> lon = reader.nextDouble()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            if (!lat.isNaN() && !lon.isNaN()) {
                latestLat = lat
                latestLon = lon
                if (lastKeptLat.isNaN() ||
                    abs(lat - lastKeptLat) >= minStepDeg ||
                    abs(lon - lastKeptLon) >= minStepDeg
                ) {
                    pts.add(GeoPoint(lat, lon))
                    lastKeptLat = lat
                    lastKeptLon = lon
                }
            }
        }
        reader.endArray()

        if (!latestLat.isNaN() && (latestLat != lastKeptLat || latestLon != lastKeptLon)) {
            pts.add(GeoPoint(latestLat, latestLon))
        }
        return pts
    }

    private fun stitchWaySegmentsExact(segments: List<List<GeoPoint>>): List<List<GeoPoint>> {
        val pool = segments.filter { it.size >= 2 }.map { it.toMutableList() }.toMutableList()
        val closedRings = ArrayList<List<GeoPoint>>()

        fun distSq(a: GeoPoint, b: GeoPoint): Double {
            val dLat = a.lat - b.lat
            val dLon = a.lon - b.lon
            return dLat * dLat + dLon * dLon
        }

        val exactMatchSq = 0.0005 * 0.0005
        val smallGapSq = 0.005 * 0.005

        while (pool.isNotEmpty()) {
            var longestIdx = 0
            for (i in 1 until pool.size) {
                if (pool[i].size > pool[longestIdx].size) longestIdx = i
            }
            val current = pool.removeAt(longestIdx)

            while (pool.isNotEmpty()) {
                val head = current.first()
                val tail = current.last()

                if (current.size >= 4 && distSq(head, tail) <= exactMatchSq) {
                    break
                }

                var bestIdx = -1
                var bestMode = 0
                var bestDist = Double.MAX_VALUE

                for (i in pool.indices) {
                    val cand = pool[i]
                    val cHead = cand.first()
                    val cTail = cand.last()

                    val d1 = distSq(tail, cHead)
                    if (d1 < bestDist) { bestDist = d1; bestIdx = i; bestMode = 1 }

                    val d2 = distSq(tail, cTail)
                    if (d2 < bestDist) { bestDist = d2; bestIdx = i; bestMode = 2 }

                    val d3 = distSq(head, cTail)
                    if (d3 < bestDist) { bestDist = d3; bestIdx = i; bestMode = 3 }

                    val d4 = distSq(head, cHead)
                    if (d4 < bestDist) { bestDist = d4; bestIdx = i; bestMode = 4 }

                    if (bestDist < 1e-11) break
                }

                if (bestIdx != -1 && bestDist <= smallGapSq) {
                    val cand = pool.removeAt(bestIdx)
                    when (bestMode) {
                        1 -> current.addAll(cand.subList(1, cand.size))
                        2 -> {
                            val rev = cand.asReversed()
                            current.addAll(rev.subList(1, rev.size))
                        }
                        3 -> current.addAll(0, cand.subList(0, cand.size - 1))
                        4 -> {
                            val rev = cand.asReversed()
                            current.addAll(0, rev.subList(0, rev.size - 1))
                        }
                    }
                } else {
                    break
                }
            }

            val head = current.first()
            val tail = current.last()
            val isProperlyClosed = distSq(head, tail) <= (0.12 * 0.12)
            if (current.size >= 8 && (isProperlyClosed || closedRings.isEmpty())) {
                closedRings.add(simplifyRing(current, maxPoints = 3500))
            }
        }

        closedRings.sortByDescending { it.size }
        return closedRings
    }

    private fun simplifyRing(pts: List<GeoPoint>, maxPoints: Int): List<GeoPoint> {
        if (pts.size <= maxPoints) return pts
        val out = ArrayList<GeoPoint>(maxPoints + 2)
        val step = (pts.size - 1).toDouble() / (maxPoints - 1).toDouble()
        for (i in 0 until maxPoints - 1) {
            out.add(pts[(i * step).toInt()])
        }
        out.add(pts.last())
        return out
    }

    private fun extractRingsFromGeoJson(geojson: JSONObject?): List<List<GeoPoint>> {
        if (geojson == null) return emptyList()
        val type = geojson.optString("type")
        val coords = geojson.optJSONArray("coordinates") ?: return emptyList()
        val out = ArrayList<List<GeoPoint>>()

        if (type == "Polygon") {
            val outer = coords.optJSONArray(0) ?: return emptyList()
            out.add(parseCoordRing(outer))
        } else if (type == "MultiPolygon") {
            for (i in 0 until coords.length()) {
                val poly = coords.optJSONArray(i) ?: continue
                val outer = poly.optJSONArray(0) ?: continue
                val ring = parseCoordRing(outer)
                if (ring.size >= 4) out.add(ring)
            }
        }
        return out
    }

    private fun parseCoordRing(arr: JSONArray): List<GeoPoint> {
        val list = ArrayList<GeoPoint>(arr.length())
        for (i in 0 until arr.length()) {
            val pair = arr.optJSONArray(i) ?: continue
            val lon = pair.optDouble(0)
            val lat = pair.optDouble(1)
            list.add(GeoPoint(lat, lon))
        }
        return list
    }

    fun pointsToJsonArray(pts: List<GeoPoint>, maxPoints: Int = 3500): JSONArray {
        val simplified = simplifyRing(pts, maxPoints)
        val arr = JSONArray()
        for (p in simplified) {
            val pair = JSONArray()
            pair.put((p.lat * 100000.0).toInt() / 100000.0)
            pair.put((p.lon * 100000.0).toInt() / 100000.0)
            arr.put(pair)
        }
        return arr
    }
}
// =============================================================================
// ESCÁNER DE POBLACIONES + ENRIQUECEDOR ULTRALIGERO (ANTI-502 / FLUJO SECUENCIAL)
// =============================================================================
object UrbanScannerEngine {

    const val Z_MASK = 16

    private val OVERPASS_MIRRORS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://lz4.overpass-api.de/api/interpreter"
    )

    fun packXY(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
    fun unpackX(code: Long): Int = (code shr 32).toInt()
    fun unpackY(code: Long): Int = code.toInt()

    fun tileYToLat(y: Double, z: Int): Double {
        val n = Math.PI - (2.0 * Math.PI * y) / (1 shl z).toDouble()
        return Math.toDegrees(atan(sinh(n)))
    }

    fun tileXToLon(x: Double, z: Int): Double {
        return (x / (1 shl z).toDouble()) * 360.0 - 180.0
    }

    fun isPointInPolygon(lat: Double, lon: Double, poly: List<GeoPoint>): Boolean {
        if (poly.size < 3) return false
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val yi = poly[i].lat
            val xi = poly[i].lon
            val yj = poly[j].lat
            val xj = poly[j].lon
            val intersect = ((yi > lat) != (yj > lat)) &&
                    (lon < (xj - xi) * (lat - yi) / ((yj - yi) + 1e-12) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    fun isPointInAnyRing(lat: Double, lon: Double, rings: List<List<GeoPoint>>): Boolean {
        for (r in rings) {
            if (isPointInPolygon(lat, lon, r)) return true
        }
        return false
    }

    private fun isPointInPolygonPrimitive(
        lat: Double,
        lon: Double,
        latBuf: DoubleArray,
        lonBuf: DoubleArray,
        count: Int
    ): Boolean {
        if (count < 3) return false
        var inside = false
        var j = count - 1
        for (i in 0 until count) {
            val yi = latBuf[i]
            val xi = lonBuf[i]
            val yj = latBuf[j]
            val xj = lonBuf[j]
            val intersect = ((yi > lat) != (yj > lat)) &&
                    (lon < (xj - xi) * (lat - yi) / ((yj - yi) + 1e-12) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    private fun stampDiscOnGrid(
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double,
        limitX0: Int,
        limitY0: Int,
        gridW: Int,
        gridH: Int,
        grid: BooleanArray
    ) {
        val dLat = radiusMeters / 111132.0
        val dLon = radiusMeters / (111320.0 * max(0.2, abs(cos(Math.toRadians(centerLat)))))
        val (x0, y0) = TileMatrixCalculator.deg2num(centerLat + dLat, centerLon - dLon, Z_MASK)
        val (x1, y1) = TileMatrixCalculator.deg2num(centerLat - dLat, centerLon + dLon, Z_MASK)
        val (cx, cy) = TileMatrixCalculator.deg2num(centerLat, centerLon, Z_MASK)

        val sx0 = (min(x0, x1) - limitX0).coerceIn(0, gridW - 1)
        val sx1 = (max(x0, x1) - limitX0).coerceIn(0, gridW - 1)
        val sy0 = (min(y0, y1) - limitY0).coerceIn(0, gridH - 1)
        val sy1 = (max(y0, y1) - limitY0).coerceIn(0, gridH - 1)

        val rx = max(0.8, abs(x1 - x0) * 0.5)
        val ry = max(0.8, abs(y1 - y0) * 0.5)
        val lcx = cx - limitX0
        val lcy = cy - limitY0

        for (gx in sx0..sx1) {
            val nx = (gx - lcx) / rx
            for (gy in sy0..sy1) {
                val ny = (gy - lcy) / ry
                if (nx * nx + ny * ny <= 1.05) {
                    grid[gy * gridW + gx] = true
                }
            }
        }
    }

    private fun rasterizeWayPrimitive(
        latBuf: DoubleArray,
        lonBuf: DoubleArray,
        ptCount: Int,
        isClosedArea: Boolean,
        limitX0: Int,
        limitY0: Int,
        gridW: Int,
        gridH: Int,
        grid: BooleanArray
    ) {
        if (ptCount <= 0) return

        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE

        for (i in 0 until ptCount) {
            val lat = latBuf[i]
            val lon = lonBuf[i]
            if (lat < minLat) minLat = lat
            if (lat > maxLat) maxLat = lat
            if (lon < minLon) minLon = lon
            if (lon > maxLon) maxLon = lon
        }

        for (i in 0 until ptCount - 1) {
            val lat1 = latBuf[i]
            val lon1 = lonBuf[i]
            val dLat = latBuf[i + 1] - lat1
            val dLon = lonBuf[i + 1] - lon1
            val approxDistDeg = sqrt(dLat * dLat + dLon * dLon)
            if (!isClosedArea && approxDistDeg > 0.0042) continue

            val steps = max(1, (approxDistDeg / 0.0015).toInt())
            for (s in 0..steps) {
                val t = s.toDouble() / steps.toDouble()
                val lat = lat1 + dLat * t
                val lon = lon1 + dLon * t
                val (tx, ty) = TileMatrixCalculator.deg2num(lat, lon, Z_MASK)
                val gx = tx - limitX0
                val gy = ty - limitY0
                if (gx in 0 until gridW && gy in 0 until gridH) {
                    grid[gy * gridW + gx] = true
                }
            }
        }

        if (isClosedArea && ptCount >= 3) {
            val (x0, y0) = TileMatrixCalculator.deg2num(maxLat, minLon, Z_MASK)
            val (x1, y1) = TileMatrixCalculator.deg2num(minLat, maxLon, Z_MASK)
            val spanX = abs(x1 - x0)
            val spanY = abs(y1 - y0)
            if (spanX * spanY > 240) return

            val sx0 = (min(x0, x1) - limitX0).coerceIn(0, gridW - 1)
            val sx1 = (max(x0, x1) - limitX0).coerceIn(0, gridW - 1)
            val sy0 = (min(y0, y1) - limitY0).coerceIn(0, gridH - 1)
            val sy1 = (max(y0, y1) - limitY0).coerceIn(0, gridH - 1)

            for (gx in sx0..sx1) {
                val lonC = tileXToLon(limitX0 + gx + 0.5, Z_MASK)
                for (gy in sy0..sy1) {
                    if (grid[gy * gridW + gx]) continue
                    val latC = tileYToLat(limitY0 + gy + 0.5, Z_MASK)
                    if (isPointInPolygonPrimitive(latC, lonC, latBuf, lonBuf, ptCount)) {
                        grid[gy * gridW + gx] = true
                    }
                }
            }
        }
    }

    private fun accumulateStreetBoundsHit(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
        limitX0: Int,
        limitY0: Int,
        gridW: Int,
        gridH: Int,
        streetHits: ShortArray
    ) {
        val dLat = abs(maxLat - minLat)
        val dLon = abs(maxLon - minLon)

        val isCompactBlock = (dLat <= 0.0052 && dLon <= 0.0052)
        val isAlignedAvenue = (min(dLat, dLon) <= 0.0022 && max(dLat, dLon) <= 0.011)

        if (isCompactBlock || isAlignedAvenue) {
            val (x0, y0) = TileMatrixCalculator.deg2num(maxLat, minLon, Z_MASK)
            val (x1, y1) = TileMatrixCalculator.deg2num(minLat, maxLon, Z_MASK)
            val sx0 = (min(x0, x1) - limitX0).coerceIn(0, gridW - 1)
            val sx1 = (max(x0, x1) - limitX0).coerceIn(0, gridW - 1)
            val sy0 = (min(y0, y1) - limitY0).coerceIn(0, gridH - 1)
            val sy1 = (max(y0, y1) - limitY0).coerceIn(0, gridH - 1)

            for (gy in sy0..sy1) {
                val rowOff = gy * gridW
                for (gx in sx0..sx1) {
                    val cur = streetHits[rowOff + gx].toInt()
                    if (cur < 1000) streetHits[rowOff + gx] = (cur + 1).toShort()
                }
            }
        } else if (dLat <= 0.015 && dLon <= 0.015) {
            val cLat = (minLat + maxLat) * 0.5
            val cLon = (minLon + maxLon) * 0.5
            val (cx, cy) = TileMatrixCalculator.deg2num(cLat, cLon, Z_MASK)
            val gx = cx - limitX0
            val gy = cy - limitY0
            if (gx in 0 until gridW && gy in 0 until gridH) {
                val idx = gy * gridW + gx
                val cur = streetHits[idx].toInt()
                if (cur < 1000) streetHits[idx] = (cur + 1).toShort()
            }
        }
    }

    private fun mergeStreetGridIntoUrbanFootprint(
        rawGrid: BooleanArray,
        streetHits: ShortArray,
        w: Int,
        h: Int
    ) {
        for (i in rawGrid.indices) {
            if (streetHits[i] >= 2) {
                rawGrid[i] = true
            }
        }

        val snapshot = rawGrid.clone()
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                if (!rawGrid[idx] && streetHits[idx] == 1.toShort()) {
                    if (snapshot[idx - 1] || snapshot[idx + 1] || snapshot[idx - w] || snapshot[idx + w]) {
                        rawGrid[idx] = true
                    }
                }
            }
        }
    }

    private fun consolidateTightUrbanFootprint(rawGrid: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = rawGrid.clone()

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                if (!out[idx]) {
                    var neighbors = 0
                    if (out[(y - 1) * w + x]) neighbors++
                    if (out[(y + 1) * w + x]) neighbors++
                    if (out[y * w + (x - 1)]) neighbors++
                    if (out[y * w + (x + 1)]) neighbors++
                    if (neighbors >= 3) out[idx] = true
                }
            }
        }

        fillSmallInteriorParks(out, w, h, maxHoleCells = 10)
        return out
    }

    private fun fillSmallInteriorParks(grid: BooleanArray, w: Int, h: Int, maxHoleCells: Int) {
        val visited = BooleanArray(w * h)
        val queue = IntArray(min(w * h, 65536))
        val compNodes = IntArray(maxHoleCells + 2)
        val dx = intArrayOf(1, -1, 0, 0)
        val dy = intArrayOf(0, 0, 1, -1)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val startIdx = y * w + x
                if (grid[startIdx] || visited[startIdx]) continue

                var qHead = 0
                var qTail = 0
                var compSize = 0
                var touchesBorder = false

                visited[startIdx] = true
                queue[qTail++] = startIdx

                while (qHead < qTail) {
                    val cur = queue[qHead % queue.size]
                    qHead++
                    if (compSize <= maxHoleCells) {
                        compNodes[compSize] = cur
                    }
                    compSize++

                    val cx = cur % w
                    val cy = cur / w
                    if (cx == 0 || cx == w - 1 || cy == 0 || cy == h - 1) {
                        touchesBorder = true
                    }
                    for (d in 0..3) {
                        val nx = cx + dx[d]
                        val ny = cy + dy[d]
                        if (nx in 0 until w && ny in 0 until h) {
                            val nIdx = ny * w + nx
                            if (!grid[nIdx] && !visited[nIdx]) {
                                visited[nIdx] = true
                                if (qTail - qHead < queue.size - 1) {
                                    queue[qTail % queue.size] = nIdx
                                    qTail++
                                }
                            }
                        }
                    }
                }

                if (!touchesBorder && compSize <= maxHoleCells) {
                    for (k in 0 until compSize) {
                        grid[compNodes[k]] = true
                    }
                }
            }
        }
    }

    fun buildPolygonsFromCells(
        cellsZ16: Set<Long>
    ): Pair<String, Triple<Int, Int, Int>> {
        if (cellsZ16.isEmpty()) return Pair("[]", Triple(0, 0, 0))

        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (c in cellsZ16) {
            val x = unpackX(c)
            val y = unpackY(c)
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }

        val limitX0 = minX - 1
        val limitY0 = minY - 1
        val w = (maxX - minX + 3).coerceAtLeast(3)
        val h = (maxY - minY + 3).coerceAtLeast(3)

        val grid = BooleanArray(w * h)
        for (c in cellsZ16) {
            val gx = unpackX(c) - limitX0
            val gy = unpackY(c) - limitY0
            if (gx in 0 until w && gy in 0 until h) {
                grid[gy * w + gx] = true
            }
        }

        val visited = BooleanArray(w * h)
        val queue = IntArray(min(w * h, 131072))
        val dx4 = intArrayOf(1, -1, 0, 0)
        val dy4 = intArrayOf(0, 0, 1, -1)

        val components = ArrayList<IntArray>()

        for (y in 0 until h) {
            for (x in 0 until w) {
                val startIdx = y * w + x
                if (!grid[startIdx] || visited[startIdx]) continue

                var qHead = 0
                var qTail = 0
                val members = ArrayList<Int>()
                visited[startIdx] = true
                queue[qTail++] = startIdx

                while (qHead < qTail) {
                    val cur = queue[qHead % queue.size]
                    qHead++
                    members.add(cur)
                    val cx = cur % w
                    val cy = cur / w
                    for (d in 0..3) {
                        val nx = cx + dx4[d]
                        val ny = cy + dy4[d]
                        if (nx in 0 until w && ny in 0 until h) {
                            val nIdx = ny * w + nx
                            if (grid[nIdx] && !visited[nIdx]) {
                                visited[nIdx] = true
                                if (qTail - qHead < queue.size - 1) {
                                    queue[qTail % queue.size] = nIdx
                                    qTail++
                                }
                            }
                        }
                    }
                }
                components.add(members.toIntArray())
            }
        }

        components.sortByDescending { it.size }

        var citiesCount = 0
        var townsCount = 0
        var communitiesCount = 0

        val jsonPolygonsWithHoles = JSONArray()

        for (comp in components) {
            when {
                comp.size >= 25 -> citiesCount++
                comp.size in 5..24 -> townsCount++
                else -> communitiesCount++
            }

            var cMinX = w
            var cMaxX = 0
            var cMinY = h
            var cMaxY = 0
            for (idx in comp) {
                val cx = idx % w
                val cy = idx / w
                if (cx < cMinX) cMinX = cx
                if (cx > cMaxX) cMaxX = cx
                if (cy < cMinY) cMinY = cy
                if (cy > cMaxY) cMaxY = cy
            }

            val subW = (cMaxX - cMinX + 3)
            val subH = (cMaxY - cMinY + 3)
            val localMask = BooleanArray(subW * subH)
            for (idx in comp) {
                val lx = (idx % w) - cMinX + 1
                val ly = (idx / w) - cMinY + 1
                localMask[ly * subW + lx] = true
            }

            val baseLimitX = limitX0 + cMinX - 1
            val baseLimitY = limitY0 + cMinY - 1

            val outerRing = traceContour(localMask, subW, subH, baseLimitX, baseLimitY)
            if (outerRing.size >= 3) {
                val ringsArray = JSONArray()
                ringsArray.put(polyToJsonArray(outerRing))

                val emptyVisited = BooleanArray(subW * subH)
                val eQueue = IntArray(subW * subH)

                for (sy in 0 until subH) {
                    for (sx in 0 until subW) {
                        val sIdx = sy * subW + sx
                        if (localMask[sIdx] || emptyVisited[sIdx]) continue

                        var qH = 0
                        var qT = 0
                        var touchesEdge = false
                        val holeCells = ArrayList<Int>()
                        emptyVisited[sIdx] = true
                        eQueue[qT++] = sIdx

                        while (qH < qT) {
                            val cur = eQueue[qH++]
                            holeCells.add(cur)
                            val ex = cur % subW
                            val ey = cur / subW
                            if (ex == 0 || ex == subW - 1 || ey == 0 || ey == subH - 1) {
                                touchesEdge = true
                            }
                            for (d in 0..3) {
                                val nx = ex + dx4[d]
                                val ny = ey + dy4[d]
                                if (nx in 0 until subW && ny in 0 until subH) {
                                    val nSIdx = ny * subW + nx
                                    if (!localMask[nSIdx] && !emptyVisited[nSIdx]) {
                                        emptyVisited[nSIdx] = true
                                        eQueue[qT++] = nSIdx
                                    }
                                }
                            }
                        }

                        if (!touchesEdge && holeCells.size >= 4) {
                            val holeMask = BooleanArray(subW * subH)
                            for (hc in holeCells) holeMask[hc] = true
                            val holeRing = traceContour(holeMask, subW, subH, baseLimitX, baseLimitY)
                            if (holeRing.size >= 3) {
                                ringsArray.put(polyToJsonArray(holeRing))
                            }
                        }
                    }
                }

                jsonPolygonsWithHoles.put(ringsArray)
            }
        }

        return Pair(jsonPolygonsWithHoles.toString(), Triple(citiesCount, townsCount, communitiesCount))
    }

    private fun traceContour(
        mask: BooleanArray,
        w: Int,
        h: Int,
        limitX0: Int,
        limitY0: Int
    ): List<GeoPoint> {
        var startX = -1
        var startY = -1
        loop@ for (y in 0 until h) {
            for (x in 0 until w) {
                if (mask[y * w + x]) {
                    startX = x
                    startY = y
                    break@loop
                }
            }
        }
        if (startX < 0) return emptyList()

        fun isSet(cx: Int, cy: Int): Boolean {
            return cx in 0 until w && cy in 0 until h && mask[cy * w + cx]
        }

        val rawVertices = ArrayList<Pair<Double, Double>>()
        var vx = startX
        var vy = startY
        var dir = 0
        val dx = intArrayOf(1, 0, -1, 0)
        val dy = intArrayOf(0, 1, 0, -1)

        var safety = 0
        val maxSteps = (w + h) * 10 + 120

        do {
            rawVertices.add(Pair(vx.toDouble(), vy.toDouble()))
            vx += dx[dir]
            vy += dy[dir]

            val nw = isSet(vx - 1, vy - 1)
            val ne = isSet(vx, vy - 1)
            val se = isSet(vx, vy)
            val sw = isSet(vx - 1, vy)

            dir = when (dir) {
                0 -> if (ne) 3 else if (se) 0 else 1
                1 -> if (se) 0 else if (sw) 1 else 2
                2 -> if (sw) 1 else if (nw) 2 else 3
                else -> if (nw) 2 else if (ne) 3 else 0
            }
            safety++
        } while ((vx != startX || vy != startY || dir != 0) && safety < maxSteps)

        if (rawVertices.size < 3) return emptyList()

        val simplified = ArrayList<Pair<Double, Double>>()
        val n = rawVertices.size
        for (i in 0 until n) {
            val prev = rawVertices[(i + n - 1) % n]
            val cur = rawVertices[i]
            val next = rawVertices[(i + 1) % n]
            val cross = (cur.first - prev.first) * (next.second - cur.second) -
                    (cur.second - prev.second) * (next.first - cur.first)
            if (abs(cross) > 1e-4) {
                simplified.add(cur)
            }
        }

        val smoothed = ArrayList<GeoPoint>(simplified.size * 2)
        val m = simplified.size
        for (i in 0 until m) {
            val p0 = simplified[i]
            val p1 = simplified[(i + 1) % m]
            val qx = 0.75 * p0.first + 0.25 * p1.first
            val qy = 0.75 * p0.second + 0.25 * p1.second
            val rx = 0.25 * p0.first + 0.75 * p1.first
            val ry = 0.25 * p0.second + 0.75 * p1.second

            smoothed.add(GeoPoint(tileYToLat(limitY0 + qy, Z_MASK), tileXToLon(limitX0 + qx, Z_MASK)))
            smoothed.add(GeoPoint(tileYToLat(limitY0 + ry, Z_MASK), tileXToLon(limitX0 + rx, Z_MASK)))
        }
        return smoothed
    }

    fun scanPopulatedZonesWithProgress(
        north: Double,
        south: Double,
        east: Double,
        west: Double,
        selectionRings: List<List<GeoPoint>>?,
        hasCustomPolygon: Boolean,
        stateRelationId: Long = 0L,
        onProgress: (Int, String) -> Unit
    ): Result<UrbanScanResult> {
        val s = min(south, north)
        val n = max(south, north)
        val w = min(west, east)
        val e = max(west, east)

        val (bxMin, byMin) = TileMatrixCalculator.deg2num(n, w, Z_MASK)
        val (bxMax, byMax) = TileMatrixCalculator.deg2num(s, e, Z_MASK)
        val limitX0 = min(bxMin, bxMax)
        val limitX1 = max(bxMin, bxMax)
        val limitY0 = min(byMin, byMax)
        val limitY1 = max(byMin, byMax)

        val gridW = (limitX1 - limitX0 + 1).coerceAtLeast(1)
        val gridH = (limitY1 - limitY0 + 1).coerceAtLeast(1)

        val rawGrid = BooleanArray(gridW * gridH)
        val streetHits = ShortArray(gridW * gridH)
        val wayLatBuf = DoubleArray(4096)
        val wayLonBuf = DoubleArray(4096)

        val seenPlaces = AtomicLong(0L)
        val seenLanduse = AtomicLong(0L)
        val seenStreets = AtomicLong(0L)
        var lastSeenName = ""

        val degArea = (n - s) * (e - w)
        val isLocalScale = degArea <= 2.2
        val bboxStr = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", s, w, n, e)

        val streetClauseLocal = if (isLocalScale) {
            "way[\"highway\"~\"^(residential|living_street|pedestrian)$\"]($bboxStr); out geom qt;"
        } else ""

        // BLINDAJE ANTI-502 Y ANTI-TIMEOUT (RIUV):
        // 'out qt;' fuerza a Overpass a escupir los nodos ANTES de procesar los polígonos.
        // Esto mantiene la conexión fluyendo desde el segundo 1 y engaña al cortafuegos para que no la tire por inactividad.
        val queryStage1 = if (stateRelationId > 0L && !isLocalScale) {
            val areaId = 3600000000L + stateRelationId
            """
                [out:json][timeout:180];
                area($areaId)->.st;
                node["place"~"^(city|town|village|hamlet|suburb|quarter|neighbourhood|isolated_dwelling|locality)$"](area.st);
                out qt;
                way["place"~"^(city|town|village|hamlet|suburb|quarter|neighbourhood)$"](area.st);
                out geom qt;
                way["landuse"~"^(residential|commercial|industrial|retail|education|institutional)$"](area.st);
                out geom qt;
            """.trimIndent()
        } else {
            """
                [out:json][timeout:180];
                node["place"~"^(city|town|village|hamlet|suburb|quarter|neighbourhood|isolated_dwelling|locality)$"]($bboxStr);
                out qt;
                way["place"~"^(city|town|village|hamlet|suburb|quarter|neighbourhood)$"]($bboxStr);
                out geom qt;
                way["landuse"~"^(residential|commercial|industrial|retail|education|institutional)$"]($bboxStr);
                out geom qt;
                $streetClauseLocal
            """.trimIndent()
        }

        val fallbackBboxStage1 = """
            [out:json][timeout:180];
            node["place"~"^(city|town|village|hamlet|suburb|quarter|neighbourhood|isolated_dwelling|locality)$"]($bboxStr);
            out qt;
            way["place"~"^(city|town|village|hamlet|suburb|quarter|neighbourhood)$"]($bboxStr);
            out geom qt;
            way["landuse"~"^(residential|commercial|industrial|retail|education|institutional)$"]($bboxStr);
            out geom qt;
        """.trimIndent()

        var parsedStage1 = false
        var stage1UsedMirror = 0
        var lastError = "Fallo de conexión con Overpass."

        for ((mIdx, mirror) in OVERPASS_MIRRORS.withIndex()) {
            val ping = NetworkHealthMonitor.checkInternetPingMs()
            val bytesCounter = AtomicLong(0L)
            val stopWatcher = AtomicBoolean(false)
            val tStart = System.currentTimeMillis()
            val queryToSend = if (mIdx == 2 && stateRelationId > 0L) fallbackBboxStage1 else queryStage1

            val watcher = thread(start = true, isDaemon = true) {
                var silence_seconds = 0
                var last_kb = 0L
                while (!stopWatcher.get()) {
                    val elapsed = (System.currentTimeMillis() - tStart) / 1000.0
                    val kb = bytesCounter.get() / 1024L

                    if (kb == last_kb) silence_seconds++ else silence_seconds = 0
                    last_kb = kb

                    if (kb == 0L) {
                        val curPing = if (elapsed > 4.0) NetworkHealthMonitor.checkInternetPingMs() else ping
                        val msg = if (curPing < 0L) {
                            "[INTERNET INESTABLE / CAÍDO] Esperando reconexión..."
                        } else if (elapsed > 12.0) {
                            "${NetworkHealthMonitor.formatNetTag(curPing)} | OSM está procesando (Tu red o proxy puede retrasarlo)... ${String.format(Locale.US, "%.1f", elapsed)}s"
                        } else {
                            "${NetworkHealthMonitor.formatNetTag(curPing)} | Etapa 1/2: Solicitando poblaciones base (${String.format(Locale.US, "%.1f", elapsed)}s)..."
                        }
                        // La barra se queda en el tope de 30% mientras kilobytes sean 0 para no falsear progreso
                        onProgress(min(30, 8 + (elapsed * 1.5).toInt()), msg)
                    } else {
                        val kbs = (kb / max(0.4, elapsed)).toInt()
                        val nameHint = if (lastSeenName.isNotBlank()) " [$lastSeenName]" else ""
                        val stallMsg = if (silence_seconds > 4) " (Esperando datos de OSM...)" else ""
                        val msg = "[Etapa 1/2: $kb KB a $kbs KB/s]$stallMsg Leyendo$nameHint: ${seenPlaces.get()} pueblos, ${seenLanduse.get()} manchas"
                        val pct = min(60, 30 + (kb / 25).toInt().coerceAtMost(30))
                        onProgress(pct, msg)
                    }
                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }

            var conn: HttpURLConnection? = null
            try {
                val postData = "data=" + URLEncoder.encode(queryToSend, "UTF-8")
                conn = (URL(mirror).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 18000
                    readTimeout = 180000 // 3 Minutos: Aguanta estados gigantes en redes lentas sin cortar
                    setRequestProperty("User-Agent", "ExonedRouter/3.0 (Streaming)")
                    setRequestProperty("Accept-Encoding", "gzip")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                }
                conn.outputStream.use { it.write(postData.toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode == 200) {
                    val counted = CountingInputStream(conn.inputStream, bytesCounter)
                    val stream = if ((conn.contentEncoding ?: "").contains("gzip", true)) {
                        GZIPInputStream(counted)
                    } else counted

                    streamParseUrbanChunk(
                        inputStream = stream,
                        s = s, n = n, w = w, e = e,
                        limitX0 = limitX0, limitY0 = limitY0,
                        gridW = gridW, gridH = gridH,
                        rawGrid = rawGrid,
                        streetHits = streetHits,
                        wayLatBuf = wayLatBuf,
                        wayLonBuf = wayLonBuf,
                        seenPlaces = seenPlaces,
                        seenLanduse = seenLanduse,
                        seenStreets = seenStreets,
                        onPlaceNameSeen = { lastSeenName = it }
                    )
                    stopWatcher.set(true)
                    if (seenPlaces.get() > 0L || seenLanduse.get() > 0L || seenStreets.get() > 0L) {
                        parsedStage1 = true
                        stage1UsedMirror = mIdx
                        break
                    } else {
                        lastError = "Respuesta vacía en Nodo ${mIdx + 1}, probando respaldo..."
                    }
                } else {
                    lastError = "Nodo ${mIdx + 1} ocupado (HTTP ${conn.responseCode})"
                }
            } catch (t: Throwable) {
                val checkPing = NetworkHealthMonitor.checkInternetPingMs()
                lastError = if (checkPing < 0L) {
                    "Tu conexión a Internet se volvió inestable o se desconectó."
                } else {
                    "Servidor OSM Nodo ${mIdx + 1} demoró demasiado (${t.localizedMessage ?: "Timeout"})."
                }
            } finally {
                stopWatcher.set(true)
                watcher.interrupt()
                conn?.disconnect()
            }
        }

        if (!parsedStage1) {
            return Result.failure(Exception(lastError))
        }

        if (isLocalScale) {
            mergeStreetGridIntoUrbanFootprint(rawGrid, streetHits, gridW, gridH)
        }

        // ETAPA 2 ORIGINAL RESTAURADA: 1 Sola consulta de flujo continuo con 'out qt;' intermedio y timeout expandido
        var parsedStage2 = isLocalScale
        if (!isLocalScale) {
            val queryStage2Area = if (stateRelationId > 0L) {
                val areaId = 3600000000L + stateRelationId
                """
                    [out:json][timeout:180];
                    area($areaId)->.st;
                    way["highway"~"^(residential|living_street|pedestrian)$"](area.st);
                    out ids bb qt;
                """.trimIndent()
            } else {
                """
                    [out:json][timeout:180];
                    way["highway"~"^(residential|living_street|pedestrian)$"]($bboxStr);
                    out ids bb qt;
                """.trimIndent()
            }

            val queryStage2BboxFallback = """
                [out:json][timeout:180];
                way["highway"~"^(residential|living_street|pedestrian)$"]($bboxStr);
                out ids bb qt;
            """.trimIndent()

            for (attempt in 0 until OVERPASS_MIRRORS.size * 2) {
                val mIdx = (stage1UsedMirror + 1 + attempt) % OVERPASS_MIRRORS.size
                val mirror = OVERPASS_MIRRORS[mIdx]
                val queryStage2 = if (attempt >= OVERPASS_MIRRORS.size) queryStage2BboxFallback else queryStage2Area

                val bytesCounter = AtomicLong(0L)
                val stopWatcher = AtomicBoolean(false)
                val tStart = System.currentTimeMillis()

                val watcher = thread(start = true, isDaemon = true) {
                    var silence_seconds = 0
                    var last_kb = 0L
                    while (!stopWatcher.get()) {
                        val elapsed = (System.currentTimeMillis() - tStart) / 1000.0
                        val kb = bytesCounter.get() / 1024L

                        if (kb == last_kb) silence_seconds++ else silence_seconds = 0
                        last_kb = kb

                        if (kb == 0L) {
                            onProgress(
                                64,
                                "Etapa 2/2: Vinculando cuadras y calles de pueblos (${String.format(Locale.US, "%.1f", elapsed)}s)..."
                            )
                        } else {
                            val kbs = (kb / max(0.4, elapsed)).toInt()
                            val stallMsg = if (silence_seconds > 4) " (Transfiriendo...)" else ""
                            val pct = min(89, 65 + (kb / 120).toInt().coerceAtMost(24))
                            onProgress(
                                pct,
                                "[Etapa 2/2: $kb KB a $kbs KB/s]$stallMsg Rellenando cuadras: ${seenStreets.get()} calles..."
                            )
                        }
                        try {
                            Thread.sleep(1000)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }

                var conn: HttpURLConnection? = null
                try {
                    val postData = "data=" + URLEncoder.encode(queryStage2, "UTF-8")
                    conn = (URL(mirror).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = 18000
                        readTimeout = 180000 // Aumentado a 3 minutos para estados gigantes como Veracruz
                        setRequestProperty("User-Agent", "ExonedRouter/3.0 (Streaming)")
                        setRequestProperty("Accept-Encoding", "gzip")
                        setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    }
                    conn.outputStream.use { it.write(postData.toByteArray(Charsets.UTF_8)) }

                    if (conn.responseCode == 200) {
                        val counted = CountingInputStream(conn.inputStream, bytesCounter)
                        val stream = if ((conn.contentEncoding ?: "").contains("gzip", true)) {
                            GZIPInputStream(counted)
                        } else counted

                        streamParseUrbanChunk(
                            inputStream = stream,
                            s = s, n = n, w = w, e = e,
                            limitX0 = limitX0, limitY0 = limitY0,
                            gridW = gridW, gridH = gridH,
                            rawGrid = rawGrid,
                            streetHits = streetHits,
                            wayLatBuf = wayLatBuf,
                            wayLonBuf = wayLonBuf,
                            seenPlaces = seenPlaces,
                            seenLanduse = seenLanduse,
                            seenStreets = seenStreets,
                            onPlaceNameSeen = {}
                        )
                        stopWatcher.set(true)
                        if (seenStreets.get() > 0L) {
                            mergeStreetGridIntoUrbanFootprint(rawGrid, streetHits, gridW, gridH)
                            parsedStage2 = true
                            break
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    stopWatcher.set(true)
                    watcher.interrupt()
                    conn?.disconnect()
                }
                try { Thread.sleep(800) } catch (_: Throwable) {}
            }

            if (!parsedStage2 && seenStreets.get() > 0L) {
                mergeStreetGridIntoUrbanFootprint(rawGrid, streetHits, gridW, gridH)
                parsedStage2 = true
            }
        }

        return try {
            onProgress(92, "Perfilando ${seenPlaces.get()} pueblos, ${seenLanduse.get()} manchas y ${seenStreets.get()} cuadras...")
            val tightGrid = consolidateTightUrbanFootprint(rawGrid, gridW, gridH)

            if (hasCustomPolygon && !selectionRings.isNullOrEmpty()) {
                for (gy in 0 until gridH) {
                    val latC = tileYToLat(limitY0 + gy + 0.5, Z_MASK)
                    for (gx in 0 until gridW) {
                        if (!tightGrid[gy * gridW + gx]) continue
                        val lonC = tileXToLon(limitX0 + gx + 0.5, Z_MASK)
                        if (!isPointInAnyRing(latC, lonC, selectionRings)) {
                            tightGrid[gy * gridW + gx] = false
                        }
                    }
                }
            }

            val finalCellsZ16 = HashSet<Long>()
            for (gy in 0 until gridH) {
                for (gx in 0 until gridW) {
                    if (tightGrid[gy * gridW + gx]) {
                        finalCellsZ16.add(packXY(limitX0 + gx, limitY0 + gy))
                    }
                }
            }

            onProgress(96, "Trazando contornos vectoriales de alta precisión...")
            val (polygonsJson, counts) = buildPolygonsFromCells(finalCellsZ16)
            onProgress(100, "Escaneo completado.")

            Result.success(
                UrbanScanResult(
                    z16Cells = finalCellsZ16,
                    citiesCount = counts.first,
                    townsCount = counts.second,
                    communitiesCount = counts.third,
                    polygonsWithHolesJson = polygonsJson,
                    stage2Completed = parsedStage2
                )
            )
        } catch (t: Throwable) {
            Result.failure(Exception(t.localizedMessage ?: "Error consolidando malla"))
        }
    }

    private fun streamParseUrbanChunk(
        inputStream: InputStream,
        s: Double, n: Double, w: Double, e: Double,
        limitX0: Int, limitY0: Int,
        gridW: Int, gridH: Int,
        rawGrid: BooleanArray,
        streetHits: ShortArray,
        wayLatBuf: DoubleArray,
        wayLonBuf: DoubleArray,
        seenPlaces: AtomicLong,
        seenLanduse: AtomicLong,
        seenStreets: AtomicLong,
        onPlaceNameSeen: (String) -> Unit
    ) {
        val reader = android.util.JsonReader(BufferedReader(java.io.InputStreamReader(inputStream, Charsets.UTF_8), 32768))
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "elements") {
                reader.beginArray()
                while (reader.hasNext()) {
                    var elType = ""
                    var nodeLat = Double.NaN
                    var nodeLon = Double.NaN
                    var placeType = ""
                    var placeName = ""
                    var isHighway = false
                    var ptCount = 0
                    var hasBounds = false
                    var bMinLat = Double.NaN
                    var bMinLon = Double.NaN
                    var bMaxLat = Double.NaN
                    var bMaxLon = Double.NaN

                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "type" -> elType = reader.nextString()
                            "lat" -> nodeLat = reader.nextDouble()
                            "lon" -> nodeLon = reader.nextDouble()
                            "bounds" -> {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "minlat" -> bMinLat = reader.nextDouble()
                                        "minlon" -> bMinLon = reader.nextDouble()
                                        "maxlat" -> bMaxLat = reader.nextDouble()
                                        "maxlon" -> bMaxLon = reader.nextDouble()
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                                hasBounds = !bMinLat.isNaN() && !bMinLon.isNaN() && !bMaxLat.isNaN() && !bMaxLon.isNaN()
                            }
                            "tags" -> {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "place" -> placeType = reader.nextString()
                                        "name", "name:es", "name:en" -> placeName = reader.nextString()
                                        "highway" -> {
                                            reader.skipValue()
                                            isHighway = true
                                        }
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                            }
                            "geometry" -> {
                                if (reader.peek() == android.util.JsonToken.NULL) {
                                    reader.nextNull()
                                } else {
                                    reader.beginArray()
                                    while (reader.hasNext()) {
                                        if (reader.peek() == android.util.JsonToken.NULL) {
                                            reader.nextNull()
                                            continue
                                        }
                                        var glat = Double.NaN
                                        var glon = Double.NaN
                                        reader.beginObject()
                                        while (reader.hasNext()) {
                                            when (reader.nextName()) {
                                                "lat" -> glat = reader.nextDouble()
                                                "lon" -> glon = reader.nextDouble()
                                                else -> reader.skipValue()
                                            }
                                        }
                                        reader.endObject()
                                        if (!glat.isNaN() && !glon.isNaN() && ptCount < wayLatBuf.size) {
                                            wayLatBuf[ptCount] = glat
                                            wayLonBuf[ptCount] = glon
                                            ptCount++
                                        }
                                    }
                                    reader.endArray()
                                }
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()

                    if (placeName.isNotBlank()) {
                        onPlaceNameSeen(placeName)
                    }

                    if (elType == "node") {
                        if (!nodeLat.isNaN() && !nodeLon.isNaN() && nodeLat in s..n && nodeLon in w..e) {
                            seenPlaces.incrementAndGet()
                            val radiusM = when (placeType) {
                                "city" -> 1150.0
                                "town" -> 680.0
                                "suburb" -> 450.0
                                "village" -> 380.0
                                "quarter", "neighbourhood" -> 300.0
                                "hamlet" -> 240.0
                                else -> 175.0
                            }
                            stampDiscOnGrid(nodeLat, nodeLon, radiusM, limitX0, limitY0, gridW, gridH, rawGrid)
                        }
                    } else if (elType == "way") {
                        if (ptCount > 0) {
                            if (isHighway) seenStreets.incrementAndGet() else seenLanduse.incrementAndGet()
                            rasterizeWayPrimitive(
                                latBuf = wayLatBuf,
                                lonBuf = wayLonBuf,
                                ptCount = ptCount,
                                isClosedArea = !isHighway,
                                limitX0 = limitX0,
                                limitY0 = limitY0,
                                gridW = gridW,
                                gridH = gridH,
                                grid = rawGrid
                            )
                        } else if (hasBounds) {
                            seenStreets.incrementAndGet()
                            accumulateStreetBoundsHit(
                                minLat = bMinLat,
                                minLon = bMinLon,
                                maxLat = bMaxLat,
                                maxLon = bMaxLon,
                                limitX0 = limitX0,
                                limitY0 = limitY0,
                                gridW = gridW,
                                gridH = gridH,
                                streetHits = streetHits
                            )
                        }
                    }
                }
                reader.endArray()
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        reader.close()
    }

    private fun polyToJsonArray(poly: List<GeoPoint>): JSONArray {
        val arr = JSONArray()
        val step = if (poly.size > 350) (poly.size / 250).coerceAtLeast(1) else 1
        var idx = 0
        while (idx < poly.size) {
            val p = poly[idx]
            val pair = JSONArray()
            pair.put((p.lat * 100000.0).toInt() / 100000.0)
            pair.put((p.lon * 100000.0).toInt() / 100000.0)
            arr.put(pair)
            idx += step
        }
        return arr
    }
}

object TileMatrixCalculator {

    private fun postQoiKbForZoom(z: Int): Double = when (z) {
        in 6..8 -> 3.8
        in 9..11 -> 6.2
        in 12..14 -> 8.5
        in 15..18 -> 11.2
        else -> 13.5
    }

    fun deg2num(latDeg: Double, lonDeg: Double, zoom: Int): Pair<Int, Int> {
        val latRad = Math.toRadians(latDeg.coerceIn(-85.0511, 85.0511))
        val n = 2.0.pow(zoom.toDouble())
        val xTile = ((lonDeg + 180.0) / 360.0 * n).toInt().coerceIn(0, n.toInt() - 1)
        val yTile = ((1.0 - asinh(tan(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, n.toInt() - 1)
        return Pair(xTile, yTile)
    }

    private fun deg2frac(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val latRad = Math.toRadians(latDeg.coerceIn(-85.0511, 85.0511))
        val x = (lonDeg + 180.0) / 360.0
        val y = (1.0 - asinh(tan(latRad)) / PI) / 2.0
        return Pair(x, y)
    }

    private fun multiRingMercatorFillRatio(rings: List<List<GeoPoint>>): Double {
        if (rings.isEmpty()) return 1.0
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var totalPolyArea = 0.0

        for (poly in rings) {
            if (poly.size < 3) continue
            val pts = poly.map { deg2frac(it.lat, it.lon) }
            var shoelace = 0.0
            for (i in pts.indices) {
                val p1 = pts[i]
                val p2 = pts[(i + 1) % pts.size]
                minX = min(minX, p1.first)
                maxX = max(maxX, p1.first)
                minY = min(minY, p1.second)
                maxY = max(maxY, p1.second)
                shoelace += (p1.first * p2.second) - (p2.first * p1.second)
            }
            totalPolyArea += abs(shoelace) * 0.5
        }

        val bboxArea = max(1e-12, (maxX - minX) * (maxY - minY))
        return (totalPolyArea / bboxArea).coerceIn(0.05, 1.0)
    }

    fun evaluateSelection(
        north: Double,
        south: Double,
        east: Double,
        west: Double,
        selectionRings: List<List<GeoPoint>>?,
        hasCustomPolygon: Boolean,
        selectedZooms: Set<Int>,
        includeRoutes: Boolean,
        routeProfileIdx: Int,
        isolateUrbanHighZoom: Boolean = false,
        urbanZ16Cells: Set<Long>? = null
    ): MatrixTelemetry {
        if (hasCustomPolygon && (selectionRings.isNullOrEmpty() || selectionRings.all { it.size < 3 })) {
            return MatrixTelemetry(0L, 0L, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, false)
        }

        val meanLatRad = Math.toRadians((north + south) * 0.5)
        val heightKm = abs(north - south) * 111.132
        val widthKm = abs(east - west) * 111.320 * abs(cos(meanLatRad))
        val fillRatio = if (hasCustomPolygon && !selectionRings.isNullOrEmpty()) {
            multiRingMercatorFillRatio(selectionRings)
        } else 1.0

        val effectiveAreaKm2 = heightKm * widthKm * fillRatio
        val hasValidUrbanMask = isolateUrbanHighZoom && !urbanZ16Cells.isNullOrEmpty()

        val urbanZ15Cells = if (hasValidUrbanMask && urbanZ16Cells != null) {
            val set15 = HashSet<Long>()
            for (c16 in urbanZ16Cells) {
                val x15 = UrbanScannerEngine.unpackX(c16) shr 1
                val y15 = UrbanScannerEngine.unpackY(c16) shr 1
                set15.add(UrbanScannerEngine.packXY(x15, y15))
            }
            set15
        } else emptySet<Long>()

        var accumulatedTiles = 0L
        var unmaskedTiles = 0L
        var accumulatedMapKb = 0.0
        var unmaskedMapKb = 0.0
        var totalClusters = 0L

        for (z in selectedZooms.sorted()) {
            val (xMin, yMin) = deg2num(north, west, z)
            val (xMax, yMax) = deg2num(south, east, z)
            val wTiles = abs(xMax - xMin) + 1L
            val hTiles = abs(yMax - yMin) + 1L
            val bboxTiles = wTiles * hTiles

            val rawZTiles = if (hasCustomPolygon) {
                max(1L, (bboxTiles.toDouble() * (fillRatio * 1.04).coerceAtMost(1.0)).toLong())
            } else {
                bboxTiles
            }

            val cxMin = min(xMin, xMax) / 32
            val cxMax = max(xMin, xMax) / 32
            val cyMin = min(yMin, yMax) / 32
            val cyMax = max(yMin, yMax) / 32
            val rawZClusters = ((cxMax - cxMin + 1L) * (cyMax - cyMin + 1L))

            unmaskedTiles += rawZTiles
            unmaskedMapKb += (rawZTiles * postQoiKbForZoom(z)) + (rawZClusters * 8.0)

            if (z >= 15 && hasValidUrbanMask && urbanZ16Cells != null) {
                val maskedZTiles: Long
                val touchedClusters = HashSet<Long>()

                if (z == 15) {
                    maskedZTiles = min(rawZTiles, urbanZ15Cells.size.toLong())
                    for (c15 in urbanZ15Cells) {
                        val clX = UrbanScannerEngine.unpackX(c15) / 32
                        val clY = UrbanScannerEngine.unpackY(c15) / 32
                        touchedClusters.add(UrbanScannerEngine.packXY(clX, clY))
                    }
                } else {
                    val scaleShift = z - 16
                    val subTilesPerCell = 1L shl (2 * scaleShift)

                    maskedZTiles = min(rawZTiles, urbanZ16Cells.size.toLong() * subTilesPerCell)
                    for (c16 in urbanZ16Cells) {
                        val x16 = UrbanScannerEngine.unpackX(c16)
                        val y16 = UrbanScannerEngine.unpackY(c16)
                        val clX = (x16 shl scaleShift) / 32
                        val clY = (y16 shl scaleShift) / 32
                        touchedClusters.add(UrbanScannerEngine.packXY(clX, clY))
                    }
                }

                val maskedZClusters = touchedClusters.size.toLong()
                accumulatedTiles += maskedZTiles
                accumulatedMapKb += (maskedZTiles * postQoiKbForZoom(z)) + (maskedZClusters * 8.0)
                totalClusters += maskedZClusters
            } else {
                accumulatedTiles += rawZTiles
                accumulatedMapKb += (rawZTiles * postQoiKbForZoom(z)) + (rawZClusters * 8.0)
                totalClusters += rawZClusters
            }
        }

        val mapMb = accumulatedMapKb / 1024.0
        val unmaskedMapMb = unmaskedMapKb / 1024.0

        val routeKbPerKm2 = when (routeProfileIdx) {
            0 -> 1.4
            1 -> 16.5
            else -> 28.0
        }
        val routeMb = if (includeRoutes && effectiveAreaKm2 > 0.0) {
            ((effectiveAreaKm2 * routeKbPerKm2) + 45.0) / 1024.0
        } else {
            0.0
        }

        return MatrixTelemetry(
            totalTiles = accumulatedTiles,
            unmaskedTotalTiles = unmaskedTiles,
            mapWeightMb = mapMb,
            unmaskedMapWeightMb = unmaskedMapMb,
            routeWeightMb = routeMb,
            totalWeightMb = mapMb + routeMb,
            areaKm2 = effectiveAreaKm2,
            clustersCount = totalClusters,
            urbanFilterActive = hasValidUrbanMask
        )
    }
}

class ExonedRouterActivity : AppCompatActivity() {

    private val COLOR_BG = Color.parseColor("#02080A")
    private val COLOR_PANEL = Color.parseColor("#0A1114")
    private val COLOR_CARD = Color.parseColor("#061418")
    private val COLOR_CYAN = Color.parseColor("#00E5FF")
    private val COLOR_CYAN_ALPHA = Color.parseColor("#1400E5FF")
    private val COLOR_GREEN = Color.parseColor("#00FF41")
    private val COLOR_AMBER = Color.parseColor("#FFB300")
    private val COLOR_DARK = Color.parseColor("#061A1E")
    private val COLOR_CRITICO = Color.parseColor("#FF003C")

    private var liveNorth = 19.56
    private var liveSouth = 19.52
    private var liveEast = -96.90
    private var liveWest = -96.94

    private var boundNorth = 19.56
    private var boundSouth = 19.52
    private var boundEast = -96.90
    private var boundWest = -96.94

    private var isStateSelectionMode = false
    private var isPolygonMode = false
    private var polygonTutorialShown = false
    private val polygonVertices = mutableListOf<GeoPoint>()

    private var loadedCountryCode = ""
    private var loadedCountryName = ""
    private val loadedCountryStates = mutableListOf<StateBoundaryInfo>()
    private val selectedStatesList = mutableListOf<StateBoundaryInfo>()
    private var isFetchingCountryStates = false

    private var isolateUrbanHighZoom = true
    private var isScanningUrban = false
    private val scannedUrbanCellsZ16 = HashSet<Long>()
    private var scannedCitiesCount = 0
    private var scannedTownsCount = 0
    private var scannedCommunitiesCount = 0
    private var lastScanStage2Completed = true
    private var manualTownBrushMode = 0

    private var viewportState = 0

    private val activeZooms = mutableSetOf<Int>().apply {
        addAll(6..18)
    }
    private var updatingCheckboxes = false

    @Volatile
    private var selectedProviderIdx = 0
    @Volatile
    private var selectedStyleIdx = 0

    private var includeRoutes = true
    private var selectedRouteProfileIdx = 1

    private val tileRamCache = LruCache<String, ByteArray>(90)

    private lateinit var mapContainer: FrameLayout
    private lateinit var configScroll: ScrollView
    private lateinit var btnRestoreMapBar: Button
    private lateinit var btnExpandMap: Button
    private lateinit var btnMinimizeMap: Button

    private lateinit var webView: WebView
    private lateinit var visorCentral: FrameLayout

    private lateinit var btnMainBBoxMode: Button
    private lateinit var btnMainStateMode: Button
    private lateinit var subBBoxModeRow: LinearLayout
    private lateinit var btnModeBox: Button
    private lateinit var btnModePoly: Button
    private lateinit var polyToolsRow: LinearLayout
    private lateinit var txtPolyHint: TextView

    private lateinit var stateToolsPanel: LinearLayout
    private lateinit var txtStateModeHint: TextView
    private lateinit var pbStateLoading: ProgressBar
    private lateinit var btnResetCountry: Button
    private lateinit var btnRetryStateLoad: Button

    private lateinit var cbMacro: CheckBox
    private lateinit var cbGeneral: CheckBox
    private lateinit var cbCercano: CheckBox
    private lateinit var cbAlta: CheckBox
    private lateinit var cbUltra: CheckBox
    private lateinit var txtManualSummary: TextView

    private lateinit var cbIsolateUrban: CheckBox
    private lateinit var btnScanUrban: Button
    private lateinit var btnClearUrban: Button
    private lateinit var pbUrbanScan: ProgressBar
    private lateinit var txtUrbanProgressPct: TextView
    private lateinit var btnBrushAddTown: Button
    private lateinit var btnBrushEraseTown: Button
    private lateinit var btnSaveAreaPreset: Button
    private lateinit var btnLoadAreaPreset: Button
    private lateinit var txtUrbanStatus: TextView

    private lateinit var spProvider: Spinner
    private lateinit var spStyle: Spinner
    private lateinit var cbPremiumCarto: CheckBox
    private lateinit var txtCartoQuotaInfo: TextView

    private lateinit var cbIncludeRoutes: CheckBox
    private lateinit var spRouteProfile: Spinner
    private lateinit var txtRouteDesc: TextView

    private lateinit var txtStatsTiles: TextView
    private lateinit var txtStatsWeight: TextView
    private lateinit var btnForjar: Button
    private lateinit var btnForjarSoloRutas: Button

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun activeCustomRings(): List<List<GeoPoint>>? {
        return when {
            isStateSelectionMode -> if (selectedStatesList.isNotEmpty()) selectedStatesList.flatMap { it.rings } else null
            isPolygonMode -> if (polygonVertices.size >= 3) listOf(polygonVertices.toList()) else null
            else -> null
        }
    }

    private fun hasActiveCustomPolygon(): Boolean {
        return isStateSelectionMode || isPolygonMode
    }

    private fun recalcularBoundsMultiEstado() {
        if (selectedStatesList.isEmpty()) return
        boundNorth = selectedStatesList.maxOf { it.north }
        boundSouth = selectedStatesList.minOf { it.south }
        boundEast = selectedStatesList.maxOf { it.east }
        boundWest = selectedStatesList.minOf { it.west }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val statusBarId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (statusBarId > 0) resources.getDimensionPixelSize(statusBarId) else dp(28)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
            setPadding(dp(10), statusBarHeight + dp(8), dp(10), dp(10))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }

        val btnBack = Button(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_back")
            setTextColor(Color.BLACK)
            setBackgroundColor(COLOR_CYAN)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 11f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { finish() }
        }

        val txtTitle = TextView(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "hdr_title")
            setTextColor(COLOR_CYAN)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnLinkGuide = Button(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_pc_link")
            setTextColor(Color.BLACK)
            setBackgroundColor(COLOR_AMBER)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 10.5f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(4) }
            setOnClickListener { ExonedForgeUi.mostrarGuiaEnlacePcDialog(this@ExonedRouterActivity, 0) }
        }

        val btnVaultTop = Button(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_vault")
            setTextColor(COLOR_CYAN)
            setBackgroundColor(COLOR_DARK)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 10.5f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { ExonedForgeUi.mostrarBovedaSincronizadaDialog(this@ExonedRouterActivity) }
        }

        headerRow.addView(btnBack)
        headerRow.addView(txtTitle)
        headerRow.addView(btnLinkGuide)
        headerRow.addView(btnVaultTop)

        val mainModeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(5))
        }

        btnMainBBoxMode = Button(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "mode_bbox")
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(4) }
            setOnClickListener { cambiarModoPrincipal(estatal = false) }
        }

        btnMainStateMode = Button(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "mode_state")
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(4) }
            setOnClickListener { cambiarModoPrincipal(estatal = true) }
        }

        mainModeRow.addView(btnMainBBoxMode)
        mainModeRow.addView(btnMainStateMode)

        subBBoxModeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }

        btnModeBox = Button(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "mode_box_fixed")
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f).apply { rightMargin = dp(4) }
            setOnClickListener { cambiarModoSeleccionBBox(poligono = false) }
        }

        btnModePoly = Button(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "mode_poly_sas")
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f).apply { leftMargin = dp(4) }
            setOnClickListener { cambiarModoSeleccionBBox(poligono = true) }
        }

        subBBoxModeRow.addView(btnModeBox)
        subBBoxModeRow.addView(btnModePoly)

        polyToolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, 0, 0, dp(6))
        }

        txtPolyHint = TextView(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "poly_hint_init")
            setTextColor(COLOR_AMBER)
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnUndoPt = Button(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_undo")
            textSize = 10f
            setBackgroundColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            setOnClickListener { webView.evaluateJavascript("undoLastVertex();", null) }
        }

        val btnClearPoly = Button(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_clear")
            textSize = 10f
            setBackgroundColor(COLOR_CRITICO)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(5) }
            setOnClickListener { webView.evaluateJavascript("clearPolygon();", null) }
        }

        val btnHelpPoly = Button(this).apply {
            text = "?"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(COLOR_AMBER)
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(5)
            }
            setOnClickListener { mostrarInstruccionesPoligono() }
        }

        polyToolsRow.addView(txtPolyHint)
        polyToolsRow.addView(btnUndoPt)
        polyToolsRow.addView(btnClearPoly)
        polyToolsRow.addView(btnHelpPoly)

        stateToolsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#081317"))
                setStroke(dp(1), COLOR_CYAN)
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }

        val stateTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        txtStateModeHint = TextView(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "state_hint_step1")
            setTextColor(COLOR_AMBER)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnRetryStateLoad = Button(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_retry")
            textSize = 10f
            setBackgroundColor(COLOR_AMBER)
            setTextColor(Color.BLACK)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(30)
            ).apply { leftMargin = dp(6) }
        }

        btnResetCountry = Button(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_reset_country")
            textSize = 10f
            setBackgroundColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(30)
            ).apply { leftMargin = dp(6) }
            setOnClickListener { reiniciarSeleccionPais() }
        }

        stateTopRow.addView(txtStateModeHint)
        stateTopRow.addView(btnRetryStateLoad)
        stateTopRow.addView(btnResetCountry)

        pbStateLoading = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply {
                topMargin = dp(4)
            }
        }

        stateToolsPanel.addView(stateTopRow)
        stateToolsPanel.addView(pbStateLoading)

        btnRestoreMapBar = Button(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_show_map")
            setBackgroundColor(COLOR_DARK)
            setTextColor(COLOR_CYAN)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 11.5f
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply {
                bottomMargin = dp(6)
            }
            setOnClickListener { aplicarEstadoViewport(0) }
        }

        mapContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240))
            background = GradientDrawable().apply {
                setColor(COLOR_DARK)
                setStroke(dp(1), COLOR_CYAN)
                cornerRadius = dp(8).toFloat()
            }
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(COLOR_BG)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(MapBridge(), "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    sincronizarCapaMapaWeb()
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                    if (reqUrl.startsWith("https://exosys.tile/")) {
                        return procesarMosaicoConShaderNativo(request.url)
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
            loadDataWithBaseURL("https://exosys.local/", buildLeafletHtml(), "text/html", "UTF-8", null)
        }

        visorCentral = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(220), dp(155), Gravity.CENTER)
            background = GradientDrawable().apply {
                setColor(COLOR_CYAN_ALPHA)
                setStroke(dp(2), COLOR_CYAN)
            }
            isClickable = false
            isFocusable = false
        }

        val miraCentral = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(6), dp(6), Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_CYAN)
            }
        }
        visorCentral.addView(miraCentral)

        val floatingControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                rightMargin = dp(8)
                bottomMargin = dp(8)
            }
        }

        btnMinimizeMap = Button(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_hide_map")
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC0A1114"))
                setStroke(dp(1), COLOR_CYAN)
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)).apply {
                rightMargin = dp(6)
            }
            setOnClickListener { aplicarEstadoViewport(2) }
        }

        btnExpandMap = Button(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_expand")
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D9FF2A85"))
                setStroke(dp(1), Color.WHITE)
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30))
            setOnClickListener {
                if (viewportState == 1) aplicarEstadoViewport(0) else aplicarEstadoViewport(1)
            }
        }

        floatingControls.addView(btnMinimizeMap)
        floatingControls.addView(btnExpandMap)

        mapContainer.addView(webView)
        mapContainer.addView(visorCentral)
        mapContainer.addView(floatingControls)

        configScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
        }

        val configContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val cardEstilo = crearTarjetaSeccion(ExonedRouterI18n.tr(this, "sec1_title"))

        val lblProv = TextView(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "lbl_prov")
            setTextColor(Color.LTGRAY)
            textSize = 12f
        }

        val proveedores = listOf(
            ExonedRouterI18n.tr(this, "prov_google"),
            ExonedRouterI18n.tr(this, "prov_carto")
        )
        spProvider = crearSpinner(proveedores, 0) { idx ->
            selectedProviderIdx = idx
            spStyle.isEnabled = (idx == 0)
            sincronizarCapaMapaWeb()
            actualizarTelemetria()
        }

        val lblShader = TextView(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "lbl_shader")
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        }

        val estilos = listOf(
            ExonedRouterI18n.tr(this, "style_v1"),
            ExonedRouterI18n.tr(this, "style_v2"),
            ExonedRouterI18n.tr(this, "style_v3"),
            ExonedRouterI18n.tr(this, "style_v4_pipboy"),
            ExonedRouterI18n.tr(this, "style_sat"),
            ExonedRouterI18n.tr(this, "style_day")
        )
        spStyle = crearSpinner(estilos, 0) { idx ->
            selectedStyleIdx = when (idx) {
                3 -> 5 // Fallout V4: Pip-Boy 3000 CRT
                4 -> 3 // Google Satelital Híbrido
                5 -> 4 // Google Clásico Día
                else -> idx
            }
            tileRamCache.evictAll()
            sincronizarCapaMapaWeb()
        }

        cardEstilo.addView(lblProv)
        cardEstilo.addView(spProvider)
        cardEstilo.addView(lblShader)
        cardEstilo.addView(spStyle)

        val idxLang = mapOf(
            "es" to 0, "en" to 1, "zh" to 2, "ja" to 3,
            "hi" to 4, "fr" to 5, "ar" to 6, "ru" to 7
        )[ExonedForgeI18n.getLang(this)] ?: 0

        val cardZoom = crearTarjetaSeccion(ExonedRouterI18n.tr(this, "sec2_title"))

        val txtZoomWarning = TextView(this).apply {
            text = arrayOf(
                "Aviso: A mayor resolución, el peso aumenta mucho más rápido. Se aconseja elegir menor resolución para áreas grandes, y usar Alta/Ultra Resolución solo en zonas específicas.",
                "Notice: At higher resolution, file size increases much faster. It is advised to choose lower resolution for large areas, and use High/Ultra Resolution only in specific zones.",
                "提示：分辨率越高，文件体积增长越快。建议大范围区域选择较低分辨率，仅在特定区域使用高/超高分辨率。",
                "注意：解像度が高いほど、容量は急激に増加します。広範囲には低解像度を選び、高/超高解像度は特定のエリアにのみ使用することを推奨します。",
                "सूचना: उच्च रिज़ॉल्यूशन पर फ़ाइल का आकार बहुत तेज़ी से बढ़ता है। बड़े क्षेत्रों के लिए कम रिज़ॉल्यूशन चुनने और केवल विशिष्ट क्षेत्रों में ही हाई/अल्ट्रा रिज़ॉल्यूशन का उपयोग करने की सलाह दी जाती है।",
                "Avis : À haute résolution, le poids augmente beaucoup plus vite. Il est conseillé de choisir une résolution plus basse pour les grandes zones, et d'utiliser la Haute/Ultra Résolution uniquement sur des zones précises.",
                "تنبيه: كلما زادت الدقة، زاد حجم الملف بشكل أسرع بكثير. يُنصح باختيار دقة أقل للمناطق الكبيرة، واستخدام الدقة العالية/الفائقة في مناطق محددة فقط.",
                "Внимание: При высоком разрешении вес растёт намного быстрее. Рекомендуется выбирать меньшее разрешение для больших зон, а Высокое/Ультра разрешение использовать только на отдельных участках."
            )[idxLang]
            setTextColor(COLOR_AMBER)
            textSize = 11.5f
            setLineSpacing(0f, 1.15f)
            setPadding(0, 0, 0, dp(8))
        }
        cardZoom.addView(txtZoomWarning)

        cbMacro = crearCheckboxPerfil(
            titulo = arrayOf(
                "Vista Continental (Macro) [Z6 - Z8]",
                "Continental View (Macro) [Z6 - Z8]",
                "大陆视图 (宏观) [Z6 - Z8]",
                "大陸ビュー (マクロ) [Z6 - Z8]",
                "महाद्वीपीय दृश्य (मैक्रो) [Z6 - Z8]",
                "Vue Continentale (Macro) [Z6 - Z8]",
                "عرض قاري (ماكرو) [Z6 - Z8]",
                "Континентальный вид (Макро) [Z6 - Z8]"
            )[idxLang],
            subtitulo = arrayOf(
                "Para ver el mundo, países y siluetas generales.",
                "To view the world, countries, and general outlines.",
                "用于查看世界、国家及整体轮廓。",
                "世界、国、全体のシルエットを表示します。",
                "दुनिया, देशों और सामान्य रूपरेखाओं को देखने के लिए।",
                "Pour voir le monde, les pays et les silhouettes générales.",
                "لعرض العالم والدول والملامح العامة.",
                "Для обзора мира, стран и общих очертаний."
            )[idxLang],
            checkedInicial = true,
            rango = 6..8
        )
        cbGeneral = crearCheckboxPerfil(
            titulo = arrayOf(
                "Radar de Carreteras (General) [Z9 - Z11]",
                "Highway Radar (General) [Z9 - Z11]",
                "公路雷达 (常规) [Z9 - Z11]",
                "幹線道路レーダー (一般) [Z9 - Z11]",
                "राजमार्ग रडार (सामान्य) [Z9 - Z11]",
                "Radar Routier (Général) [Z9 - Z11]",
                "رادار الطرق السريعة (عام) [Z9 - Z11]",
                "Радар трасс (Общий) [Z9 - Z11]"
            )[idxLang],
            subtitulo = arrayOf(
                "Para carreteras o caminos vistos desde lejos.",
                "For highways or roads viewed from afar.",
                "用于从远距离查看高速公路或主干道。",
                "遠方から見た幹線道路や道を表示します。",
                "दूर से देखे जाने वाले राजमार्गों या सड़कों के लिए।",
                "Pour les autoroutes ou routes vues de loin.",
                "للطرق السريعة أو الطرق المرئية من بعيد.",
                "Для автомобильных трасс и дорог, видимых издалека."
            )[idxLang],
            checkedInicial = true,
            rango = 9..11
        )
        cbCercano = crearCheckboxPerfil(
            titulo = arrayOf(
                "Reconocimiento Urbano (Cercano) [Z12 - Z14]",
                "Urban Recon (Close) [Z12 - Z14]",
                "城市侦察 (近距) [Z12 - Z14]",
                "都市偵察 (近距離) [Z12 - Z14]",
                "शहरी टोही (निकट) [Z12 - Z14]",
                "Reconnaissance Urbaine (Proche) [Z12 - Z14]",
                "استطلاع حضري (قريب) [Z12 - Z14]",
                "Городская разведка (Близко) [Z12 - Z14]"
            )[idxLang],
            subtitulo = arrayOf(
                "Para diferenciar entre puntos clave y colonias de una ciudad.",
                "To distinguish between key points and neighborhoods of a city.",
                "用于区分城市中的关键地点与各个街区。",
                "都市の重要拠点と地区・町丁を識別します。",
                "शहर के प्रमुख बिंदुओं और कॉलोनियों/मोहल्लों के बीच अंतर करने के लिए।",
                "Pour différencier les points clés et les quartiers d'une ville.",
                "للتمييز بين النقاط الرئيسية والأحياء السكنية في المدينة.",
                "Для различения ключевых точек и районов города."
            )[idxLang],
            checkedInicial = true,
            rango = 12..14
        )
        cbAlta = crearCheckboxPerfil(
            titulo = arrayOf(
                "Trazado de Calles (Alta Resolución) [Z15 - Z18]",
                "Street Layout (High Resolution) [Z15 - Z18]",
                "街道布局 (高分辨率) [Z15 - Z18]",
                "街路詳細 (高解像度) [Z15 - Z18]",
                "सड़कों का खाका (उच्च रिज़ॉल्यूशन) [Z15 - Z18]",
                "Tracé des Rues (Haute Résolution) [Z15 - Z18]",
                "تخطيط الشوارع (دقة عالية) [Z15 - Z18]",
                "Сетка улиц (Высокое разрешение) [Z15 - Z18]"
            )[idxLang],
            subtitulo = arrayOf(
                "Para poder saber la calle exacta en la que estás.",
                "To know the exact street you are standing on.",
                "用于精确识别您当前所在的街道名称。",
                "現在いる正確な通りを把握できるようにします。",
                "यह जानने के लिए कि आप ठीक किस गली या सड़क पर हैं।",
                "Pour connaître la rue exacte dans laquelle vous vous trouvez.",
                "لتتمكن من معرفة الشارع الدقيق الذي تتواجد فيه.",
                "Чтобы точно знать улицу, на которой вы находитесь."
            )[idxLang],
            checkedInicial = true,
            rango = 15..18
        )
        cbUltra = crearCheckboxPerfil(
            titulo = arrayOf(
                "Precisión Milimétrica (Ultra Resolución) [Z19 - Z20]",
                "Pinpoint Precision (Ultra Resolution) [Z19 - Z20]",
                "毫米级精度 (超高分辨率) [Z19 - Z20]",
                "ミリ単位の精度 (超高解像度) [Z19 - Z20]",
                "मिलीमीटर सटीकता (अल्ट्रा रिज़ॉल्यूशन) [Z19 - Z20]",
                "Précision Millimétrique (Ultra Résolution) [Z19 - Z20]",
                "دقة مليمترية (دقة فائقة) [Z19 - Z20]",
                "Миллиметровая точность (Ультра разрешение) [Z19 - Z20]"
            )[idxLang],
            subtitulo = arrayOf(
                "Para ubicar con exactitud de centímetros tu posición.",
                "To pinpoint your position with centimeter accuracy.",
                "用于以厘米级精度定位您的确切位置。",
                "センチメートル単位の精度で現在位置を特定します。",
                "सेंटीमीटर की सटीकता के साथ अपनी स्थिति का पता लगाने के लिए।",
                "Pour localiser votre position avec une précision au centimètre près.",
                "لتحديد موقعك بدقة تصل إلى السنتيمترات.",
                "Для определения вашей позиции с точностью до сантиметров."
            )[idxLang],
            checkedInicial = false,
            rango = 19..20
        )

        cardZoom.addView(cbMacro)
        cardZoom.addView(cbGeneral)
        cardZoom.addView(cbCercano)
        cardZoom.addView(cbAlta)
        cardZoom.addView(cbUltra)

        txtManualSummary = TextView(this).apply {
            text = arrayOf(
                "Niveles activos: Z6 a Z18",
                "Active levels: Z6 to Z18",
                "当前激活级别：Z6 至 Z18",
                "有効レベル: Z6 〜 Z18",
                "सक्रिय स्तर: Z6 से Z18",
                "Niveaux actifs : Z6 à Z18",
                "المستويات النشطة: Z6 إلى Z18",
                "Активные уровни: Z6 — Z18"
            )[idxLang]
            setTextColor(COLOR_CYAN)
            textSize = 11.5f
            setPadding(0, dp(6), 0, dp(6))
        }

        val btnManualZoom = Button(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_manual_z")
            setBackgroundColor(COLOR_DARK)
            setTextColor(COLOR_CYAN)
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { mostrarDialogoZoomManual() }
        }

        cardZoom.addView(txtManualSummary)
        cardZoom.addView(btnManualZoom)

        val urbanBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#041014"))
                setStroke(dp(1), COLOR_GREEN)
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        cbIsolateUrban = CheckBox(this).apply {
            text = arrayOf(
                "Aislar solo zonas pobladas en Z15-Z20 (Filtro Anti-Monte)\n   -> Delimita ciudades, pueblos y rancherías dejando fuera el monte intermedio.",
                "Isolate only populated zones in Z15-Z20 (Anti-Wilderness Filter)\n   -> Outlines cities, towns, and settlements while leaving out empty wilderness in between.",
                "仅保留 Z15-Z20 的居民区（防荒野过滤）\n   -> 精准圈出城市、城镇与村庄，剔除中间的无用荒野。",
                "Z15-Z20の市街地のみを抽出（山林除外フィルター）\n   -> 都市・町・集落のみを囲み、中間の山林や無人地帯を除外します。",
                "Z15-Z20 में केवल आबादी वाले क्षेत्रों को अलग करें (एंटी-जंगल फ़िल्टर)\n   -> बीच के खाली जंगल को छोड़कर शहरों, कस्बों और गांवों की सीमा तय करता है।",
                "Isoler uniquement les zones peuplées en Z15-Z20 (Filtre Anti-Brousse)\n   -> Délimite les villes, villages et hameaux en excluant les terres sauvages intermédiaires.",
                "عزل المناطق المأهولة فقط في Z15-Z20 (فلتر استبعاد البراري)\n   -> يحدد المدن والبلدات والقرى مع استبعاد المناطق البرية الفارغة بينها.",
                "Изолировать только населённые зоны на Z15-Z20 (Фильтр Анти-Лес)\n   -> Выделяет города, посёлки и деревни, отсекая пустые леса и поля между ними."
            )[idxLang]
            setTextColor(COLOR_GREEN)
            textSize = 11.5f
            isChecked = true
            setOnCheckedChangeListener { _, checked ->
                isolateUrbanHighZoom = checked
                actualizarTelemetria()
            }
        }

        val scanBtnsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(4))
        }

        btnScanUrban = Button(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_scan_urban")
            setBackgroundColor(COLOR_GREEN)
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
            setOnClickListener { ejecutarEscaneoPoblaciones() }
        }

        btnClearUrban = Button(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_clear")
            setBackgroundColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            textSize = 10.5f
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36)
            ).apply { leftMargin = dp(6) }
            setOnClickListener { limpiarMascaraUrbana() }
        }

        scanBtnsRow.addView(btnScanUrban)
        scanBtnsRow.addView(btnClearUrban)

        pbUrbanScan = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply {
                topMargin = dp(4)
            }
        }

        txtUrbanProgressPct = TextView(this).apply {
            text = ""
            setTextColor(COLOR_CYAN)
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
            setPadding(0, dp(2), 0, dp(4))
        }

        val brushRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }

        btnBrushAddTown = Button(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_brush_add")
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(COLOR_DARK)
            setTextColor(COLOR_GREEN)
            layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f).apply { rightMargin = dp(4) }
            setOnClickListener { alternarPincelPueblo(1) }
        }

        btnBrushEraseTown = Button(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_brush_del")
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(COLOR_DARK)
            setTextColor(COLOR_AMBER)
            layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f).apply { leftMargin = dp(4) }
            setOnClickListener { alternarPincelPueblo(2) }
        }

        brushRow.addView(btnBrushAddTown)
        brushRow.addView(btnBrushEraseTown)

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }

        btnSaveAreaPreset = Button(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_save_preset")
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(COLOR_CYAN)
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(4) }
            setOnClickListener { mostrarDialogoGuardarSeleccion() }
        }

        btnLoadAreaPreset = Button(this).apply {
            text = "${ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_my_areas")} (${AreaPresetStorage.listPresets(this@ExonedRouterActivity).size})"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(COLOR_DARK)
            setTextColor(COLOR_CYAN)
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply { leftMargin = dp(4) }
            setOnClickListener { mostrarDialogoMisAreasGuardadas() }
        }

        presetRow.addView(btnSaveAreaPreset)
        presetRow.addView(btnLoadAreaPreset)

        txtUrbanStatus = TextView(this).apply {
            text = arrayOf(
                "Sin escanear aún. Puedes seleccionar 1 o múltiples estados, añadir pueblos con '+' y guardar tu selección.",
                "Not scanned yet. You can select 1 or multiple states, add towns with '+', and save your selection.",
                "尚未扫描。您可以选择 1 个或多个州/省，使用 '+' 手动添加城镇并保存选区。",
                "未スキャンです。1つまたは複数の州を選択し、「+」で町を追加して選択範囲を保存できます。",
                "अभी तक स्कैन नहीं किया गया। आप 1 या अधिक राज्य चुन सकते हैं, '+' से कस्बे जोड़ सकते हैं और अपना चयन सहेज सकते हैं।",
                "Pas encore scanné. Vous pouvez sélectionner 1 ou plusieurs états, ajouter des villages avec '+' et sauvegarder votre sélection.",
                "لم يتم المسح بعد. يمكنك تحديد ولاية أو عدة ولايات، وإضافة بلدات بـ '+' وحفظ تحديدك.",
                "Ещё не сканировалось. Выберите 1 или несколько регионов, добавьте посёлки кнопкой '+' и сохраните выбор."
            )[idxLang]
            setTextColor(Color.LTGRAY)
            textSize = 11f
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(4), 0, 0)
        }

        urbanBox.addView(cbIsolateUrban)
        urbanBox.addView(scanBtnsRow)
        urbanBox.addView(pbUrbanScan)
        urbanBox.addView(txtUrbanProgressPct)
        urbanBox.addView(brushRow)
        urbanBox.addView(presetRow)
        urbanBox.addView(txtUrbanStatus)
        cardZoom.addView(urbanBox)

        val cardRutas = crearTarjetaSeccion(ExonedRouterI18n.tr(this, "sec3_title"))

        cbIncludeRoutes = CheckBox(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "cb_include_routes")
            setTextColor(COLOR_GREEN)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 13f
            isChecked = true
            setOnCheckedChangeListener { _, isChecked ->
                includeRoutes = isChecked
                spRouteProfile.visibility = if (isChecked) View.VISIBLE else View.GONE
                txtRouteDesc.visibility = if (isChecked) View.VISIBLE else View.GONE
                actualizarTelemetria()
            }
        }

        val routeDescs = arrayOf(
            arrayOf(
                "Incluye autopistas, carreteras federales y vías rápidas principales entre ciudades. Muy ligero.",
                "Includes freeways, federal highways, and main expressways between cities. Very lightweight.",
                "包含高速公路、国道及城际主要快速干道。体积非常小。",
                "高速道路、国道、都市間の主要幹線道路を含みます。非常に軽量です。",
                "इसमें शहरों के बीच एक्सप्रेसवे, राष्ट्रीय राजमार्ग और मुख्य तेज़ मार्ग शामिल हैं। बहुत हल्का।",
                "Inclut les autoroutes, routes nationales et voies rapides principales entre les villes. Très léger.",
                "يشمل الطرق السريعة والطرق الفيدرالية والشرايين الرئيسية بين المدن. خفيف جدًا.",
                "Включает автомагистрали, федеральные трассы и главные дороги между городами. Очень лёгкий."
            ),
            arrayOf(
                "Incluye todas las calles, avenidas y retornos de la ciudad para navegación urbana completa.",
                "Includes all city streets, avenues, and turnarounds for complete urban navigation.",
                "包含城市内所有街道、大道和掉头处，支持完整的城市导航。",
                "完全な都市ナビゲーションのために、市内のすべての通り、大通り、Uターン路を含みます。",
                "पूर्ण शहरी नेविगेशन के लिए शहर की सभी गलियाँ, मुख्य सड़कें और यू-टर्न शामिल हैं।",
                "Inclut toutes les rues, avenues et demi-tours de la ville pour une navigation urbaine complète.",
                "يشمل جميع شوارع المدينة والجادات ونقاط الالتفاف للملاحة الحضرية الكاملة.",
                "Включает все городские улицы, проспекты и развороты для полноценной городской навигации."
            ),
            arrayOf(
                "Incluye calles más puentes peatonales, escaleras, senderos de cerro, parques y pasillos.",
                "Includes streets plus footbridges, stairs, mountain trails, parks, and walkways.",
                "包含所有街道，外加人行天桥、阶梯、山间小径、公园步道与走廊。",
                "街路に加え、歩道橋、階段、登山道、公園の遊歩道や通路まですべて含みます。",
                "इसमें सड़कों के साथ पैदल पुल, सीढ़ियाँ, पहाड़ी पगडंडियाँ, पार्क और गलियारे शामिल हैं।",
                "Inclut les rues ainsi que les passerelles piétonnes, escaliers, sentiers de montagne, parcs et allées.",
                "يشمل الشوارع بالإضافة إلى جسور المشاة والسلالم ومسارات الجبال والحدائق والممرات.",
                "Включает улицы, а также пешеходные мосты, лестницы, горные тропы, парки и переулки."
            )
        )

        val perfilesRuta = listOf(
            ExonedRouterI18n.tr(this, "rprof_0"),
            ExonedRouterI18n.tr(this, "rprof_1"),
            ExonedRouterI18n.tr(this, "rprof_2")
        )
        spRouteProfile = crearSpinner(perfilesRuta, 1) { idx ->
            selectedRouteProfileIdx = idx
            val curLangIdx = mapOf(
                "es" to 0, "en" to 1, "zh" to 2, "ja" to 3,
                "hi" to 4, "fr" to 5, "ar" to 6, "ru" to 7
            )[ExonedForgeI18n.getLang(this@ExonedRouterActivity)] ?: 0
            txtRouteDesc.text = routeDescs[idx.coerceIn(0, 2)][curLangIdx]
            actualizarTelemetria()
        }

        txtRouteDesc = TextView(this).apply {
            text = routeDescs[1][idxLang]
            setTextColor(Color.LTGRAY)
            textSize = 11.5f
            setPadding(0, dp(6), 0, dp(0))
        }

        cardRutas.addView(cbIncludeRoutes)
        cardRutas.addView(spRouteProfile)
        cardRutas.addView(txtRouteDesc)

        val cardCartoLimit = crearTarjetaSeccion(ExonedRouterI18n.tr(this, "sec4_title"))

        txtCartoQuotaInfo = TextView(this).apply {
            text = arrayOf(
                "Nota importante: El proveedor Carto cobra tras superar los 5,000,000 de sectores al mes. En modo GM con respaldo Carto el techo seguro se duplica a 9,500,000 sectores.",
                "Important note: The Carto provider charges after exceeding 5,000,000 sectors per month. In GM mode with Carto fallback, the safe ceiling doubles to 9,500,000 sectors.",
                "重要提示：Carto 提供商在每月超过 5,000,000 个扇区后将产生费用。在带 Carto 备份的 GM 模式下，安全上限翻倍至 9,500,000 个扇区。",
                "重要なお知らせ：Cartoプロバイダーは月間5,000,000セクターを超えると課金されます。Cartoバックアップ付きGMモードでは、安全上限が9,500,000セクターに倍増します。",
                "महत्वपूर्ण नोट: Carto प्रदाता प्रति माह 5,000,000 सेक्टर पार करने के बाद शुल्क लेता है। Carto बैकअप के साथ GM मोड में सुरक्षित सीमा दोगुनी होकर 9,500,000 सेक्टर हो जाती है।",
                "Note importante : Le fournisseur Carto facture au-delà de 5 000 000 de secteurs par mois. En mode GM avec secours Carto, le plafond de sécurité double à 9 500 000 secteurs.",
                "ملاحظة هامة: يفرض مزود Carto رسومًا بعد تجاوز 5,000,000 قطاع شهريًا. في وضع GM مع دعم Carto الاحتياطي، يتضاعف السقف الآمن إلى 9,500,000 قطاع.",
                "Важное примечание: Провайдер Carto взимает плату после превышения 5,000,000 секторов в месяц. В режиме GM с резервом Carto безопасный лимит удваивается до 9,500,000 секторов."
            )[idxLang]
            setTextColor(Color.LTGRAY)
            textSize = 11.5f
            setLineSpacing(0f, 1.15f)
        }

        cbPremiumCarto = CheckBox(this).apply {
            text = arrayOf(
                "Tengo cuenta Premium de Carto (Desbloquear límite mensual)",
                "I have a Carto Premium account (Unlock monthly limit)",
                "我拥有 Carto 高级账户（解锁每月配额限制）",
                "Cartoプレミアムアカウントを所有（月間制限を解除）",
                "मेरे पास Carto प्रीमियम खाता है (मासिक सीमा अनलॉक करें)",
                "J'ai un compte Carto Premium (Débloquer la limite mensuelle)",
                "لدي حساب Carto Premium (إلغاء قفل الحد الشهري)",
                "У меня есть аккаунт Carto Premium (Снять месячный лимит)"
            )[idxLang]
            setTextColor(COLOR_CYAN)
            textSize = 12f
            isChecked = CartoApiManager.isPremiumAccount(this@ExonedRouterActivity)
            setOnCheckedChangeListener { _, isChecked ->
                CartoApiManager.setPremiumAccount(this@ExonedRouterActivity, isChecked)
                actualizarTelemetria()
            }
        }

        cardCartoLimit.addView(txtCartoQuotaInfo)
        cardCartoLimit.addView(cbPremiumCarto)

        configContent.addView(cardEstilo)
        configContent.addView(cardZoom)
        configContent.addView(cardRutas)
        configContent.addView(cardCartoLimit)
        configScroll.addView(configContent)

        val bottomTelemetryPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(COLOR_PANEL)
                setStroke(dp(1), COLOR_CYAN)
                cornerRadius = dp(8).toFloat()
            }
        }

        txtStatsTiles = TextView(this).apply {
            text = arrayOf(
                "SECTORES: CALCULANDO...",
                "SECTORS: CALCULATING...",
                "扇区：计算中...",
                "セクター: 計算中...",
                "सेक्टर: गणना हो रही है...",
                "SECTEURS : CALCUL EN COURS...",
                "القطاعات: جارٍ الحساب...",
                "СЕКТОРЫ: ВЫЧИСЛЕНИЕ..."
            )[idxLang]
            setTextColor(COLOR_CYAN)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        txtStatsWeight = TextView(this).apply {
            text = arrayOf(
                "PESO POST-QOI: 0.00 MB",
                "POST-QOI WEIGHT: 0.00 MB",
                "POST-QOI 重量：0.00 MB",
                "POST-QOI 容量: 0.00 MB",
                "POST-QOI वज़न: 0.00 MB",
                "POIDS POST-QOI : 0.00 MB",
                "الوزن بعد QOI: 0.00 MB",
                "ВЕС ПОСЛЕ QOI: 0.00 MB"
            )[idxLang]
            setTextColor(COLOR_GREEN)
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(8))
        }

        val actionButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        btnForjar = Button(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_forge_map")
            setTextColor(Color.BLACK)
            setBackgroundColor(COLOR_CYAN)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 11.5f
            setPadding(dp(8), dp(10), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.35f).apply {
                rightMargin = dp(5)
            }
            setOnClickListener { confirmarYProcederCompilacion(routesOnly = false) }
        }

        btnForjarSoloRutas = Button(this).apply {
            text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_forge_routes")
            setTextColor(Color.BLACK)
            setBackgroundColor(COLOR_AMBER)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 11f
            setPadding(dp(8), dp(10), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { confirmarYProcederCompilacion(routesOnly = true) }
        }

        actionButtonsRow.addView(btnForjar)
        actionButtonsRow.addView(btnForjarSoloRutas)

        bottomTelemetryPanel.addView(txtStatsTiles)
        bottomTelemetryPanel.addView(txtStatsWeight)
        bottomTelemetryPanel.addView(actionButtonsRow)

        root.addView(headerRow)
        root.addView(mainModeRow)
        root.addView(subBBoxModeRow)
        root.addView(polyToolsRow)
        root.addView(stateToolsPanel)
        root.addView(btnRestoreMapBar)
        root.addView(mapContainer)
        root.addView(configScroll)
        root.addView(bottomTelemetryPanel)
        setContentView(root)

        cambiarModoPrincipal(estatal = false)
        actualizarResumenNiveles()
        actualizarTelemetria()
    }

    // =========================================================================
    // DIÁLOGO DE ADVERTENCIA TÁCTICA MULTI-ESTADO (AL SELECCIONAR 2+ ESTADOS)
    // =========================================================================
    private fun mostrarDialogoConfirmarMultiEstado(nuevoEstado: StateBoundaryInfo) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply {
                setColor(COLOR_PANEL)
                setStroke(dp(2), COLOR_AMBER)
                cornerRadius = dp(10).toFloat()
            }
        }

        val title = TextView(this).apply {
            text = "¿ESTÁS SEGURO DE AGREGAR OTRO ESTADO?"
            setTextColor(COLOR_AMBER)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
        }

        val actualesStr = selectedStatesList.joinToString(", ") { it.name }
        val nuevoTotal = selectedStatesList.size + 1

        val body = TextView(this).apply {
            text = "Estás por añadir '${nuevoEstado.name}' a tu selección ($actualesStr) para un total de $nuevoTotal estados.\n\n" +
                    "ADVERTENCIAS DE INGENIERÍA:\n" +
                    "• SE NECESITA UNA PC OBLIGATORIAMENTE: Seleccionar múltiples estados multiplica exponencialmente los sectores y el tamaño del grafo vial. La descarga directa en el teléfono se desactivará y deberás enviar la orden a la aplicación de PC (Exosys PC Forge).\n\n" +
                    "• ESPACIO EN DISCO Y MICRO-SD: Asegúrate de contar con suficiente espacio libre en tu computadora y en la tarjeta MicroSD del reloj.\n\n" +
                    "• ESCANEO SECUENCIAL: Al pulsar 'Escanear Poblaciones', la app escaneará estado por estado automáticamente para evitar bloqueos del servidor."
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setLineSpacing(0f, 1.18f)
            setPadding(0, 0, 0, dp(16))
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnCancel = Button(this).apply {
            text = "CANCELAR"
            setBackgroundColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(6) }
        }

        val btnConfirm = Button(this).apply {
            text = "SÍ, AGREGAR ESTADO"
            setBackgroundColor(COLOR_GREEN)
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1.3f).apply { leftMargin = dp(6) }
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnConfirm)

        box.addView(title)
        box.addView(body)
        box.addView(btnRow)

        val dlg = AlertDialog.Builder(this).setView(box).create()
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dlg.dismiss() }
        btnConfirm.setOnClickListener {
            dlg.dismiss()
            agregarEstadoASeleccionMulti(nuevoEstado)
        }
        dlg.show()
    }

    private fun agregarEstadoASeleccionMulti(st: StateBoundaryInfo) {
        if (selectedStatesList.none { it.relationId == st.relationId && it.name == st.name }) {
            selectedStatesList.add(st)
        }
        recalcularBoundsMultiEstado()
        sincronizarEstadosVerdesEnMapaWeb(ajustarCamara = true)
        actualizarTextoEstadoMultiSeleccion()
        actualizarTelemetria()
    }

    private fun quitarEstadoDeSeleccionMulti(st: StateBoundaryInfo) {
        selectedStatesList.removeAll { it.relationId == st.relationId && it.name == st.name }
        if (selectedStatesList.isNotEmpty()) {
            recalcularBoundsMultiEstado()
        }
        sincronizarEstadosVerdesEnMapaWeb(ajustarCamara = false)
        actualizarTextoEstadoMultiSeleccion()
        actualizarTelemetria()
    }

    private fun sincronizarEstadosVerdesEnMapaWeb(ajustarCamara: Boolean) {
        if (selectedStatesList.isEmpty()) {
            webView.evaluateJavascript("clearSelectedStatesGreen();", null)
            return
        }
        val multiStatesJson = JSONArray()
        for (st in selectedStatesList) {
            val stRings = JSONArray()
            for (r in st.rings) {
                stRings.put(GeoBoundaryEngine.pointsToJsonArray(r, maxPoints = 2500))
            }
            multiStatesJson.put(stRings)
        }
        val safeJson = multiStatesJson.toString().replace("\\", "\\\\").replace("'", "\\'")
        val fitFlag = if (ajustarCamara) "true" else "false"
        webView.evaluateJavascript(
            "highlightMultipleStatesGreen('$safeJson', $boundSouth, $boundWest, $boundNorth, $boundEast, $fitFlag);",
            null
        )
    }

    private fun actualizarTextoEstadoMultiSeleccion() {
        if (selectedStatesList.isEmpty()) {
            txtStateModeHint.setTextColor(COLOR_AMBER)
            txtStateModeHint.text = ExonedRouterI18n.tr(this, "state_hint_select")
        } else {
            val nombres = selectedStatesList.joinToString(" + ") { it.name.uppercase(Locale.getDefault()) }
            val tagPc = if (selectedStatesList.size > 1) " [MODO MULTI-ESTADO -> REQUIERE PC]" else ""
            txtStateModeHint.setTextColor(COLOR_GREEN)
            txtStateModeHint.text = "[${selectedStatesList.size} SELECCIONADOS]: $nombres$tagPc. Toca otro para sumar o toca uno verde para quitarlo."
        }
    }

    // =========================================================================
    // GUARDAR Y CARGAR ÁREAS SELECCIONADAS (+ MULTI-ESTADO + PUEBLOS MANUALES)
    // =========================================================================
    private fun actualizarContadorBotonAreas() {
        if (::btnLoadAreaPreset.isInitialized) {
            val cnt = AreaPresetStorage.listPresets(this).size
            btnLoadAreaPreset.text = "${ExonedRouterI18n.tr(this, "btn_my_areas")} ($cnt)"
        }
    }

    private fun mostrarDialogoGuardarSeleccion() {
        if (isStateSelectionMode && selectedStatesList.isEmpty()) {
            Toast.makeText(this, "Selecciona al menos un estado en el mapa para guardar.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isStateSelectionMode && isPolygonMode && polygonVertices.size < 3) {
            Toast.makeText(this, "Traza al menos 3 puntos del polígono para guardar.", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isStateSelectionMode && !isPolygonMode && scannedUrbanCellsZ16.isEmpty()) {
            boundNorth = liveNorth
            boundSouth = liveSouth
            boundEast = liveEast
            boundWest = liveWest
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply {
                setColor(COLOR_PANEL)
                setStroke(dp(2), COLOR_CYAN)
                cornerRadius = dp(10).toFloat()
            }
        }

        val title = TextView(this).apply {
            text = "GUARDAR ÁREA Y PUEBLOS SELECCIONADOS"
            setTextColor(COLOR_CYAN)
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }

        val modeLabel = when {
            isStateSelectionMode -> "Estados (${selectedStatesList.joinToString("+") { it.name }})"
            isPolygonMode -> "Polígono SAS (${polygonVertices.size} vértices)"
            else -> "Caja Geodésica"
        }

        val infoTxt = TextView(this).apply {
            text = "Modo: $modeLabel\n" +
                    "Celdas Urbanas Z16 incluidas: ${scannedUrbanCellsZ16.size} " +
                    "($scannedCitiesCount Ciudades, $scannedTownsCount Pueblos, $scannedCommunitiesCount Comunidades/Manuales)"
            setTextColor(COLOR_GREEN)
            textSize = 11.5f
            setPadding(0, 0, 0, dp(12))
        }

        val defaultName = when {
            isStateSelectionMode && selectedStatesList.isNotEmpty() ->
                selectedStatesList.joinToString("_") { it.name.take(8) }
            isPolygonMode -> "Poligono_${SimpleDateFormat("ddMMM_HHmm", Locale.US).format(Date())}"
            else -> "Region_${SimpleDateFormat("ddMMM_HHmm", Locale.US).format(Date())}"
        }

        val inputName = EditText(this).apply {
            setText(defaultName)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "Nombre de tu selección..."
            textSize = 13.5f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(COLOR_CARD)
                setStroke(dp(1), COLOR_CYAN)
                cornerRadius = dp(6).toFloat()
            }
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }

        val btnCancel = Button(this).apply {
            text = "CANCELAR"
            setBackgroundColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(6) }
        }

        val btnSave = Button(this).apply {
            text = "GUARDAR"
            setBackgroundColor(COLOR_GREEN)
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(6) }
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnSave)

        box.addView(title)
        box.addView(infoTxt)
        box.addView(inputName)
        box.addView(btnRow)

        val dlg = AlertDialog.Builder(this).setView(box).create()
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dlg.dismiss() }
        btnSave.setOnClickListener {
            val chosenName = inputName.text.toString().trim().ifBlank { defaultName }
            val modeCode = when {
                isStateSelectionMode -> "STATE"
                isPolygonMode -> "POLY"
                else -> "BOX"
            }
            val ringsToSave = activeCustomRings() ?: emptyList()

            val preset = SavedAreaPreset(
                id = "AREA_${System.currentTimeMillis()}",
                name = chosenName,
                timestamp = System.currentTimeMillis(),
                mode = modeCode,
                statesList = if (isStateSelectionMode) selectedStatesList.toList() else emptyList(),
                north = boundNorth,
                south = boundSouth,
                east = boundEast,
                west = boundWest,
                rings = ringsToSave,
                urbanCellsZ16 = HashSet(scannedUrbanCellsZ16),
                isolateUrbanHighZoom = isolateUrbanHighZoom,
                routeProfileIdx = selectedRouteProfileIdx
            )

            AreaPresetStorage.savePreset(this, preset)
            actualizarContadorBotonAreas()
            Toast.makeText(this, "Selección '$chosenName' guardada (${scannedUrbanCellsZ16.size} celdas Z16).", Toast.LENGTH_SHORT).show()
            dlg.dismiss()
        }

        dlg.show()
    }

    private fun mostrarDialogoMisAreasGuardadas() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                setColor(COLOR_PANEL)
                setStroke(dp(2), COLOR_CYAN)
                cornerRadius = dp(10).toFloat()
            }
        }

        val title = TextView(this).apply {
            text = "MIS ÁREAS Y PUEBLOS GUARDADOS"
            setTextColor(COLOR_CYAN)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
        }
        box.addView(title)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320))
        }
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listContainer)
        box.addView(scroll)

        val btnClose = Button(this).apply {
            text = "CERRAR"
            setBackgroundColor(COLOR_DARK)
            setTextColor(COLOR_CYAN)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply {
                topMargin = dp(10)
            }
        }
        box.addView(btnClose)

        val dlg = AlertDialog.Builder(this).setView(box).create()
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        btnClose.setOnClickListener { dlg.dismiss() }

        fun poblarLista() {
            listContainer.removeAllViews()
            val presets = AreaPresetStorage.listPresets(this)
            actualizarContadorBotonAreas()

            if (presets.isEmpty()) {
                val emptyTxt = TextView(this).apply {
                    text = "Aún no tienes áreas guardadas.\nSelecciona estados o polígonos, añade tus pueblos con '+' y pulsa 'GUARDAR SELECCIÓN'."
                    setTextColor(Color.LTGRAY)
                    textSize = 12f
                    setPadding(dp(6), dp(20), dp(6), dp(20))
                }
                listContainer.addView(emptyTxt)
                return
            }

            val df = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            for (p in presets) {
                val itemCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = GradientDrawable().apply {
                        setColor(COLOR_CARD)
                        setStroke(dp(1), COLOR_DARK)
                        cornerRadius = dp(6).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                }

                val badge = when (p.mode) {
                    "STATE" -> "[ESTADOS: ${p.statesList.size.coerceAtLeast(1)}]"
                    "POLY" -> "[POLÍGONO SAS]"
                    else -> "[CAJA GEODÉSICA]"
                }

                val nameTxt = TextView(this).apply {
                    text = "${p.name}  $badge"
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    textSize = 12.5f
                }

                val subTxt = TextView(this).apply {
                    text = "Celdas Z16 (Pueblos): ${p.urbanCellsZ16.size}  |  Guardado: ${df.format(Date(p.timestamp))}"
                    setTextColor(COLOR_GREEN)
                    textSize = 11f
                    setPadding(0, dp(2), 0, dp(6))
                }

                val actionsRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }

                val btnLoad = Button(this).apply {
                    text = "CARGAR EN MAPA"
                    textSize = 10.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setBackgroundColor(COLOR_GREEN)
                    setTextColor(Color.BLACK)
                    layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f).apply { rightMargin = dp(6) }
                    setOnClickListener {
                        dlg.dismiss()
                        restaurarSeleccionGuardada(p)
                    }
                }

                val btnDel = Button(this).apply {
                    text = "BORRAR"
                    textSize = 10.5f
                    setBackgroundColor(COLOR_CRITICO)
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32))
                    setOnClickListener {
                        AreaPresetStorage.deletePreset(this@ExonedRouterActivity, p.id)
                        poblarLista()
                    }
                }

                actionsRow.addView(btnLoad)
                actionsRow.addView(btnDel)

                itemCard.addView(nameTxt)
                itemCard.addView(subTxt)
                itemCard.addView(actionsRow)
                listContainer.addView(itemCard)
            }
        }

        poblarLista()
        dlg.show()
    }

    private fun restaurarSeleccionGuardada(preset: SavedAreaPreset) {
        desactivarPincelPueblo()

        boundNorth = preset.north
        boundSouth = preset.south
        boundEast = preset.east
        boundWest = preset.west
        isolateUrbanHighZoom = preset.isolateUrbanHighZoom
        cbIsolateUrban.isChecked = preset.isolateUrbanHighZoom

        if (preset.routeProfileIdx in 0..2) {
            selectedRouteProfileIdx = preset.routeProfileIdx
            spRouteProfile.setSelection(preset.routeProfileIdx)
        }

        when (preset.mode) {
            "STATE" -> {
                cambiarModoPrincipal(estatal = true)
                selectedStatesList.clear()
                if (preset.statesList.isNotEmpty()) {
                    selectedStatesList.addAll(preset.statesList)
                } else {
                    selectedStatesList.add(
                        StateBoundaryInfo(
                            relationId = 0L,
                            name = preset.name,
                            rings = preset.rings,
                            north = preset.north,
                            south = preset.south,
                            east = preset.east,
                            west = preset.west
                        )
                    )
                }
                recalcularBoundsMultiEstado()
                btnResetCountry.visibility = View.VISIBLE
                sincronizarEstadosVerdesEnMapaWeb(ajustarCamara = true)
                actualizarTextoEstadoMultiSeleccion()
            }
            "POLY" -> {
                cambiarModoPrincipal(estatal = false)
                cambiarModoSeleccionBBox(poligono = true)
                polygonVertices.clear()
                val ring = preset.rings.firstOrNull() ?: emptyList()
                polygonVertices.addAll(ring)

                val ptsArr = JSONArray()
                for (p in ring) {
                    val o = JSONObject()
                    o.put("lat", p.lat)
                    o.put("lng", p.lon)
                    ptsArr.put(o)
                }
                val safePts = ptsArr.toString().replace("\\", "\\\\").replace("'", "\\'")
                webView.evaluateJavascript(
                    "restoreSavedPolygon('$safePts', ${preset.south}, ${preset.west}, ${preset.north}, ${preset.east});",
                    null
                )
            }
            else -> {
                cambiarModoPrincipal(estatal = false)
                cambiarModoSeleccionBBox(poligono = false)
                visorCentral.visibility = View.GONE
                webView.evaluateJavascript(
                    "drawLockedBox(${preset.south}, ${preset.west}, ${preset.north}, ${preset.east}); " +
                            "map.fitBounds([[${preset.south}, ${preset.west}], [${preset.north}, ${preset.east}]], { padding: [18, 18] });",
                    null
                )
            }
        }

        scannedUrbanCellsZ16.clear()
        scannedUrbanCellsZ16.addAll(preset.urbanCellsZ16)

        if (scannedUrbanCellsZ16.isNotEmpty()) {
            val (polysJson, counts) = UrbanScannerEngine.buildPolygonsFromCells(scannedUrbanCellsZ16)
            scannedCitiesCount = counts.first
            scannedTownsCount = counts.second
            scannedCommunitiesCount = counts.third
            lastScanStage2Completed = true
            btnClearUrban.visibility = View.VISIBLE

            val safeJson = polysJson.replace("\\", "\\\\").replace("'", "\\'")
            webView.evaluateJavascript("drawUrbanMask('$safeJson');", null)
        } else {
            scannedCitiesCount = 0
            scannedTownsCount = 0
            scannedCommunitiesCount = 0
            btnClearUrban.visibility = View.GONE
            webView.evaluateJavascript("clearUrbanMask();", null)
        }

        actualizarTelemetria()
        Toast.makeText(this, "Área '${preset.name}' restaurada con ${scannedUrbanCellsZ16.size} celdas urbanas.", Toast.LENGTH_SHORT).show()
    }

    private fun cambiarModoPrincipal(estatal: Boolean) {
        isStateSelectionMode = estatal
        desactivarPincelPueblo()

        if (estatal) {
            btnMainStateMode.setBackgroundColor(COLOR_GREEN)
            btnMainStateMode.setTextColor(Color.BLACK)
            btnMainBBoxMode.setBackgroundColor(COLOR_DARK)
            btnMainBBoxMode.setTextColor(COLOR_CYAN)

            subBBoxModeRow.visibility = View.GONE
            polyToolsRow.visibility = View.GONE
            visorCentral.visibility = View.GONE
            stateToolsPanel.visibility = View.VISIBLE

            webView.evaluateJavascript("setMainMode('STATE');", null)
            if (loadedCountryCode.isEmpty() && selectedStatesList.isEmpty()) {
                webView.evaluateJavascript("map.setView([23.6, -102.5], 5);", null)
            }
        } else {
            btnMainBBoxMode.setBackgroundColor(COLOR_CYAN)
            btnMainBBoxMode.setTextColor(Color.BLACK)
            btnMainStateMode.setBackgroundColor(COLOR_DARK)
            btnMainStateMode.setTextColor(COLOR_CYAN)

            stateToolsPanel.visibility = View.GONE
            subBBoxModeRow.visibility = View.VISIBLE
            cambiarModoSeleccionBBox(poligono = isPolygonMode)
        }
        actualizarTelemetria()
    }

    private fun reiniciarSeleccionPais() {
        loadedCountryCode = ""
        loadedCountryName = ""
        loadedCountryStates.clear()
        selectedStatesList.clear()
        btnResetCountry.visibility = View.GONE
        btnRetryStateLoad.visibility = View.GONE
        pbStateLoading.visibility = View.GONE
        txtStateModeHint.setTextColor(COLOR_AMBER)
        txtStateModeHint.text = ExonedRouterI18n.tr(this, "state_hint_step1")
        limpiarMascaraUrbana()
        webView.evaluateJavascript("clearStateModeLayers(); map.setZoom(4);", null)
        actualizarTelemetria()
    }

    private fun manejarToqueEnModoEstatal(lat: Double, lon: Double) {
        if (isFetchingCountryStates) return

        if (loadedCountryStates.isEmpty()) {
            iniciarCargaDePaisYEstados(lat, lon)
            return
        }

        var hitState: StateBoundaryInfo? = null
        for (st in loadedCountryStates) {
            if (lat in st.south..st.north && lon in st.west..st.east) {
                if (UrbanScannerEngine.isPointInAnyRing(lat, lon, st.rings)) {
                    hitState = st
                    break
                }
            }
        }

        if (hitState != null) {
            val alreadySelected = selectedStatesList.any { it.relationId == hitState.relationId && it.name == hitState.name }
            when {
                alreadySelected -> {
                    quitarEstadoDeSeleccionMulti(hitState)
                }
                selectedStatesList.isEmpty() -> {
                    agregarEstadoASeleccionMulti(hitState)
                }
                else -> {
                    mostrarDialogoConfirmarMultiEstado(hitState)
                }
            }
        } else {
            iniciarCargaDePaisYEstados(lat, lon)
        }
    }

    private fun iniciarCargaDePaisYEstados(lat: Double, lon: Double) {
        isFetchingCountryStates = true
        tileRamCache.evictAll()
        btnRetryStateLoad.visibility = View.GONE
        pbStateLoading.visibility = View.VISIBLE
        pbStateLoading.progress = 8
        txtStateModeHint.setTextColor(COLOR_AMBER)
        txtStateModeHint.text = "Comprobando enlace a Internet e identificando país..."

        thread(start = true, isDaemon = true) {
            val countryRes = GeoBoundaryEngine.identifyCountryAt(lat, lon) { statusMsg ->
                mainHandler.post { txtStateModeHint.text = statusMsg }
            }
            if (countryRes.isFailure) {
                val err = countryRes.exceptionOrNull()?.localizedMessage ?: "Error de red"
                mainHandler.post {
                    isFetchingCountryStates = false
                    txtStateModeHint.setTextColor(COLOR_CRITICO)
                    txtStateModeHint.text = "[ERROR] $err"
                    btnRetryStateLoad.visibility = View.VISIBLE
                    btnRetryStateLoad.setOnClickListener { iniciarCargaDePaisYEstados(lat, lon) }
                }
                return@thread
            }

            val country = countryRes.getOrThrow()
            loadedCountryCode = country.countryCodeIso2
            loadedCountryName = country.countryName

            mainHandler.post {
                pbStateLoading.progress = 18
                txtStateModeHint.setTextColor(COLOR_CRITICO)
                txtStateModeHint.text = "[CARGANDO] ${country.countryName}: Conectando con índice ISO3166-2..."
                val safeRings = country.ringsJson.replace("\\", "\\\\").replace("'", "\\'")
                webView.evaluateJavascript(
                    "startCountryBlinkingRed('$safeRings', ${country.south}, ${country.west}, ${country.north}, ${country.east});",
                    null
                )
            }

            val statesRes = GeoBoundaryEngine.fetchStatesForCountry(country) { pct, msg ->
                mainHandler.post {
                    pbStateLoading.progress = pct
                    txtStateModeHint.text = "${country.countryName} ($pct%): $msg"
                }
            }

            mainHandler.post {
                isFetchingCountryStates = false
                statesRes.onSuccess { states ->
                    loadedCountryStates.clear()
                    loadedCountryStates.addAll(states)
                    pbStateLoading.visibility = View.GONE
                    btnResetCountry.visibility = View.VISIBLE

                    val allStatesJson = JSONArray()
                    for (st in states) {
                        val stRings = JSONArray()
                        for (r in st.rings) {
                            stRings.put(GeoBoundaryEngine.pointsToJsonArray(r, maxPoints = 1500))
                        }
                        allStatesJson.put(stRings)
                    }
                    val safeStatesStr = allStatesJson.toString().replace("\\", "\\\\").replace("'", "\\'")
                    webView.evaluateJavascript("showCountryStatesGrid('$safeStatesStr');", null)

                    var touchedState: StateBoundaryInfo? = null
                    for (st in states) {
                        if (lat in st.south..st.north && lon in st.west..st.east) {
                            if (UrbanScannerEngine.isPointInAnyRing(lat, lon, st.rings)) {
                                touchedState = st
                                break
                            }
                        }
                    }

                    if (touchedState != null) {
                        if (selectedStatesList.isEmpty()) {
                            agregarEstadoASeleccionMulti(touchedState)
                        } else if (selectedStatesList.none { it.relationId == touchedState.relationId }) {
                            mostrarDialogoConfirmarMultiEstado(touchedState)
                        }
                    } else {
                        actualizarTextoEstadoMultiSeleccion()
                    }
                }.onFailure { err ->
                    webView.evaluateJavascript("stopCountryBlinkingRed();", null)
                    txtStateModeHint.setTextColor(COLOR_CRITICO)
                    txtStateModeHint.text = "[ERROR] Fallo en ${country.countryName}: ${err.localizedMessage}"
                    btnRetryStateLoad.visibility = View.VISIBLE
                    btnRetryStateLoad.setOnClickListener { iniciarCargaDePaisYEstados(lat, lon) }
                }
            }
        }
    }

    private fun alternarPincelPueblo(modo: Int) {
        if (manualTownBrushMode == modo) {
            desactivarPincelPueblo()
        } else {
            manualTownBrushMode = modo
            if (modo == 1) {
                btnBrushAddTown.setBackgroundColor(COLOR_GREEN)
                btnBrushAddTown.setTextColor(Color.BLACK)
                btnBrushEraseTown.setBackgroundColor(COLOR_DARK)
                btnBrushEraseTown.setTextColor(COLOR_AMBER)
                Toast.makeText(this, "Toca cualquier pueblito en el mapa para agregarlo.", Toast.LENGTH_SHORT).show()
            } else {
                btnBrushEraseTown.setBackgroundColor(COLOR_AMBER)
                btnBrushEraseTown.setTextColor(Color.BLACK)
                btnBrushAddTown.setBackgroundColor(COLOR_DARK)
                btnBrushAddTown.setTextColor(COLOR_GREEN)
                Toast.makeText(this, "Toca cualquier mancha verde en el mapa para borrarla.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun desactivarPincelPueblo() {
        manualTownBrushMode = 0
        if (::btnBrushAddTown.isInitialized) {
            btnBrushAddTown.setBackgroundColor(COLOR_DARK)
            btnBrushAddTown.setTextColor(COLOR_GREEN)
            btnBrushEraseTown.setBackgroundColor(COLOR_DARK)
            btnBrushEraseTown.setTextColor(COLOR_AMBER)
        }
    }

    private fun aplicarPincelManualEnCoordenada(lat: Double, lon: Double) {
        val (cx, cy) = TileMatrixCalculator.deg2num(lat, lon, UrbanScannerEngine.Z_MASK)
        if (manualTownBrushMode == 1) {
            scannedUrbanCellsZ16.add(UrbanScannerEngine.packXY(cx, cy))
            scannedCommunitiesCount++
        } else if (manualTownBrushMode == 2) {
            for (dx in -1..1) {
                for (dy in -1..1) {
                    scannedUrbanCellsZ16.remove(UrbanScannerEngine.packXY(cx + dx, cy + dy))
                }
            }
        }

        val (polysJson, counts) = UrbanScannerEngine.buildPolygonsFromCells(scannedUrbanCellsZ16)
        scannedCitiesCount = counts.first
        scannedTownsCount = counts.second
        scannedCommunitiesCount = counts.third
        btnClearUrban.visibility = if (scannedUrbanCellsZ16.isNotEmpty()) View.VISIBLE else View.GONE

        val safeJson = polysJson.replace("\\", "\\\\").replace("'", "\\'")
        webView.evaluateJavascript("drawUrbanMask('$safeJson');", null)
        actualizarTelemetria()
    }

    private fun ejecutarEscaneoPoblaciones() {
        if (isScanningUrban) return
        if (isStateSelectionMode && selectedStatesList.isEmpty()) {
            Toast.makeText(this, "Primero toca un país y selecciona al menos un estado en el mapa.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isStateSelectionMode && isPolygonMode && polygonVertices.size < 3) {
            Toast.makeText(this, "Primero traza al menos 3 puntos en el mapa.", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isStateSelectionMode && !isPolygonMode && scannedUrbanCellsZ16.isEmpty()) {
            boundNorth = liveNorth
            boundSouth = liveSouth
            boundEast = liveEast
            boundWest = liveWest
        }

        tileRamCache.evictAll()

        isScanningUrban = true
        btnScanUrban.isEnabled = false
        btnScanUrban.setBackgroundColor(Color.DKGRAY)
        btnScanUrban.setTextColor(Color.WHITE)
        btnScanUrban.text = "ESCANEANDO..."

        pbUrbanScan.visibility = View.VISIBLE
        pbUrbanScan.progress = 5
        txtUrbanProgressPct.visibility = View.VISIBLE
        txtUrbanProgressPct.setTextColor(COLOR_CYAN)
        txtUrbanProgressPct.text = "5% — Iniciando escaneo aditivo (Poblaciones + Cuadras)..."
        txtUrbanStatus.setTextColor(COLOR_AMBER)
        txtUrbanStatus.text = "Extrayendo ciudades, pueblos y cuadrículas de calles en flujo continuo..."

        val statesSnapshot = if (isStateSelectionMode) selectedStatesList.toList() else emptyList()
        val nSnap = boundNorth
        val sSnap = boundSouth
        val eSnap = boundEast
        val wSnap = boundWest
        val customRingsSnap = activeCustomRings()
        val hasCustomPolySnap = hasActiveCustomPolygon()
        val lockBoxOnMap = (!isStateSelectionMode && !isPolygonMode)

        thread(start = true, isDaemon = true) {
            if (statesSnapshot.isNotEmpty()) {
                val combinedCells = HashSet<Long>()
                var allStage2Ok = true
                var lastErrStr = "Error en escaneo multi-estado"
                var anySuccess = false

                for ((stIdx, st) in statesSnapshot.withIndex()) {
                    val prefix = if (statesSnapshot.size > 1) "[Estado ${stIdx + 1}/${statesSnapshot.size}: ${st.name}] " else ""
                    val res = UrbanScannerEngine.scanPopulatedZonesWithProgress(
                        north = st.north,
                        south = st.south,
                        east = st.east,
                        west = st.west,
                        selectionRings = st.rings,
                        hasCustomPolygon = true,
                        stateRelationId = st.relationId
                    ) { pct, stageMsg ->
                        val globalPct = ((stIdx * 100 + pct) / statesSnapshot.size).coerceIn(1, 99)
                        mainHandler.post {
                            pbUrbanScan.progress = globalPct
                            txtUrbanProgressPct.text = "$globalPct% — $prefix$stageMsg"
                        }
                    }

                    res.onSuccess { scan ->
                        combinedCells.addAll(scan.z16Cells)
                        if (!scan.stage2Completed) allStage2Ok = false
                        anySuccess = true
                    }.onFailure { err ->
                        lastErrStr = "${st.name}: ${err.localizedMessage}"
                    }
                }

                if (!anySuccess) {
                    mainHandler.post {
                        isScanningUrban = false
                        btnScanUrban.isEnabled = true
                        pbUrbanScan.progress = 0
                        txtUrbanProgressPct.setTextColor(COLOR_CRITICO)
                        txtUrbanProgressPct.text = "[FALLO]: $lastErrStr"
                        btnScanUrban.setBackgroundColor(COLOR_AMBER)
                        btnScanUrban.setTextColor(Color.BLACK)
                        btnScanUrban.text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_retry")
                    }
                    return@thread
                }

                val (polygonsJson, counts) = UrbanScannerEngine.buildPolygonsFromCells(combinedCells)
                mainHandler.post {
                    isScanningUrban = false
                    btnScanUrban.isEnabled = true
                    pbUrbanScan.visibility = View.GONE
                    txtUrbanProgressPct.visibility = View.GONE

                    btnScanUrban.setBackgroundColor(COLOR_GREEN)
                    btnScanUrban.setTextColor(Color.BLACK)
                    btnScanUrban.text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_rescan_urban")

                    scannedUrbanCellsZ16.clear()
                    scannedUrbanCellsZ16.addAll(combinedCells)
                    scannedCitiesCount = counts.first
                    scannedTownsCount = counts.second
                    scannedCommunitiesCount = counts.third
                    lastScanStage2Completed = allStage2Ok
                    cbIsolateUrban.isChecked = true
                    isolateUrbanHighZoom = true
                    btnClearUrban.visibility = View.VISIBLE

                    val safeJson = polygonsJson.replace("\\", "\\\\").replace("'", "\\'")
                    webView.evaluateJavascript("drawUrbanMask('$safeJson');", null)
                    actualizarTelemetria()
                }
            } else {
                val result = UrbanScannerEngine.scanPopulatedZonesWithProgress(
                    north = nSnap,
                    south = sSnap,
                    east = eSnap,
                    west = wSnap,
                    selectionRings = customRingsSnap,
                    hasCustomPolygon = hasCustomPolySnap,
                    stateRelationId = 0L
                ) { pct, stageMsg ->
                    mainHandler.post {
                        pbUrbanScan.progress = pct
                        txtUrbanProgressPct.text = "$pct% — $stageMsg"
                    }
                }

                mainHandler.post {
                    isScanningUrban = false
                    btnScanUrban.isEnabled = true

                    result.onSuccess { scan ->
                        pbUrbanScan.visibility = View.GONE
                        txtUrbanProgressPct.visibility = View.GONE

                        btnScanUrban.setBackgroundColor(COLOR_GREEN)
                        btnScanUrban.setTextColor(Color.BLACK)
                        btnScanUrban.text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_rescan_urban")

                        scannedUrbanCellsZ16.clear()
                        scannedUrbanCellsZ16.addAll(scan.z16Cells)
                        scannedCitiesCount = scan.citiesCount
                        scannedTownsCount = scan.townsCount
                        scannedCommunitiesCount = scan.communitiesCount
                        lastScanStage2Completed = scan.stage2Completed
                        cbIsolateUrban.isChecked = true
                        isolateUrbanHighZoom = true
                        btnClearUrban.visibility = View.VISIBLE

                        if (lockBoxOnMap) {
                            visorCentral.visibility = View.GONE
                            webView.evaluateJavascript("drawLockedBox($sSnap, $wSnap, $nSnap, $eSnap);", null)
                        }

                        val safeJson = scan.polygonsWithHolesJson.replace("\\", "\\\\").replace("'", "\\'")
                        webView.evaluateJavascript("drawUrbanMask('$safeJson');", null)

                        actualizarTelemetria()
                    }.onFailure { err ->
                        pbUrbanScan.progress = 0
                        txtUrbanProgressPct.setTextColor(COLOR_CRITICO)
                        txtUrbanProgressPct.text = "[FALLO]: ${err.localizedMessage}"

                        btnScanUrban.setBackgroundColor(COLOR_AMBER)
                        btnScanUrban.setTextColor(Color.BLACK)
                        btnScanUrban.text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_retry")

                        txtUrbanStatus.setTextColor(COLOR_CRITICO)
                        txtUrbanStatus.text = "Diagnóstico: ${err.localizedMessage}. Pulsa el botón ámbar arriba para reintentar."
                    }
                }
            }
        }
    }

    private fun limpiarMascaraUrbana() {
        desactivarPincelPueblo()
        scannedUrbanCellsZ16.clear()
        scannedCitiesCount = 0
        scannedTownsCount = 0
        scannedCommunitiesCount = 0
        lastScanStage2Completed = true
        btnClearUrban.visibility = View.GONE
        pbUrbanScan.visibility = View.GONE
        txtUrbanProgressPct.visibility = View.GONE
        btnScanUrban.setBackgroundColor(COLOR_GREEN)
        btnScanUrban.setTextColor(Color.BLACK)
        btnScanUrban.text = ExonedRouterI18n.tr(this, "btn_scan_urban")
        txtUrbanStatus.setTextColor(Color.LTGRAY)
        txtUrbanStatus.text = "Máscara limpiada. Puedes encuadrar otra zona o volver a escanear."

        if (!isStateSelectionMode && !isPolygonMode) {
            visorCentral.visibility = View.VISIBLE
            boundNorth = liveNorth
            boundSouth = liveSouth
            boundEast = liveEast
            boundWest = liveWest
        }
        webView.evaluateJavascript("clearUrbanMask();", null)
        actualizarTelemetria()
    }

    private fun aplicarEstadoViewport(nuevoEstado: Int) {
        viewportState = nuevoEstado
        when (nuevoEstado) {
            0 -> {
                btnRestoreMapBar.visibility = View.GONE
                mapContainer.visibility = View.VISIBLE
                mapContainer.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(240)
                )
                configScroll.visibility = View.VISIBLE
                configScroll.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                }
                btnExpandMap.text = ExonedRouterI18n.tr(this, "btn_expand")
                visorCentral.layoutParams = FrameLayout.LayoutParams(dp(220), dp(155), Gravity.CENTER)
                webView.evaluateJavascript("setReticleBoxSize(110, 77.5);", null)
            }
            1 -> {
                btnRestoreMapBar.visibility = View.GONE
                mapContainer.visibility = View.VISIBLE
                mapContainer.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).apply {
                    bottomMargin = dp(6)
                }
                configScroll.visibility = View.GONE
                btnExpandMap.text = ExonedRouterI18n.tr(this, "btn_view_menu")
                visorCentral.layoutParams = FrameLayout.LayoutParams(dp(260), dp(240), Gravity.CENTER)
                webView.evaluateJavascript("setReticleBoxSize(130, 120);", null)
            }
            2 -> {
                mapContainer.visibility = View.GONE
                btnRestoreMapBar.visibility = View.VISIBLE
                configScroll.visibility = View.VISIBLE
                configScroll.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(6)
                }
            }
        }
        webView.evaluateJavascript("setTimeout(function(){ map.invalidateSize(); reportReticleBounds(); }, 180);", null)
    }

    private fun procesarMosaicoConShaderNativo(uri: Uri): WebResourceResponse? {
        try {
            val segments = uri.pathSegments
            if (segments.size < 5) return null
            val prov = segments[0].toIntOrNull() ?: 0
            val style = segments[1].toIntOrNull() ?: 0
            val z = segments[2].toIntOrNull() ?: 12
            val x = segments[3].toIntOrNull() ?: 0
            val y = segments[4].removeSuffix(".png").toIntOrNull() ?: 0

            val cacheKey = "${prov}_${style}_${z}_${x}_${y}"
            tileRamCache.get(cacheKey)?.let { cachedBytes ->
                return WebResourceResponse("image/png", null, ByteArrayInputStream(cachedBytes))
            }

            if (prov == 1) {
                val apiKey = CartoApiManager.getApiKey(this)
                val sub = arrayOf("a", "b", "c")[(x + y) % 3]
                val cartoUrl = if (apiKey.isNotBlank()) {
                    "https://$sub.basemaps.cartocdn.com/dark_all/$z/$x/$y.png?api_key=${Uri.encode(apiKey)}"
                } else {
                    "https://$sub.basemaps.cartocdn.com/dark_all/$z/$x/$y.png"
                }

                var rawBytes = descargarBytesHttp(cartoUrl, referer = "https://carto.com/", bearerToken = apiKey)
                var bmp = if (rawBytes != null) BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) else null

                if (bmp == null || esMosaicoWatermarkCarto(bmp)) {
                    bmp?.recycle()
                    val esriBase = descargarBitmapHttp(
                        "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/$z/$y/$x"
                    )
                    val esriLabels = descargarBitmapHttp(
                        "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Reference/MapServer/tile/$z/$y/$x"
                    )
                    if (esriBase != null) {
                        val combined = esriBase.copy(Bitmap.Config.ARGB_8888, true)
                        if (esriLabels != null) {
                            val canvas = android.graphics.Canvas(combined)
                            canvas.drawBitmap(esriLabels, 0f, 0f, null)
                            esriLabels.recycle()
                        }
                        val outStream = ByteArrayOutputStream()
                        combined.compress(Bitmap.CompressFormat.PNG, 92, outStream)
                        combined.recycle()
                        rawBytes = outStream.toByteArray()
                    }
                } else {
                    bmp.recycle()
                }

                if (rawBytes != null) {
                    tileRamCache.put(cacheKey, rawBytes)
                    return WebResourceResponse("image/png", null, ByteArrayInputStream(rawBytes))
                }
                return null
            }

            val primaryUrl = CartoApiManager.buildPrimaryTileUrl(this, x, y, z, style) ?: return null
            val rawGoogle = descargarBytesHttp(primaryUrl, referer = CartoApiManager.getPrimaryReferer(this)) ?: return null

            if (style == 3 || style == 4) {
                tileRamCache.put(cacheKey, rawGoogle)
                return WebResourceResponse("image/png", null, ByteArrayInputStream(rawGoogle))
            }

            val srcBmp = BitmapFactory.decodeByteArray(rawGoogle, 0, rawGoogle.size) ?: return null
            val shadedBmp = CyberpunkShaderEngine.applyShader(srcBmp, style, quantizeForWatch = false)
            srcBmp.recycle()

            val baos = ByteArrayOutputStream()
            shadedBmp.compress(Bitmap.CompressFormat.PNG, 94, baos)
            shadedBmp.recycle()

            val finalBytes = baos.toByteArray()
            tileRamCache.put(cacheKey, finalBytes)
            return WebResourceResponse("image/png", null, ByteArrayInputStream(finalBytes))
        } catch (_: Throwable) {
            return null
        }
    }

    private fun esMosaicoWatermarkCarto(bmp: Bitmap): Boolean {
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
            for (d in 50..205 step 2) {
                val pxDiag = bmp.getPixel(d, d)
                val pxVert = bmp.getPixel(128, d)
                if ((pxDiag and 0xFF) > r1 + 35 || (pxVert and 0xFF) > r1 + 35) {
                    brightTextHits++
                }
            }
            if (brightTextHits >= 3) return true
        }
        return false
    }

    private fun descargarBytesHttp(urlStr: String, referer: String, bearerToken: String? = null): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36")
                setRequestProperty("Referer", referer)
                if (!bearerToken.isNullOrBlank() && bearerToken.startsWith("eyJ")) {
                    setRequestProperty("Authorization", "Bearer $bearerToken")
                }
            }
            if (conn.responseCode == 200) {
                conn.inputStream.use { it.readBytes() }
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun descargarBitmapHttp(urlStr: String): Bitmap? {
        val bytes = descargarBytesHttp(urlStr, "https://www.arcgis.com/") ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun cambiarModoSeleccionBBox(poligono: Boolean) {
        isPolygonMode = poligono
        if (poligono) {
            btnModePoly.setBackgroundColor(COLOR_CYAN)
            btnModePoly.setTextColor(Color.BLACK)
            btnModeBox.setBackgroundColor(COLOR_DARK)
            btnModeBox.setTextColor(COLOR_CYAN)

            visorCentral.visibility = View.GONE
            polyToolsRow.visibility = View.VISIBLE
            webView.evaluateJavascript("setMainMode('POLY');", null)

            if (!polygonTutorialShown) {
                polygonTutorialShown = true
                mostrarInstruccionesPoligono()
            }
        } else {
            btnModeBox.setBackgroundColor(COLOR_CYAN)
            btnModeBox.setTextColor(Color.BLACK)
            btnModePoly.setBackgroundColor(COLOR_DARK)
            btnModePoly.setTextColor(COLOR_CYAN)

            visorCentral.visibility = if (scannedUrbanCellsZ16.isNotEmpty()) View.GONE else View.VISIBLE
            polyToolsRow.visibility = View.GONE
            webView.evaluateJavascript("setMainMode('BOX');", null)
        }
        actualizarTelemetria()
    }

    private fun mostrarInstruccionesPoligono() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply {
                setColor(COLOR_PANEL)
                setStroke(dp(2), COLOR_AMBER)
                cornerRadius = dp(10).toFloat()
            }
        }

        val title = TextView(this).apply {
            text = "CÓMO TRAZAR Y ARRASTRAR EL POLÍGONO"
            setTextColor(COLOR_AMBER)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
        }

        val body = TextView(this).apply {
            text = "1. Toca sobre el mapa para colocar cada esquina (a partir de 3 puntos se cierra la figura).\n\n" +
                    "2. PUEDES MOVER LOS PUNTOS: Mantén presionado cualquier punto verde ya puesto y arrástralo con el dedo para ajustar la silueta igual que en SAS.Planet.\n\n" +
                    "3. Usa el botón rosa \"EXPANDIR\" en la esquina inferior derecha del mapa si quieres hacerlo en grande."
            setTextColor(Color.WHITE)
            textSize = 13.2f
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, dp(16))
        }

        val btnOk = Button(this).apply {
            text = "ENTENDIDO"
            setBackgroundColor(COLOR_AMBER)
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
        }

        box.addView(title)
        box.addView(body)
        box.addView(btnOk)

        val dlg = AlertDialog.Builder(this).setView(box).create()
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        btnOk.setOnClickListener { dlg.dismiss() }
        dlg.show()
    }

    private fun crearCheckboxPerfil(
        titulo: String,
        subtitulo: String,
        checkedInicial: Boolean,
        rango: IntRange
    ): CheckBox {
        return CheckBox(this).apply {
            text = "$titulo\n   -> $subtitulo"
            setTextColor(Color.WHITE)
            textSize = 12f
            isChecked = checkedInicial
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, isChecked ->
                if (updatingCheckboxes) return@setOnCheckedChangeListener
                if (isChecked) activeZooms.addAll(rango) else activeZooms.removeAll(rango.toSet())
                actualizarResumenNiveles()
                actualizarTelemetria()
            }
        }
    }

    private fun mostrarDialogoZoomManual() {
        val idxLang = mapOf(
            "es" to 0, "en" to 1, "zh" to 2, "ja" to 3,
            "hi" to 4, "fr" to 5, "ar" to 6, "ru" to 7
        )[ExonedForgeI18n.getLang(this)] ?: 0

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply {
                setColor(COLOR_PANEL)
                setStroke(dp(2), COLOR_CYAN)
                cornerRadius = dp(10).toFloat()
            }
        }

        val title = TextView(this).apply {
            text = arrayOf(
                "SELECCIÓN MANUAL DE NIVELES (Z6 - Z20)",
                "MANUAL LEVEL SELECTION (Z6 - Z20)",
                "手动选择分辨率级别 (Z6 - Z20)",
                "レベル手動選択 (Z6 - Z20)",
                "मैनुअल स्तर चयन (Z6 - Z20)",
                "SÉLECTION MANUELLE DES NIVEAUX (Z6 - Z20)",
                "الاختيار اليدوي للمستويات (Z6 - Z20)",
                "РУЧНОЙ ВЫБОР УРОВНЕЙ (Z6 - Z20)"
            )[idxLang]
            setTextColor(COLOR_CYAN)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
        }
        box.addView(title)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280))
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val tLvlPrefix = arrayOf("Nivel Z", "Level Z", "级别 Z", "レベル Z", "स्तर Z", "Niveau Z", "المستوى Z", "Уровень Z")[idxLang]

        for (z in 6..20) {
            val tag = when (z) {
                in 6..8 -> arrayOf(
                    "Vista Continental (Macro)",
                    "Continental View (Macro)",
                    "大陆视图 (宏观)",
                    "大陸ビュー (マクロ)",
                    "महाद्वीपीय दृश्य (मैक्रो)",
                    "Vue Continentale (Macro)",
                    "عرض قاري (ماكرو)",
                    "Континентальный вид (Макро)"
                )[idxLang]
                in 9..11 -> arrayOf(
                    "Radar de Carreteras (General)",
                    "Highway Radar (General)",
                    "公路雷达 (常规)",
                    "幹線道路レーダー (一般)",
                    "राजमार्ग रडार (सामान्य)",
                    "Radar Routier (Général)",
                    "رادار الطرق السريعة (عام)",
                    "Радар трасс (Общий)"
                )[idxLang]
                in 12..14 -> arrayOf(
                    "Reconocimiento Urbano (Cercano)",
                    "Urban Recon (Close)",
                    "城市侦察 (近距)",
                    "都市偵察 (近距離)",
                    "शहरी टोही (निकट)",
                    "Reconnaissance Urbaine (Proche)",
                    "استطلاع حضري (قريب)",
                    "Городская разведка (Близко)"
                )[idxLang]
                in 15..18 -> arrayOf(
                    "Trazado de Calles (Alta Resolución)",
                    "Street Layout (High Resolution)",
                    "街道布局 (高分辨率)",
                    "街路詳細 (高解像度)",
                    "सड़कों का खाका (उच्च रिज़ॉल्यूशन)",
                    "Tracé des Rues (Haute Résolution)",
                    "تخطيط الشوارع (دقة عالية)",
                    "Сетка улиц (Высокое разрешение)"
                )[idxLang]
                else -> arrayOf(
                    "Precisión Milimétrica (Ultra Resolución)",
                    "Pinpoint Precision (Ultra Resolution)",
                    "毫米级精度 (超高分辨率)",
                    "ミリ単位の精度 (超高解像度)",
                    "मिलीमीटर सटीकता (अल्ट्रा रिज़ॉल्यूशन)",
                    "Précision Millimétrique (Ultra Résolution)",
                    "دقة مليمترية (دقة فائقة)",
                    "Миллиметровая точность (Ультра разрешение)"
                )[idxLang]
            }
            val cb = CheckBox(this).apply {
                text = "$tLvlPrefix$z  —  $tag"
                setTextColor(if (z >= 19) COLOR_AMBER else Color.WHITE)
                textSize = 12.5f
                isChecked = activeZooms.contains(z)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) activeZooms.add(z) else activeZooms.remove(z)
                    sincronizarPerfilesDesdeManual()
                    actualizarResumenNiveles()
                    actualizarTelemetria()
                }
            }
            list.addView(cb)
        }
        scroll.addView(list)
        box.addView(scroll)

        val btnListo = Button(this).apply {
            text = arrayOf(
                "APLICAR NIVELES",
                "APPLY LEVELS",
                "应用级别",
                "レベルを適用",
                "स्तर लागू करें",
                "APPLIQUER LES NIVEAUX",
                "تطبيق المستويات",
                "ПРИМЕНИТЬ УРОВНИ"
            )[idxLang]
            setBackgroundColor(COLOR_GREEN)
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
        }
        box.addView(btnListo)

        val dlg = AlertDialog.Builder(this).setView(box).create()
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        btnListo.setOnClickListener { dlg.dismiss() }
        dlg.show()
    }

    private fun sincronizarPerfilesDesdeManual() {
        updatingCheckboxes = true
        cbMacro.isChecked = (6..8).any { activeZooms.contains(it) }
        cbGeneral.isChecked = (9..11).any { activeZooms.contains(it) }
        cbCercano.isChecked = (12..14).any { activeZooms.contains(it) }
        cbAlta.isChecked = (15..18).any { activeZooms.contains(it) }
        cbUltra.isChecked = (19..20).any { activeZooms.contains(it) }
        updatingCheckboxes = false
    }

    private fun actualizarResumenNiveles() {
        val idxLang = mapOf(
            "es" to 0, "en" to 1, "zh" to 2, "ja" to 3,
            "hi" to 4, "fr" to 5, "ar" to 6, "ru" to 7
        )[ExonedForgeI18n.getLang(this)] ?: 0

        if (activeZooms.isEmpty()) {
            txtManualSummary.setTextColor(COLOR_AMBER)
            txtManualSummary.text = arrayOf(
                "Ningún nivel Z seleccionado (Puedes usar 'SOLO RUTAS .HWY').",
                "No Z level selected (You can use 'ROUTES ONLY .HWY').",
                "未选择任何 Z 级别（可使用“仅路线 .HWY”）。",
                "Zレベル未選択（「ルートのみ .HWY」を使用できます）。",
                "कोई Z स्तर चयनित नहीं है (आप 'केवल मार्ग .HWY' का उपयोग कर सकते हैं)।",
                "Aucun niveau Z sélectionné (Vous pouvez utiliser 'ROUTES SEULES .HWY').",
                "لم يتم تحديد أي مستوى Z (يمكنك استخدام 'مسارات فقط .HWY').",
                "Ни один уровень Z не выбран (Можно использовать 'ТОЛЬКО МАРШРУТЫ .HWY')."
            )[idxLang]
        } else {
            txtManualSummary.setTextColor(COLOR_CYAN)
            val sorted = activeZooms.sorted().joinToString(", ") { "Z$it" }
            val activeLbl = arrayOf(
                "Niveles activos (${activeZooms.size}): $sorted",
                "Active levels (${activeZooms.size}): $sorted",
                "已激活级别 (${activeZooms.size})：$sorted",
                "有効レベル (${activeZooms.size}): $sorted",
                "सक्रिय स्तर (${activeZooms.size}): $sorted",
                "Niveaux actifs (${activeZooms.size}) : $sorted",
                "المستويات النشطة (${activeZooms.size}): $sorted",
                "Активные уровни (${activeZooms.size}): $sorted"
            )[idxLang]
            txtManualSummary.text = activeLbl
        }
    }

    private fun actualizarTelemetria() {
        val telemetry = TileMatrixCalculator.evaluateSelection(
            north = boundNorth,
            south = boundSouth,
            east = boundEast,
            west = boundWest,
            selectionRings = activeCustomRings(),
            hasCustomPolygon = hasActiveCustomPolygon(),
            selectedZooms = activeZooms,
            includeRoutes = includeRoutes,
            routeProfileIdx = selectedRouteProfileIdx,
            isolateUrbanHighZoom = isolateUrbanHighZoom,
            urbanZ16Cells = scannedUrbanCellsZ16
        )

        val cartoUsed = CartoApiManager.getMonthlyUsedTiles(this)
        val isCartoPremium = cbPremiumCarto.isChecked
        val remainingCartoFree = (CartoApiManager.SAFE_FREE_LIMIT - cartoUsed).coerceAtLeast(0L)

        val idxLang = mapOf(
            "es" to 0, "en" to 1, "zh" to 2, "ja" to 3,
            "hi" to 4, "fr" to 5, "ar" to 6, "ru" to 7
        )[ExonedForgeI18n.getLang(this)] ?: 0

        val tSecFilt = arrayOf("SECTORES (FILTRADO):", "SECTORS (FILTERED):", "扇区 (已过滤)：", "セクター (フィルター済):", "सेक्टर (फ़िल्टर्ड):", "SECTEURS (FILTRÉS) :", "القطاعات (مفلترة):", "СЕКТОРЫ (ФИЛЬТР):")[idxLang]
        val tSec = arrayOf("SECTORES:", "SECTORS:", "扇区：", "セクター:", "सेक्टर:", "SECTEURS :", "القطاعات:", "СЕКТОРЫ:")[idxLang]
        val tArea = arrayOf("ÁREA:", "AREA:", "面积：", "面積:", "क्षेत्र:", "ZONE :", "المنطقة:", "ПЛОЩАДЬ:")[idxLang]

        val tOkUrb = arrayOf("[OK] POBLACIONES PERFILADAS:", "[OK] POPULATIONS PROFILED:", "[OK] 已生成居民区轮廓：", "[OK] 市街地プロファイリング完了:", "[OK] आबादी प्रोफाइल की गई:", "[OK] POPULATIONS PROFILÉES :", "[OK] تم تحديد المناطق المأهولة:", "[ОК] НАСЕЛЕНИЕ ОПРЕДЕЛЕНО:")[idxLang]
        val tCities = arrayOf("Ciudades", "Cities", "城市", "都市", "शहर", "Villes", "مدن", "Города")[idxLang]
        val tTowns = arrayOf("Pueblos", "Towns", "城镇", "町", "कस्बे", "Villages", "بلدات", "Посёлки")[idxLang]
        val tComms = arrayOf("Comunidades", "Communities", "社区", "集落", "समुदाय", "Communautés", "مجتمعات", "Общины")[idxLang]
        val tSaveZ = arrayOf("Ahorro en Z15-Z20:", "Savings in Z15-Z20:", "Z15-Z20 节省空间：", "Z15-Z20 削減率:", "Z15-Z20 में बचत:", "Économie en Z15-Z20 :", "التوفير في Z15-Z20:", "Экономия на Z15-Z20:")[idxLang]
        val tDropFrom = arrayOf("Bajó de", "Dropped from", "从", "削減:", "से घटकर", "Passé de", "انخفض من", "Снижено с")[idxLang]
        val tTo = arrayOf("a", "to", "降至", "→", "तक", "à", "إلى", "до")[idxLang]
        val tRetouch = arrayOf("Usa +/- para retocar o 'GUARDAR SELECCIÓN'.", "Use +/- to retouch or 'SAVE SELECTION'.", "使用 +/- 微调或点击“保存当前选区”。", "+/- で微調整するか「選択範囲を保存」を押してください。", "सुधारने के लिए +/- का उपयोग करें या 'चयन सहेजें' दबाएँ।", "Utilisez +/- pour retoucher ou 'SAUVEGARDER ZONE'.", "استخدم +/- للتعديل أو اضغط 'حفظ التحديد'.", "Используйте +/- для правки или 'СОХРАНИТЬ ЗОНУ'.")[idxLang]

        val tTotMapRte = arrayOf("PESO TOTAL (.QOI + .HWY):", "TOTAL WEIGHT (.QOI + .HWY):", "总体积 (.QOI + .HWY)：", "総容量 (.QOI + .HWY):", "कुल वज़न (.QOI + .HWY):", "POIDS TOTAL (.QOI + .HWY) :", "الوزن الإجمالي (.QOI + .HWY):", "ОБЩИЙ ВЕС (.QOI + .HWY):")[idxLang]
        val tMapLabel = arrayOf("Mapa:", "Map:", "地图：", "マップ:", "नक्शा:", "Carte :", "الخريطة:", "Карта:")[idxLang]
        val tRteLabel = arrayOf("Rutas:", "Routes:", "路线：", "ルート:", "मार्ग:", "Routes :", "المسارات:", "Маршруты:")[idxLang]
        val tTotQoi = arrayOf("PESO TOTAL POST-QOI:", "TOTAL POST-QOI WEIGHT:", "POST-QOI 总体积：", "POST-QOI 総容量:", "कुल POST-QOI वज़न:", "POIDS TOTAL POST-QOI :", "إجمالي الوزن بعد QOI:", "ОБЩИЙ ВЕС ПОСЛЕ QOI:")[idxLang]
        val tNoRte = arrayOf("(Sin rutas)", "(No routes)", "(不含路线)", "(ルートなし)", "(बिना मार्ग)", "(Sans routes)", "(بدون مسارات)", "(Без маршрутов)")[idxLang]

        val tBtnCountry = arrayOf("TOCA UN PAÍS", "TAP A COUNTRY", "请点击一个国家", "国をタップ", "एक देश टैप करें", "TOUCHEZ UN PAYS", "المس دولة", "ВЫБЕРИТЕ СТРАНУ")[idxLang]
        val tBtnStates = arrayOf("TOCA 1 O MÁS ESTADOS", "TAP 1 OR MORE STATES", "请点击 1 个或多个州/省", "1つ以上の州をタップ", "1 या अधिक राज्य टैप करें", "TOUCHEZ 1 OU PLUSIEURS ÉTATS", "المس ولاية أو أكثر", "ВЫБЕРИТЕ 1 ИЛИ БОЛЕЕ РЕГИОНОВ")[idxLang]
        val tBtnPts = arrayOf("TRAZA 3+ PUNTOS", "DRAW 3+ POINTS", "请绘制至少 3 个顶点", "3点以上を描画", "3+ बिंदु बनाएँ", "TRACEZ 3+ POINTS", "ارسم 3 نقاط أو أكثر", "НАРИСУЙТЕ 3+ ТОЧКИ")[idxLang]
        val tBtnZoom = arrayOf("ELIGE ZOOM Z", "SELECT ZOOM Z", "请选择缩放级别 Z", "ズーム Z を選択", "ज़ूम Z चुनें", "CHOISIR ZOOM Z", "اختر مستوى التكبير Z", "ВЫБЕРИТЕ МАСШТАБ Z")[idxLang]
        val tBtnLimit = arrayOf("CUOTA CARTO EXCEDIDA", "CARTO QUOTA EXCEEDED", "CARTO 配额超限", "CARTO クォータ超過", "CARTO कोटा पार हो गया", "QUOTA CARTO DÉPASSÉ", "تم تجاوز حصة CARTO", "КВОТА CARTO ПРЕВЫШЕНА")[idxLang]

        mainHandler.post {
            val tilesFmt = String.format(Locale.US, "%,d", telemetry.totalTiles)
            val areaFmt = String.format(Locale.US, "%,.1f", telemetry.areaKm2)
            val mapMbFmt = String.format(Locale.US, "%,.2f", telemetry.mapWeightMb)
            val rteMbFmt = String.format(Locale.US, "%,.2f", telemetry.routeWeightMb)
            val totMbFmt = String.format(Locale.US, "%,.2f", telemetry.totalWeightMb)

            if (telemetry.urbanFilterActive && telemetry.unmaskedMapWeightMb > telemetry.mapWeightMb) {
                val rawMbFmt = String.format(Locale.US, "%,.1f", telemetry.unmaskedMapWeightMb)
                val savedPct = ((1.0 - (telemetry.mapWeightMb / telemetry.unmaskedMapWeightMb)) * 100.0).coerceIn(0.0, 99.9)
                val pctFmt = String.format(Locale.US, "%.1f", savedPct)

                txtUrbanStatus.setTextColor(if (lastScanStage2Completed) COLOR_GREEN else COLOR_AMBER)
                txtUrbanStatus.text = "$tOkUrb $scannedCitiesCount $tCities, $scannedTownsCount $tTowns, $scannedCommunitiesCount $tComms.\n" +
                        "$tSaveZ -$pctFmt% ($tDropFrom $rawMbFmt MB $tTo $mapMbFmt MB). $tRetouch"
                txtStatsTiles.text = "$tSecFilt $tilesFmt  |  $tArea $areaFmt km²"
            } else {
                txtStatsTiles.text = "$tSec $tilesFmt  |  $tArea $areaFmt km²"
            }

            txtStatsWeight.text = if (includeRoutes) {
                "$tTotMapRte $totMbFmt MB  ($tMapLabel $mapMbFmt MB + $tRteLabel $rteMbFmt MB)"
            } else {
                "$tTotQoi $totMbFmt MB  $tNoRte"
            }

            if (isStateSelectionMode && selectedStatesList.isEmpty()) {
                txtStatsTiles.setTextColor(COLOR_AMBER)
                btnForjar.isEnabled = false
                btnForjar.setBackgroundColor(Color.DKGRAY)
                btnForjar.setTextColor(Color.LTGRAY)
                btnForjar.text = if (loadedCountryStates.isEmpty()) tBtnCountry else tBtnStates

                btnForjarSoloRutas.isEnabled = false
                btnForjarSoloRutas.setBackgroundColor(Color.DKGRAY)
                btnForjarSoloRutas.setTextColor(Color.LTGRAY)
                return@post
            }

            if (!isStateSelectionMode && isPolygonMode && polygonVertices.size < 3) {
                txtStatsTiles.setTextColor(COLOR_AMBER)
                btnForjar.isEnabled = false
                btnForjar.setBackgroundColor(Color.DKGRAY)
                btnForjar.setTextColor(Color.LTGRAY)
                btnForjar.text = tBtnPts

                btnForjarSoloRutas.isEnabled = false
                btnForjarSoloRutas.setBackgroundColor(Color.DKGRAY)
                btnForjarSoloRutas.setTextColor(Color.LTGRAY)
                return@post
            }

            btnForjarSoloRutas.isEnabled = true
            btnForjarSoloRutas.setBackgroundColor(COLOR_AMBER)
            btnForjarSoloRutas.setTextColor(Color.BLACK)
            btnForjarSoloRutas.text = ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_forge_routes")

            if (activeZooms.isEmpty()) {
                txtStatsTiles.setTextColor(COLOR_AMBER)
                btnForjar.isEnabled = false
                btnForjar.setBackgroundColor(Color.DKGRAY)
                btnForjar.setTextColor(Color.LTGRAY)
                btnForjar.text = tBtnZoom
                return@post
            }

            val effectiveCartoCeiling = if (selectedProviderIdx == 0) remainingCartoFree * 2L else remainingCartoFree
            val exceedsCartoFree = !isCartoPremium && (telemetry.totalTiles > effectiveCartoCeiling)
            if (exceedsCartoFree) {
                txtStatsTiles.setTextColor(COLOR_CRITICO)
                btnForjar.isEnabled = false
                btnForjar.setBackgroundColor(COLOR_CRITICO)
                btnForjar.setTextColor(Color.WHITE)
                btnForjar.text = tBtnLimit
                return@post
            }

            txtStatsTiles.setTextColor(COLOR_CYAN)
            btnForjar.isEnabled = true
            btnForjar.setBackgroundColor(COLOR_CYAN)
            btnForjar.setTextColor(Color.BLACK)
            val multiTag = if (isStateSelectionMode && selectedStatesList.size > 1) " [PC]" else ""
            btnForjar.text = "${ExonedRouterI18n.tr(this@ExonedRouterActivity, "btn_forge_map")} ($totMbFmt MB)$multiTag"
        }
    }

    private fun confirmarYProcederCompilacion(routesOnly: Boolean = false) {
        val zoomsToUse = if (routesOnly) emptySet() else activeZooms
        val includeRoutesFlag = if (routesOnly) true else includeRoutes

        val telemetry = TileMatrixCalculator.evaluateSelection(
            boundNorth, boundSouth, boundEast, boundWest,
            activeCustomRings(), hasActiveCustomPolygon(),
            zoomsToUse, includeRoutesFlag, selectedRouteProfileIdx,
            isolateUrbanHighZoom, scannedUrbanCellsZ16
        )

        val baseName = when {
            isStateSelectionMode && selectedStatesList.isNotEmpty() ->
                selectedStatesList.joinToString("_") { it.name.take(8) }
            isPolygonMode -> "POLIGONO_SAS"
            else -> "ZONA_CAJA"
        }
        val autoName = if (routesOnly) "${baseName}_RUTAS_HWY" else baseName
        val isMultiState = isStateSelectionMode && selectedStatesList.size > 1

        val recipe = ExonedForgeEngine.createRecipe(
            context = this,
            jobName = autoName,
            providerIdx = selectedProviderIdx,
            styleIdx = selectedStyleIdx,
            selectedZooms = zoomsToUse,
            includeRoutes = includeRoutesFlag,
            routeProfileIdx = selectedRouteProfileIdx,
            north = boundNorth,
            south = boundSouth,
            east = boundEast,
            west = boundWest,
            hasCustomPolygon = hasActiveCustomPolygon(),
            selectionRings = activeCustomRings(),
            isolateUrbanHighZoom = isolateUrbanHighZoom,
            urbanZ16Cells = scannedUrbanCellsZ16,
            telemetry = telemetry,
            routesOnly = routesOnly
        )

        ExonedForgeUi.mostrarModalDestinoYForja(this, recipe, forcePcOnly = isMultiState)
    }

    private fun sincronizarCapaMapaWeb() {
        val jsCall = "setMapVisuals($selectedProviderIdx, $selectedStyleIdx);"
        webView.evaluateJavascript(jsCall, null)
    }

    inner class MapBridge {
        @JavascriptInterface
        fun onBoundsChanged(north: Double, south: Double, east: Double, west: Double) {
            liveNorth = north
            liveSouth = south
            liveEast = east
            liveWest = west

            if (!isStateSelectionMode && !isPolygonMode && scannedUrbanCellsZ16.isEmpty()) {
                boundNorth = north
                boundSouth = south
                boundEast = east
                boundWest = west
                actualizarTelemetria()
            }
        }

        @JavascriptInterface
        fun onMapClicked(lat: Double, lng: Double) {
            mainHandler.post {
                if (manualTownBrushMode != 0) {
                    aplicarPincelManualEnCoordenada(lat, lng)
                } else if (isStateSelectionMode) {
                    manejarToqueEnModoEstatal(lat, lng)
                }
            }
        }

        @JavascriptInterface
        fun onPolygonChanged(jsonPoints: String) {
            try {
                val arr = JSONArray(jsonPoints)
                polygonVertices.clear()
                var minLat = Double.MAX_VALUE
                var maxLat = -Double.MAX_VALUE
                var minLon = Double.MAX_VALUE
                var maxLon = -Double.MAX_VALUE

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val lat = obj.getDouble("lat")
                    val lng = obj.getDouble("lng")
                    polygonVertices.add(GeoPoint(lat, lng))
                    minLat = min(minLat, lat)
                    maxLat = max(maxLat, lat)
                    minLon = min(minLon, lng)
                    maxLon = max(maxLon, lng)
                }

                if (polygonVertices.size >= 3) {
                    boundNorth = maxLat
                    boundSouth = minLat
                    boundEast = maxLon
                    boundWest = minLon
                }

                mainHandler.post {
                    txtPolyHint.text = if (polygonVertices.size < 3) {
                        "Puntos: ${polygonVertices.size} (Faltan ${3 - polygonVertices.size} para cerrar)"
                    } else {
                        "Polígono: ${polygonVertices.size} puntos (Arrástralos para ajustar)"
                    }
                }
                actualizarTelemetria()
            } catch (_: Exception) {}
        }
    }

    private fun buildLeafletHtml(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
                <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
                <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
                <style>
                    html, body, #map { margin: 0; padding: 0; width: 100%; height: 100%; background: #02080A; }
                    .leaflet-control-attribution, .leaflet-control-zoom { display: none !important; }
                    .exo-vertex-icon {
                        width: 18px !important;
                        height: 18px !important;
                        margin-left: -9px !important;
                        margin-top: -9px !important;
                        border-radius: 50%;
                        background: #00E5FF;
                        border: 3px solid #00FF41;
                        box-shadow: 0 0 8px #00FF41;
                        box-sizing: border-box;
                    }
                </style>
            </head>
            <body>
                <div id="map"></div>
                <script>
                    var map = L.map('map', {
                        zoomControl: false,
                        attributionControl: false,
                        zoomSnap: 1,
                        zoomDelta: 1,
                        preferCanvas: true
                    }).setView([19.54, -96.92], 10);

                    var currentLayer = L.tileLayer('https://exosys.tile/0/0/{z}/{x}/{y}.png', {
                        tileSize: 256,
                        maxZoom: 20
                    }).addTo(map);

                    var currentMode = 'BOX';
                    var pts = [];
                    var markers = [];
                    var reticleHalfW = 110;
                    var reticleHalfH = 77.5;
                    var polyLayer = L.polygon([], { color: '#00E5FF', weight: 2.5, fillColor: '#00E5FF', fillOpacity: 0.16 });

                    var lockedBoxGroup = L.layerGroup().addTo(map);
                    var countryBlinkGroup = L.layerGroup().addTo(map);
                    var statesGridGroup = L.layerGroup().addTo(map);
                    var selectedStateGroup = L.layerGroup().addTo(map);
                    var urbanMaskGroup = L.layerGroup().addTo(map);
                    var canvasRenderer = L.canvas({ padding: 0.5 });

                    var blinkInterval = null;
                    var blinkPolys = [];

                    var vertexIcon = L.divIcon({ className: 'exo-vertex-icon' });

                    function setReticleBoxSize(hw, hh) {
                        reticleHalfW = hw;
                        reticleHalfH = hh;
                        reportReticleBounds();
                    }

                    function setMapVisuals(providerIdx, styleIdx) {
                        map.removeLayer(currentLayer);
                        currentLayer = L.tileLayer('https://exosys.tile/' + providerIdx + '/' + styleIdx + '/{z}/{x}/{y}.png', {
                            tileSize: 256,
                            maxZoom: 20
                        }).addTo(map);
                    }

                    function setMainMode(modeStr) {
                        currentMode = modeStr;
                        if (modeStr === 'BOX') {
                            if (map.hasLayer(polyLayer)) map.removeLayer(polyLayer);
                            for (var i = 0; i < markers.length; i++) {
                                if (map.hasLayer(markers[i])) map.removeLayer(markers[i]);
                            }
                            clearStateModeLayers();
                            reportReticleBounds();
                        } else if (modeStr === 'POLY') {
                            lockedBoxGroup.clearLayers();
                            clearStateModeLayers();
                            if (!map.hasLayer(polyLayer)) polyLayer.addTo(map);
                            for (var j = 0; j < markers.length; j++) {
                                if (!map.hasLayer(markers[j])) markers[j].addTo(map);
                            }
                            syncPolygonToAndroid();
                        } else if (modeStr === 'STATE') {
                            lockedBoxGroup.clearLayers();
                            if (map.hasLayer(polyLayer)) map.removeLayer(polyLayer);
                            for (var k = 0; k < markers.length; k++) {
                                if (map.hasLayer(markers[k])) map.removeLayer(markers[k]);
                            }
                        }
                    }

                    function startCountryBlinkingRed(ringsJson, south, west, north, east) {
                        stopCountryBlinkingRed();
                        statesGridGroup.clearLayers();
                        try {
                            var rings = JSON.parse(ringsJson);
                            for (var i = 0; i < rings.length; i++) {
                                var p = L.polygon(rings[i], {
                                    renderer: canvasRenderer,
                                    color: '#FF003C',
                                    weight: 2.2,
                                    fillColor: '#FF003C',
                                    fillOpacity: 0.30,
                                    interactive: false
                                }).addTo(countryBlinkGroup);
                                blinkPolys.push(p);
                            }
                            map.fitBounds([[south, west], [north, east]], { padding: [15, 15] });

                            var high = true;
                            blinkInterval = setInterval(function() {
                                high = !high;
                                var op = high ? 0.34 : 0.07;
                                for (var j = 0; j < blinkPolys.length; j++) {
                                    blinkPolys[j].setStyle({ fillOpacity: op });
                                }
                            }, 550);
                        } catch (e) {}
                    }

                    function stopCountryBlinkingRed() {
                        if (blinkInterval) {
                            clearInterval(blinkInterval);
                            blinkInterval = null;
                        }
                        blinkPolys = [];
                        countryBlinkGroup.clearLayers();
                    }

                    function showCountryStatesGrid(allStatesJson) {
                        stopCountryBlinkingRed();
                        statesGridGroup.clearLayers();
                        try {
                            var states = JSON.parse(allStatesJson);
                            for (var i = 0; i < states.length; i++) {
                                var stRings = states[i];
                                for (var r = 0; r < stRings.length; r++) {
                                    L.polygon(stRings[r], {
                                        renderer: canvasRenderer,
                                        color: '#00E5FF',
                                        weight: 1.3,
                                        fillColor: '#00E5FF',
                                        fillOpacity: 0.04,
                                        interactive: false
                                    }).addTo(statesGridGroup);
                                }
                            }
                        } catch (e) {}
                    }

                    function highlightMultipleStatesGreen(multiStatesRingsJson, south, west, north, east, shouldFit) {
                        selectedStateGroup.clearLayers();
                        try {
                            var statesArr = JSON.parse(multiStatesRingsJson);
                            for (var s = 0; s < statesArr.length; s++) {
                                var rings = statesArr[s];
                                for (var i = 0; i < rings.length; i++) {
                                    L.polygon(rings[i], {
                                        renderer: canvasRenderer,
                                        color: '#00FF41',
                                        weight: 2.5,
                                        fillColor: '#00FF41',
                                        fillOpacity: 0.16,
                                        interactive: false
                                    }).addTo(selectedStateGroup);
                                }
                            }
                            if (shouldFit) {
                                map.fitBounds([[south, west], [north, east]], { padding: [18, 18] });
                            }
                        } catch (e) {}
                    }

                    function clearSelectedStatesGreen() {
                        selectedStateGroup.clearLayers();
                    }

                    function clearStateModeLayers() {
                        stopCountryBlinkingRed();
                        statesGridGroup.clearLayers();
                        selectedStateGroup.clearLayers();
                    }

                    function drawLockedBox(south, west, north, east) {
                        lockedBoxGroup.clearLayers();
                        L.rectangle([[south, west], [north, east]], {
                            renderer: canvasRenderer,
                            color: '#00E5FF',
                            weight: 2.5,
                            fillColor: '#00E5FF',
                            fillOpacity: 0.04,
                            interactive: false
                        }).addTo(lockedBoxGroup);
                    }

                    function drawUrbanMask(polygonsWithHolesJson) {
                        urbanMaskGroup.clearLayers();
                        try {
                            var polys = JSON.parse(polygonsWithHolesJson);
                            for (var i = 0; i < polys.length; i++) {
                                L.polygon(polys[i], {
                                    renderer: canvasRenderer,
                                    color: '#00FF41',
                                    weight: 1.8,
                                    fillColor: '#00FF41',
                                    fillOpacity: 0.20,
                                    interactive: false
                                }).addTo(urbanMaskGroup);
                            }
                        } catch (e) {}
                    }

                    function clearUrbanMask() {
                        urbanMaskGroup.clearLayers();
                        lockedBoxGroup.clearLayers();
                    }

                    function syncPolygonToAndroid() {
                        polyLayer.setLatLngs(pts);
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onPolygonChanged(JSON.stringify(pts));
                        }
                    }

                    function createDraggableVertex(latlng, index) {
                        var marker = L.marker(latlng, {
                            draggable: true,
                            icon: vertexIcon
                        }).addTo(map);

                        marker.exoIndex = index;

                        marker.on('drag', function(ev) {
                            var pos = ev.target.getLatLng();
                            pts[marker.exoIndex] = { lat: pos.lat, lng: pos.lng };
                            polyLayer.setLatLngs(pts);
                        });

                        marker.on('dragend', function(ev) {
                            var pos = ev.target.getLatLng();
                            pts[marker.exoIndex] = { lat: pos.lat, lng: pos.lng };
                            syncPolygonToAndroid();
                        });

                        return marker;
                    }

                    function restoreSavedPolygon(ptsJson, south, west, north, east) {
                        clearPolygon();
                        try {
                            var arr = JSON.parse(ptsJson);
                            for (var i = 0; i < arr.length; i++) {
                                var ll = L.latLng(arr[i].lat, arr[i].lng);
                                pts.push({ lat: ll.lat, lng: ll.lng });
                                var m = createDraggableVertex(ll, i);
                                markers.push(m);
                            }
                            syncPolygonToAndroid();
                            map.fitBounds([[south, west], [north, east]], { padding: [18, 18] });
                        } catch (e) {}
                    }

                    function undoLastVertex() {
                        if (pts.length > 0) {
                            pts.pop();
                            var m = markers.pop();
                            if (m) map.removeLayer(m);
                            syncPolygonToAndroid();
                        }
                    }

                    function clearPolygon() {
                        pts = [];
                        for (var i = 0; i < markers.length; i++) map.removeLayer(markers[i]);
                        markers = [];
                        syncPolygonToAndroid();
                    }

                    map.on('click', function(e) {
                        if (currentMode === 'POLY') {
                            var newIdx = pts.length;
                            pts.push({ lat: e.latlng.lat, lng: e.latlng.lng });
                            var m = createDraggableVertex(e.latlng, newIdx);
                            markers.push(m);
                            syncPolygonToAndroid();
                        } else if (window.AndroidBridge) {
                            window.AndroidBridge.onMapClicked(e.latlng.lat, e.latlng.lng);
                        }
                    });

                    function reportReticleBounds() {
                        if (currentMode !== 'BOX') return;
                        var size = map.getSize();
                        if (size.x <= 0 || size.y <= 0) return;
                        var cx = size.x / 2;
                        var cy = size.y / 2;
                        var nw = map.containerPointToLatLng([cx - reticleHalfW, cy - reticleHalfH]);
                        var se = map.containerPointToLatLng([cx + reticleHalfW, cy + reticleHalfH]);
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onBoundsChanged(nw.lat, se.lat, se.lng, nw.lng);
                        }
                    }

                    map.on('move', reportReticleBounds);
                    map.on('zoomend', reportReticleBounds);
                    setTimeout(reportReticleBounds, 350);
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun crearTarjetaSeccion(titulo: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(COLOR_CARD)
                setStroke(dp(1), COLOR_DARK)
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }

            val hdr = TextView(this@ExonedRouterActivity).apply {
                text = titulo
                setTextColor(COLOR_CYAN)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(6))
            }
            addView(hdr)
        }
    }

    private fun crearSpinner(items: List<String>, defaultIdx: Int, onSelected: (Int) -> Unit): Spinner {
        return Spinner(this).apply {
            setBackgroundColor(COLOR_DARK)
            adapter = ArrayAdapter(this@ExonedRouterActivity, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(defaultIdx.coerceIn(0, items.size - 1))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    (view as? TextView)?.apply {
                        setTextColor(Color.WHITE)
                        textSize = 12.5f
                    }
                    onSelected(position)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}