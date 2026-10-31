package com.example.exosys

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

object CartoApiManager {

    private const val PREFS_NAME = "ExosysCartoPrefs"
    private const val KEY_API = "carto_api_key"
    private const val KEY_VERIFIED = "carto_api_verified"
    private const val KEY_SKIP_CARTO = "carto_skip_mode"
    private const val KEY_SAVED_TIMESTAMP = "carto_saved_timestamp"
    private const val KEY_TILES_USED = "carto_tiles_month_count"
    private const val KEY_MONTH_STAMP = "carto_tiles_month_stamp"
    private const val KEY_PREMIUM = "carto_is_premium"

    // Llaves dinámicas del Nodo Primario (OTA)
    private const val KEY_NODE_ID = "primary_node_id"
    private const val KEY_PRIMARY_TILE_TPL = "primary_tile_template"
    private const val KEY_PRIMARY_REFERER = "primary_tile_referer"

    const val SAFE_FREE_LIMIT = 4_750_000L
    const val CARTO_HARD_LIMIT = 5_000_000L
    private const val TRIAL_DURATION_DAYS = 14L

    private val COLOR_BG = Color.parseColor("#0A1114")
    private val COLOR_CYAN = Color.parseColor("#00E5FF")
    private val COLOR_GREEN = Color.parseColor("#00FF41")
    private val COLOR_AMBER = Color.parseColor("#FFB300")
    private val COLOR_RED = Color.parseColor("#FF003C")
    private val COLOR_DARK_FIELD = Color.parseColor("#050A0C")

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // =========================================================================
    // GESTIÓN DEL NODO PRIMARIO (INYECCIÓN OTA DESDE GITHUB PAGES)
    // =========================================================================
    fun hasPrimaryNode(ctx: Context): Boolean {
        val tpl = prefs(ctx).getString(KEY_PRIMARY_TILE_TPL, "") ?: ""
        return tpl.contains("{x}") && tpl.contains("{y}") && tpl.contains("{z}")
    }

    fun getPrimaryNodeId(ctx: Context): String {
        return prefs(ctx).getString(KEY_NODE_ID, "") ?: ""
    }

    fun getPrimaryTileTemplate(ctx: Context): String {
        return prefs(ctx).getString(KEY_PRIMARY_TILE_TPL, "") ?: ""
    }

    fun getPrimaryReferer(ctx: Context): String {
        val ref = prefs(ctx).getString(KEY_PRIMARY_REFERER, "") ?: ""
        return ref.ifBlank { "https://exosys.local/" }
    }

    fun buildPrimaryTileUrl(
        ctx: Context,
        x: Int,
        y: Int,
        z: Int,
        styleIdx: Int,
        mirrorOverride: Int? = null,
        scale: Int = 2
    ): String? {
        val tpl = getPrimaryTileTemplate(ctx)
        if (tpl.isBlank()) return null

        val mtSub = (mirrorOverride ?: (x + y)) and 3
        val lyrs = if (styleIdx == 3) "y" else "m"

        var built = tpl
            .replace("{s}", mtSub.toString())
            .replace("{type}", lyrs)
            .replace("{lyrs}", lyrs)
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
            .replace("{z}", z.toString())

        if (!built.contains("scale=")) {
            built += "&scale=$scale&hl=es"
        }
        return built
    }

    private fun resolverUrlNodo(rawInput: String): String {
        val clean = rawInput.trim()
        if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) {
            return if (clean.endsWith(".json", ignoreCase = true)) clean else "${clean.trimEnd('/')}/config.json"
        }
        if (clean.contains(".github.io", ignoreCase = true)) {
            val host = clean.removeSuffix("/").removeSuffix("/config.json")
            return "https://$host/config.json"
        }
        return "https://${clean.lowercase(Locale.US)}.github.io/config.json"
    }

    private fun sincronizarNodoEnServidor(rawInput: String, callback: (Boolean, String, String, String) -> Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        thread(start = true, isDaemon = true) {
            var conn: HttpURLConnection? = null
            try {
                val targetUrl = resolverUrlNodo(rawInput)
                conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7000
                    readTimeout = 7000
                    useCaches = false
                    setRequestProperty("User-Agent", "ExosysRouter/2.0")
                }

                val code = conn.responseCode
                if (code != 200) {
                    mainHandler.post {
                        callback(false, "HTTP $code", "", "")
                    }
                    return@thread
                }

                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val json = JSONObject(body)

                val tileTpl = when {
                    json.has("tile_tpl_b64") -> String(Base64.decode(json.getString("tile_tpl_b64"), Base64.DEFAULT), Charsets.UTF_8)
                    json.has("tile_url") -> json.getString("tile_url")
                    else -> ""
                }

                val referer = when {
                    json.has("referer_b64") -> String(Base64.decode(json.getString("referer_b64"), Base64.DEFAULT), Charsets.UTF_8)
                    json.has("referer") -> json.getString("referer")
                    else -> "https://exosys.local/"
                }

                if (tileTpl.contains("{x}") && tileTpl.contains("{y}") && tileTpl.contains("{z}")) {
                    mainHandler.post {
                        callback(true, "OK", tileTpl, referer)
                    }
                } else {
                    mainHandler.post {
                        callback(false, "INVALID_JSON", "", "")
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    callback(false, e.localizedMessage ?: "Timeout", "", "")
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    fun mostrarDialogoSincronizarNodo(ctx: Context, onSuccess: (() -> Unit)? = null) {
        val lang = ExonedForgeI18n.getLang(ctx)
        val idxLang = mapOf(
            "es" to 0, "en" to 1, "zh" to 2, "ja" to 3,
            "hi" to 4, "fr" to 5, "ar" to 6, "ru" to 7
        )[lang] ?: 0

        val tTitle = arrayOf(
            "ACUERDO DE USO",
            "TERMS OF USE",
            "使用协议",
            "利用規約",
            "उपयोग की शर्तें",
            "CONDITIONS D'UTILISATION",
            "اتفاقية الاستخدام",
            "УСЛОВИЯ ИСПОЛЬЗОВАНИЯ"
        )[idxLang]

        val tDesc = arrayOf(
            "Exosys es una plataforma experimental. El uso de banda ancha para la descarga topográfica de servidores externos no tiene costo por parte de nuestro sistema. Escribe 'OK' para confirmar que entiendes y aceptas estos términos.",
            "Exosys is an experimental platform. Bandwidth usage for downloading topographic data from external servers incurs no cost from our system. Type 'OK' to confirm that you understand and accept these terms.",
            "Exosys 是一个实验性平台。使用宽带从外部服务器下载地形数据在本系统中不产生任何费用。请输入 'OK' 确认您理解并接受这些条款。",
            "Exosysは実験的なプラットフォームです。外部サーバーからの地形データダウンロードに伴う通信に、当システムからの課金は一切ありません。規約を理解し同意する場合は「OK」と入力してください。",
            "Exosys एक प्रायोगिक प्लेटफ़ॉर्म है। बाहरी सर्वरों से स्थलाकृतिक डेटा डाउनलोड करने के लिए ब्रॉडबैंड के उपयोग पर हमारे सिस्टम की ओर से कोई शुल्क नहीं है। यह पुष्टि करने के लिए 'OK' टाइप करें कि आप इन शर्तों को समझते और स्वीकार करते हैं।",
            "Exosys est une plateforme expérimentale. L'utilisation de la bande passante pour le téléchargement topographique depuis des serveurs externes n'entraîne aucun coût de la part de notre système. Tapez 'OK' pour confirmer que vous comprenez et acceptez ces conditions.",
            "منصة Exosys هي منصة تجريبية. استخدام النطاق الترددي لتنزيل البيانات الطبوغرافية من الخوادم الخارجية لا يترتب عليه أي تكلفة من نظامنا. اكتب 'OK' لتأكيد فهمك وقبولك لهذه الشروط.",
            "Exosys — экспериментальная платформа. Использование трафика для загрузки топографических данных с внешних серверов абсолютно бесплатно со стороны нашей системы. Введите 'OK', чтобы подтвердить, что вы понимаете и принимаете эти условия."
        )[idxLang]

        val tHint = arrayOf(
            "Escribe 'OK' aquí",
            "Type 'OK' here",
            "在此输入 'OK'",
            "ここに「OK」と入力",
            "यहाँ 'OK' टाइप करें",
            "Tapez 'OK' ici",
            "اكتب 'OK' هنا",
            "Введите 'OK' здесь"
        )[idxLang]

        val tBtnAccept = arrayOf(
            "ACEPTAR TÉRMINOS",
            "ACCEPT TERMS",
            "接受条款",
            "規約に同意する",
            "शर्तें स्वीकार करें",
            "ACCEPTER LES CONDITIONS",
            "قبول الشروط",
            "ПРИНЯТЬ УСЛОВИЯ"
        )[idxLang]

        val tBtnCancel = arrayOf(
            "CANCELAR",
            "CANCEL",
            "取消",
            "キャンセル",
            "रद्द करें",
            "ANNULER",
            "إلغاء",
            "ОТМЕНА"
        )[idxLang]

        val tErrTypeOk = arrayOf(
            "Error: Debes escribir 'OK' para confirmar y continuar.",
            "Error: You must type 'OK' to confirm and continue.",
            "错误：您必须输入 'OK' 才能确认并继续。",
            "エラー: 確認して続行するには「OK」と入力してください。",
            "त्रुटि: पुष्टि करने और जारी रखने के लिए आपको 'OK' टाइप करना होगा।",
            "Erreur : Vous devez taper 'OK' pour confirmer et continuer.",
            "خطأ: يجب كتابة 'OK' للتأكيد والمتابعة.",
            "Ошибка: Необходимо ввести 'OK' для подтверждения и продолжения."
        )[idxLang]

        val tValidating = arrayOf(
            "Validando acuerdo de uso...",
            "Validating terms of use...",
            "正在验证使用协议...",
            "利用規約を確認中...",
            "उपयोग की शर्तों को मान्य किया जा रहा है...",
            "Validation des conditions d'utilisation...",
            "جارٍ التحقق من اتفاقية الاستخدام...",
            "Проверка условий использования..."
        )[idxLang]

        val tToastOk = arrayOf(
            "Términos aceptados. Motor activado.",
            "Terms accepted. Engine activated.",
            "条款已接受。引擎已激活。",
            "規約に同意しました。エンジンが有効化されました。",
            "शर्तें स्वीकार की गईं। इंजन सक्रिय हुआ।",
            "Conditions acceptées. Moteur activé.",
            "تم قبول الشروط. تم تفعيل المحرك.",
            "Условия приняты. Движок активирован."
        )[idxLang]

        val tErrNet = arrayOf(
            "Error de red al validar parámetros. Reintenta.",
            "Network error while validating parameters. Retry.",
            "验证参数时发生网络错误，请重试。",
            "パラメータ検証中にネットワークエラーが発生しました。再試行してください。",
            "पैरामीटर सत्यापित करते समय नेटवर्क त्रुटि। पुनः प्रयास करें।",
            "Erreur réseau lors de la validation des paramètres. Réessayez.",
            "خطأ في الشبكة أثناء التحقق من المعلمات. أعد المحاولة.",
            "Ошибка сети при проверке параметров. Повторите попытку."
        )[idxLang]

        val root = crearContenedorDialogo(ctx)

        val titulo = TextView(ctx).apply {
            text = tTitle
            setTextColor(COLOR_CYAN)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(ctx, 8))
        }

        val desc = TextView(ctx).apply {
            text = tDesc
            setTextColor(Color.WHITE)
            textSize = 13f
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, dp(ctx, 12))
        }

        val inputNodo = EditText(ctx).apply {
            hint = tHint
            setHintTextColor(Color.GRAY)
            setTextColor(COLOR_GREEN)
            setText("")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
            background = GradientDrawable().apply {
                setColor(COLOR_DARK_FIELD)
                setStroke(dp(ctx, 1), COLOR_CYAN)
                cornerRadius = dp(ctx, 6).toFloat()
            }
        }

        val txtEstado = TextView(ctx).apply {
            text = ""
            setTextColor(COLOR_AMBER)
            textSize = 12.5f
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 10))
        }

        val btnConectar = crearBoton(ctx, tBtnAccept, COLOR_GREEN, Color.BLACK)
        val spacer1 = crearEspacio(ctx, 8)
        val btnCancelar = crearBoton(ctx, tBtnCancel, Color.DKGRAY, Color.WHITE)

        root.addView(titulo)
        root.addView(desc)
        root.addView(inputNodo)
        root.addView(txtEstado)
        root.addView(btnConectar)
        root.addView(spacer1)
        root.addView(btnCancelar)

        val dialog = AlertDialog.Builder(ctx).setView(root).setCancelable(false).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnConectar.setOnClickListener {
            val raw = inputNodo.text.toString().trim().uppercase(Locale.US)
            if (raw != "OK" && raw != "ACEPTO") {
                txtEstado.setTextColor(COLOR_RED)
                txtEstado.text = tErrTypeOk
                return@setOnClickListener
            }

            btnConectar.isEnabled = false
            txtEstado.setTextColor(COLOR_AMBER)
            txtEstado.text = tValidating

            sincronizarNodoEnServidor("TheExoned") { ok, _, tileTpl, referer ->
                btnConectar.isEnabled = true
                if (ok) {
                    prefs(ctx).edit()
                        .putString(KEY_NODE_ID, "TheExoned")
                        .putString(KEY_PRIMARY_TILE_TPL, tileTpl)
                        .putString(KEY_PRIMARY_REFERER, referer)
                        .apply()

                    Toast.makeText(ctx, tToastOk, Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    onSuccess?.invoke()
                } else {
                    txtEstado.setTextColor(COLOR_RED)
                    txtEstado.text = tErrNet
                }
            }
        }

        btnCancelar.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    // =========================================================================
    // GESTIÓN DEL SERVIDOR DE RESPALDO (CARTO API)
    // =========================================================================
    fun hasVerifiedApi(ctx: Context): Boolean {
        val p = prefs(ctx)
        val key = p.getString(KEY_API, "") ?: ""
        return p.getBoolean(KEY_VERIFIED, false) && key.isNotBlank()
    }

    fun isCartoSkipped(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SKIP_CARTO, false)

    fun getApiKey(ctx: Context): String = prefs(ctx).getString(KEY_API, "") ?: ""

    fun isPremiumAccount(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_PREMIUM, false)

    fun setPremiumAccount(ctx: Context, premium: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_PREMIUM, premium).apply()
    }

    fun getRemainingTrialDays(ctx: Context): Long {
        val savedAt = prefs(ctx).getLong(KEY_SAVED_TIMESTAMP, 0L)
        if (savedAt == 0L) return TRIAL_DURATION_DAYS
        val elapsedDays = (System.currentTimeMillis() - savedAt) / (1000L * 60L * 60L * 24L)
        return (TRIAL_DURATION_DAYS - elapsedDays).coerceAtLeast(0L)
    }

    fun getMonthlyUsedTiles(ctx: Context): Long {
        val currentMonth = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        val p = prefs(ctx)
        val savedMonth = p.getString(KEY_MONTH_STAMP, "")
        if (savedMonth != currentMonth) {
            p.edit().putString(KEY_MONTH_STAMP, currentMonth).putLong(KEY_TILES_USED, 0L).apply()
            return 0L
        }
        return p.getLong(KEY_TILES_USED, 0L)
    }

    fun addUsedTiles(ctx: Context, count: Long) {
        val current = getMonthlyUsedTiles(ctx)
        prefs(ctx).edit().putLong(KEY_TILES_USED, current + count).apply()
    }

    fun abrirObtencionDeMapas(ctx: Context) {
        val continuarFlujoCarto = {
            if (hasVerifiedApi(ctx) || isCartoSkipped(ctx)) {
                ctx.startActivity(Intent(ctx, ExonedRouterActivity::class.java))
            } else {
                mostrarPopupPreguntaInicial(ctx) {
                    ctx.startActivity(Intent(ctx, ExonedRouterActivity::class.java))
                }
            }
        }

        if (!hasPrimaryNode(ctx)) {
            mostrarDialogoSincronizarNodo(ctx, onSuccess = continuarFlujoCarto)
        } else {
            continuarFlujoCarto()
        }
    }

    private fun mostrarPopupPreguntaInicial(ctx: Context, onReadyToNavigate: () -> Unit) {
        val root = crearContenedorDialogo(ctx)

        val titulo = TextView(ctx).apply {
            text = "RESPALDO DE MAPAS (CARTO)"
            setTextColor(COLOR_CYAN)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(ctx, 10))
        }

        val mensaje = TextView(ctx).apply {
            text = "Para contar con el servidor de respaldo de Carto necesitamos conectar una llave de acceso (API).\n\n" +
                    "¿Ya tienes tu llave API de Carto?"
            setTextColor(Color.WHITE)
            textSize = 14f
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, dp(ctx, 18))
        }

        val btnSi = crearBoton(ctx, "SÍ, YA LA TENGO", COLOR_GREEN, Color.BLACK)
        val spacer1 = crearEspacio(ctx, 10)
        val btnNo = crearBoton(ctx, "NO, ¿CÓMO LA CONSIGO?", COLOR_CYAN, Color.BLACK)
        val spacer2 = crearEspacio(ctx, 10)
        val btnSinCarto = crearBoton(ctx, "CONTINUAR SIN CARTO", Color.DKGRAY, COLOR_AMBER)

        root.addView(titulo)
        root.addView(mensaje)
        root.addView(btnSi)
        root.addView(spacer1)
        root.addView(btnNo)
        root.addView(spacer2)
        root.addView(btnSinCarto)

        val dialog = AlertDialog.Builder(ctx).setView(root).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnSi.setOnClickListener {
            dialog.dismiss()
            mostrarDialogoIngresarApi(ctx, onSuccess = onReadyToNavigate)
        }

        btnNo.setOnClickListener {
            dialog.dismiss()
            mostrarTutorialPasoAPaso(ctx, onReadyToNavigate)
        }

        btnSinCarto.setOnClickListener {
            dialog.dismiss()
            mostrarAdvertenciaSinCarto(ctx, onReadyToNavigate)
        }

        dialog.show()
    }

    private fun mostrarAdvertenciaSinCarto(ctx: Context, onReadyToNavigate: () -> Unit) {
        val root = crearContenedorDialogo(ctx)

        val titulo = TextView(ctx).apply {
            text = "AVISO: CONTINUAR SIN CARTO"
            setTextColor(COLOR_AMBER)
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(ctx, 10))
        }

        val explicacion = TextView(ctx).apply {
            text = "• Carto funciona como el respaldo (backup) para el Motor Primario.\n\n" +
                    "• No se recomienda omitir la API, porque si descargas un área gigante y el servidor primario limita temporalmente tu conexión, no tendrás a Carto para completar los cuadros faltantes.\n\n" +
                    "• Sin embargo, NO es necesaria para usar el sistema con el Motor Primario y puedes añadirla cuando quieras desde el menú \"4. AJUSTES\"."
            setTextColor(Color.WHITE)
            textSize = 13.5f
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, dp(ctx, 18))
        }

        val btnEntendido = crearBoton(ctx, "ENTENDIDO, CONTINUAR SIN CARTO", COLOR_AMBER, Color.BLACK)
        val spacer1 = crearEspacio(ctx, 10)
        val btnConfigurar = crearBoton(ctx, "MEJOR OBTENER MI API AHORA", COLOR_CYAN, Color.BLACK)

        root.addView(titulo)
        root.addView(explicacion)
        root.addView(btnEntendido)
        root.addView(spacer1)
        root.addView(btnConfigurar)

        val dialog = AlertDialog.Builder(ctx).setView(root).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnEntendido.setOnClickListener {
            prefs(ctx).edit().putBoolean(KEY_SKIP_CARTO, true).apply()
            dialog.dismiss()
            onReadyToNavigate()
        }

        btnConfigurar.setOnClickListener {
            dialog.dismiss()
            mostrarTutorialPasoAPaso(ctx, onReadyToNavigate)
        }

        dialog.show()
    }

    private fun mostrarTutorialPasoAPaso(ctx: Context, onReadyToNavigate: (() -> Unit)? = null) {
        val outer = crearContenedorDialogo(ctx)

        val titulo = TextView(ctx).apply {
            text = "CÓMO OBTENER Y RENOVAR TU API"
            setTextColor(COLOR_AMBER)
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(ctx, 8))
        }

        val textoPasos = TextView(ctx).apply {
            text = "⚠️ IMPORTANTE SOBRE EL FREE TRIAL (14 DÍAS):\n" +
                    "Al registrarte en Carto se inicia una prueba gratuita (Free Trial) de 14 días sin pedir tarjeta de crédito. Pasados los 14 días, la llave expira.\n\n" +
                    "🔄 ¿CÓMO RECUPERARLA CUANDO EXPIRE?\n" +
                    "Cuando se acaben los 14 días, inicia sesión en Carto con otro correo (o elimina tu cuenta desde Settings en Carto y vuelve a entrar), repite estos pasos en 1 minuto y pega la llave nueva en el menú \"4. AJUSTES\".\n\n" +
                    "----------------------------------------\n" +
                    "PASOS EXACTOS PARA SACAR LA LLAVE:\n\n" +
                    "1. Toca \"IR AL SITIO\" abajo. En Carto, dale a \"Log in\" y entra con tu cuenta.\n\n" +
                    "2. En \"Organization name\" escribe cualquier nombre único y dale a \"Continue\".\n\n" +
                    "3. En las 3 preguntas de encuesta elige cualquier opción hasta llegar a la pantalla principal (Workspace).\n\n" +
                    "4. En la barra izquierda, toca el ícono \"< >\" (Developers) y entra en \"Credentials\".\n\n" +
                    "5. Toca el botón azul \"+ Create new\" (arriba a la derecha).\n\n" +
                    "6. Verás que \"Maps API\" está bloqueado en gris. Para destrabarlo, baja hasta el fondo donde dice \"Grants -> Choose a connection\" y selecciona \"carto_dw\".\n\n" +
                    "7. Regresa un poco hacia arriba, enciende el interruptor de \"Maps API\" y toca el botón azul \"Save\".\n\n" +
                    "8. En la lista donde aparece tu llave, toca los 3 puntitos verticales (⋮) a la derecha, selecciona \"Copy\" y pégala en esta app."
            setTextColor(Color.WHITE)
            textSize = 12.8f
            setLineSpacing(0f, 1.2f)
        }

        val scrollPasos = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 310))
            setPadding(0, 0, 0, dp(ctx, 12))
            addView(textoPasos)
        }

        val btnIrAlSitio = crearBoton(ctx, "1. IR AL SITIO (CARTO.COM)", COLOR_AMBER, Color.BLACK)
        val spacer1 = crearEspacio(ctx, 8)
        val btnYaLaCopie = crearBoton(ctx, "2. YA LA COPIÉ, PEGARLA AHORA", COLOR_GREEN, Color.BLACK)
        val spacer2 = crearEspacio(ctx, 8)
        val btnSinCarto = crearBoton(ctx, "CONTINUAR SIN CARTO", Color.DKGRAY, COLOR_CYAN)

        outer.addView(titulo)
        outer.addView(scrollPasos)
        outer.addView(btnIrAlSitio)
        outer.addView(spacer1)
        outer.addView(btnYaLaCopie)
        outer.addView(spacer2)
        outer.addView(btnSinCarto)

        val dialog = AlertDialog.Builder(ctx).setView(outer).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnIrAlSitio.setOnClickListener {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://app.carto.com/")))
            } catch (_: Exception) {
                Toast.makeText(ctx, "No se pudo abrir el navegador.", Toast.LENGTH_SHORT).show()
            }
        }

        btnYaLaCopie.setOnClickListener {
            dialog.dismiss()
            mostrarDialogoIngresarApi(ctx, onSuccess = onReadyToNavigate)
        }

        btnSinCarto.setOnClickListener {
            dialog.dismiss()
            if (onReadyToNavigate != null) {
                mostrarAdvertenciaSinCarto(ctx, onReadyToNavigate)
            }
        }

        dialog.show()
    }

    fun mostrarDialogoIngresarApi(ctx: Context, onSuccess: (() -> Unit)? = null) {
        val root = crearContenedorDialogo(ctx)

        val titulo = TextView(ctx).apply {
            text = "INGRESAR LLAVE API DE CARTO"
            setTextColor(COLOR_CYAN)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(ctx, 8))
        }

        val desc = TextView(ctx).apply {
            text = "Recuerda copiarla desde los 3 puntitos (⋮) para que esté completa. Al guardarla, registraremos tus 14 días de Free Trial."
            setTextColor(Color.LTGRAY)
            textSize = 13f
            setPadding(0, 0, 0, dp(ctx, 12))
        }

        val inputApi = EditText(ctx).apply {
            hint = "Pega aquí tu Token (ej. eyJhbGci...)"
            setHintTextColor(Color.GRAY)
            setTextColor(COLOR_GREEN)
            setText(getApiKey(ctx))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
            background = GradientDrawable().apply {
                setColor(COLOR_DARK_FIELD)
                setStroke(dp(ctx, 1), COLOR_CYAN)
                cornerRadius = dp(ctx, 6).toFloat()
            }
        }

        val txtEstado = TextView(ctx).apply {
            text = ""
            setTextColor(COLOR_AMBER)
            textSize = 12.5f
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 10))
        }

        val btnVerificar = crearBoton(ctx, "VERIFICAR Y GUARDAR", COLOR_GREEN, Color.BLACK)
        val spacer1 = crearEspacio(ctx, 8)
        val btnTutorial = crearBoton(ctx, "VER PASOS Y CÓMO RENOVAR TRIAL", COLOR_CYAN, Color.BLACK)
        val spacer2 = crearEspacio(ctx, 8)
        val btnCancelar = crearBoton(ctx, "CANCELAR", Color.DKGRAY, Color.WHITE)

        root.addView(titulo)
        root.addView(desc)
        root.addView(inputApi)
        root.addView(txtEstado)
        root.addView(btnVerificar)
        root.addView(spacer1)
        root.addView(btnTutorial)
        root.addView(spacer2)
        root.addView(btnCancelar)

        val dialog = AlertDialog.Builder(ctx).setView(root).setCancelable(false).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnVerificar.setOnClickListener {
            val candidateKey = inputApi.text.toString().trim()
            if (candidateKey.endsWith("...")) {
                txtEstado.setTextColor(COLOR_RED)
                txtEstado.text = "Tu llave está cortada ('...'). Cópiala desde los 3 puntitos (⋮) a la derecha en la web de Carto."
                return@setOnClickListener
            }
            if (candidateKey.length < 12) {
                txtEstado.setTextColor(COLOR_RED)
                txtEstado.text = "La llave es demasiado corta. Verifica haberla copiado completa."
                return@setOnClickListener
            }

            btnVerificar.isEnabled = false
            txtEstado.setTextColor(COLOR_AMBER)
            txtEstado.text = "Comprobando llave con el servidor de Carto..."

            verificarLlaveEnServidor(candidateKey) { esValida, detalle ->
                btnVerificar.isEnabled = true
                if (esValida) {
                    prefs(ctx).edit()
                        .putString(KEY_API, candidateKey)
                        .putBoolean(KEY_VERIFIED, true)
                        .putBoolean(KEY_SKIP_CARTO, false)
                        .putLong(KEY_SAVED_TIMESTAMP, System.currentTimeMillis())
                        .apply()

                    Toast.makeText(ctx, "¡Llave activa! Trial de 14 días registrado.", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    onSuccess?.invoke()
                } else {
                    txtEstado.setTextColor(COLOR_RED)
                    txtEstado.text = detalle
                }
            }
        }

        btnTutorial.setOnClickListener {
            dialog.dismiss()
            mostrarTutorialPasoAPaso(ctx, onSuccess)
        }

        btnCancelar.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    fun mostrarMenuAjustes(ctx: Context) {
        val lang = ExonedForgeI18n.getLang(ctx)
        val idxLang = mapOf("es" to 0, "en" to 1, "zh" to 2, "ja" to 3, "hi" to 4, "fr" to 5, "ar" to 6, "ru" to 7)[lang] ?: 0

        // Extraemos el cálculo para no llamar la función 8 veces en el array
        val days = getRemainingTrialDays(ctx)

        val tTitle = arrayOf("AJUSTES DEL SISTEMA", "SYSTEM SETTINGS", "系统设置", "システム設定", "सिस्टम सेटिंग्स", "PARAMÈTRES SYSTÈME", "إعدادات النظام", "СИСТЕМНЫЕ НАСТРОЙКИ")[idxLang]
        val tNodeOk = arrayOf("Conectado y Autorizado", "Connected and Authorized", "已连接并授权", "接続および認証済み", "कनेक्टेड और अधिकृत", "Connecté et Autorisé", "متصل ومصرح به", "Подключено и авторизовано")[idxLang]
        val tNodePend = arrayOf("Pendiente de autorización", "Pending authorization", "等待授权", "認証待ち", "प्राधिकरण लंबित", "En attente d'autorisation", "في انتظار التصريح", "Ожидание авторизации")[idxLang]
        val tNodeLabel = arrayOf("• Motor Primario (OTA): ", "• Primary Engine (OTA): ", "• 主引擎 (OTA)：", "• プライマリーエンジン (OTA): ", "• प्राथमिक इंजन (OTA): ", "• Moteur Principal (OTA): ", "• المحرك الأساسي (OTA): ", "• Основной движок (OTA): ")[idxLang]
        val tApiStateLabel = arrayOf("• Estado de API Carto: ", "• Carto API Status: ", "• Carto API 状态：", "• Carto API ステータス: ", "• Carto API स्थिति: ", "• Statut API Carto: ", "• حالة Carto API: ", "• Статус Carto API: ")[idxLang]
        val tActive = arrayOf("Activa", "Active", "活跃", "アクティブ", "सक्रिय", "Active", "نشط", "Активен")[idxLang]
        val tDaysLeft = arrayOf("Quedan ~$days días de Trial", "~$days Trial days left", "剩余约 ~$days 天试用期", "トライアル残り約 ~$days 日", "~$days ट्रायल दिन बचे हैं", "Reste ~$days jours d'essai", "متبقي ~$days يوم تجريبي", "Осталось ~$days дней триала")[idxLang]
        val tSkipped = arrayOf("Omitida (Usando únicamente Motor Primario)", "Skipped (Using Primary Engine only)", "已跳过 (仅使用主引擎)", "スキップ (プライマリーエンジンのみ使用)", "छोड़ा गया (केवल प्राथमिक इंजन का उपयोग)", "Ignoré (Moteur Principal uniquement)", "تم التخطي (استخدام المحرك الأساسي فقط)", "Пропущено (Только основной движок)")[idxLang]
        val tNone = arrayOf("Sin configurar", "Not configured", "未配置", "未設定", "कॉन्फ़िगर नहीं किया गया", "Non configuré", "غير معد", "Не настроено")[idxLang]
        val tUsed = arrayOf("• Consumo mensual Carto: ", "• Monthly Carto usage: ", "• Carto 月度用量：", "• 月間Carto使用量: ", "• मासिक Carto उपयोग: ", "• Conso. Carto mensuelle: ", "• استخدام Carto الشهري: ", "• Расход Carto за месяц: ")[idxLang]
        val tTip = arrayOf(
            "💡 Si tu Trial de 14 días expiró, crea una cuenta nueva en Carto y pega aquí tu nueva llave.",
            "💡 If your 14-day Trial expired, create a new Carto account and paste your new key here.",
            "💡 如果您的 14 天试用期已过期，请在 Carto 中创建一个新账户并将新密钥粘贴到此处。",
            "💡 14日間のトライアルが終了した場合は、Cartoで新しいアカウントを作成し、新しいキーをここに貼り付けてください。",
            "💡 यदि आपका 14-दिन का ट्रायल समाप्त हो गया है, तो नया Carto खाता बनाएँ और अपनी नई कुंजी यहाँ पेस्ट करें।",
            "💡 Si votre essai de 14 jours a expiré, créez un nouveau compte Carto et collez votre nouvelle clé ici.",
            "💡 إذا انتهت الفترة التجريبية (14 يومًا)، أنشئ حساب Carto جديدًا والصق مفتاحك الجديد هنا.",
            "💡 Если 14-дневный триал истёк, создайте новый аккаунт Carto и вставьте новый ключ сюда."
        )[idxLang]
        val tBtnSync = arrayOf("CAMBIAR / SINCRONIZAR NODO PRIMARIO", "CHANGE / SYNC PRIMARY NODE", "更改/同步主节点", "プライマリーノードの変更/同期", "प्राथमिक नोड बदलें / सिंक करें", "CHANGER / SYNC NOEUD PRINCIPAL", "تغيير / مزامنة العقدة الأساسية", "ИЗМЕНИТЬ / СИНХР. ОСНОВНОЙ УЗЕЛ")[idxLang]
        val tBtnCarto = arrayOf("AÑADIR / CAMBIAR API DE CARTO", "ADD / CHANGE CARTO API", "添加/更改 CARTO API", "CARTO APIの追加/変更", "CARTO API जोड़ें / बदलें", "AJOUTER / CHANGER API CARTO", "إضافة / تغيير Carto API", "ДОБАВИТЬ / ИЗМЕНИТЬ API CARTO")[idxLang]
        val tBtnGuide = arrayOf("CÓMO SACAR O RENOVAR API (14 DÍAS)", "HOW TO GET OR RENEW API (14 DAYS)", "如何获取或更新 API（14 天）", "APIの取得・更新方法（14日間）", "API कैसे प्राप्त करें या नवीनीकृत करें", "OBTENIR OU RENOUVELER API (14J)", "كيفية الحصول على أو تجديد API", "КАК ПОЛУЧИТЬ ИЛИ ПРОДЛИТЬ API")[idxLang]
        val tBtnBack = arrayOf("VOLVER", "BACK", "返回", "戻る", "वापस", "RETOUR", "رجوع", "НАЗАД")[idxLang]

        val root = crearContenedorDialogo(ctx)

        val titulo = TextView(ctx).apply {
            text = tTitle
            setTextColor(COLOR_CYAN)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(ctx, 12))
        }

        val nodoEstado = if (hasPrimaryNode(ctx)) tNodeOk else tNodePend
        val keyActual = getApiKey(ctx)

        val estadoTexto = when {
            hasVerifiedApi(ctx) && keyActual.length > 10 ->
                "$tActive (${keyActual.take(6)}...${keyActual.takeLast(4)}) — $tDaysLeft"
            isCartoSkipped(ctx) -> tSkipped
            else -> tNone
        }

        val usados = String.format(Locale.US, "%,d", getMonthlyUsedTiles(ctx))
        val infoEstado = TextView(ctx).apply {
            text = "$tNodeLabel$nodoEstado\n" +
                    "$tApiStateLabel$estadoTexto\n" +
                    "$tUsed$usados / 5,000,000\n\n" +
                    tTip
            setTextColor(Color.WHITE)
            textSize = 13f
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, dp(ctx, 18))
        }

        val btnSincronizarNodo = crearBoton(ctx, tBtnSync, COLOR_GREEN, Color.BLACK)
        val spacer0 = crearEspacio(ctx, 10)
        val btnCambiarApi = crearBoton(ctx, tBtnCarto, COLOR_CYAN, Color.BLACK)
        val spacer1 = crearEspacio(ctx, 10)
        val btnVerGuia = crearBoton(ctx, tBtnGuide, Color.DKGRAY, COLOR_AMBER)
        val spacer2 = crearEspacio(ctx, 10)
        val btnCerrar = crearBoton(ctx, tBtnBack, Color.DKGRAY, Color.WHITE)

        root.addView(titulo)
        root.addView(infoEstado)
        root.addView(btnSincronizarNodo)
        root.addView(spacer0)
        root.addView(btnCambiarApi)
        root.addView(spacer1)
        root.addView(btnVerGuia)
        root.addView(spacer2)
        root.addView(btnCerrar)

        val dialog = AlertDialog.Builder(ctx).setView(root).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnSincronizarNodo.setOnClickListener {
            dialog.dismiss()
            mostrarDialogoSincronizarNodo(ctx)
        }

        btnCambiarApi.setOnClickListener {
            dialog.dismiss()
            mostrarDialogoIngresarApi(ctx)
        }

        btnVerGuia.setOnClickListener {
            dialog.dismiss()
            mostrarTutorialPasoAPaso(ctx)
        }

        btnCerrar.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun verificarLlaveEnServidor(apiKey: String, callback: (Boolean, String) -> Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        thread(start = true, isDaemon = true) {
            try {
                val encodedKey = Uri.encode(apiKey)
                val testUrl = URL("https://a.basemaps.cartocdn.com/dark_all/6/18/28.png?api_key=$encodedKey")
                val conn = (testUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7000
                    readTimeout = 7000
                    setRequestProperty("User-Agent", "ExosysRouter/1.0")
                    if (apiKey.startsWith("eyJ")) {
                        setRequestProperty("Authorization", "Bearer $apiKey")
                    }
                }

                val code = conn.responseCode
                val bytes = if (code == 200) conn.inputStream.use { it.readBytes() } else ByteArray(0)
                conn.disconnect()

                val isPng = bytes.size > 200 &&
                        bytes[0] == 0x89.toByte() &&
                        bytes[1] == 0x50.toByte() &&
                        bytes[2] == 0x4E.toByte() &&
                        bytes[3] == 0x47.toByte()

                mainHandler.post {
                    if (code == 200 && isPng) {
                        callback(true, "OK")
                    } else {
                        callback(false, "Carto rechazó la llave (Código $code). Revisa haberla copiado desde los 3 puntitos (⋮).")
                    }
                }
            } catch (_: Exception) {
                mainHandler.post {
                    callback(false, "Error de conexión a internet al verificar la llave.")
                }
            }
        }
    }

    private fun crearContenedorDialogo(ctx: Context): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 20), dp(ctx, 20), dp(ctx, 20))
            background = GradientDrawable().apply {
                setColor(COLOR_BG)
                setStroke(dp(ctx, 2), COLOR_CYAN)
                cornerRadius = dp(ctx, 10).toFloat()
            }
        }
    }

    private fun crearBoton(ctx: Context, texto: String, bgColor: Int, txtColor: Int): Button {
        return Button(ctx).apply {
            text = texto
            setBackgroundColor(bgColor)
            setTextColor(txtColor)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12.5f
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun crearEspacio(ctx: Context, heightDp: Int): android.view.View {
        return android.view.View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(ctx, heightDp))
        }
    }
}