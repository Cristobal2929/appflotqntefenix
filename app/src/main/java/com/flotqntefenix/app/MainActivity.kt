import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var historyLayout: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var etTexto: EditText
    private lateinit var tts: TextToSpeech
    private var lastTranslation: String = ""
    private val client = OkHttpClient()
    private val handler = Handler(Looper.getMainLooper())

    private val languageNames = arrayOf(
        "Francés", "Inglés", "Alemán", "Italiano", "Portugués", "Árabe", "Español"
    )
    private val languageCodes = mapOf(
        "Francés" to "fr",
        "Inglés" to "en",
        "Alemán" to "de",
        "Italiano" to "it",
        "Portugués" to "pt",
        "Árabe" to "ar",
        "Español" to "es"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale("es")
            }
        }

        val btnActivar = findViewById<Button>(R.id.btn_activar)
        btnActivar.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                mostrarChat()
            }
        }
    }

    private fun mostrarChat() {
        if (overlayView != null) return

        val density = resources.displayMetrics.density
        val widthPx = (340 * density).toInt()
        val heightPx = (420 * density).toInt()

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            layoutParams = LinearLayout.LayoutParams(widthPx, heightPx)
            val drawable = GradientDrawable()
            drawable.cornerRadius = 16 * density
            drawable.setColor(Color.parseColor("#1E1E1E"))
            background = drawable
            setPadding(16, 16, 16, 16)
        }

        // Top bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvTitle = TextView(this).apply {
            text = getString(R.string.top_bar)
            setTextColor(Color.WHITE)
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnClose = Button(this).apply {
            text = getString(R.string.close)
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.WHITE)
            setOnClickListener {
                overlayView?.let { windowManager.removeView(it) }
                overlayView = null
            }
        }

        topBar.addView(tvTitle)
        topBar.addView(btnClose)
        rootLayout.addView(topBar)

        // Spinners
        val spinnerSource = Spinner(this)
        val spinnerDest = Spinner(this)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languageNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        spinnerSource.adapter = adapter
        spinnerDest.adapter = adapter

        fun styleSpinner(spinner: Spinner) {
            spinner.setBackgroundColor(Color.parseColor("#2D2D2D"))
            spinner.setPadding(8, 8, 8, 8)
        }

        styleSpinner(spinnerSource)
        styleSpinner(spinnerDest)

        // Set default destination to Español
        val defaultDestIndex = languageNames.indexOf("Español")
        if (defaultDestIndex >= 0) spinnerDest.setSelection(defaultDestIndex)

        rootLayout.addView(spinnerSource)
        rootLayout.addView(spinnerDest)

        // History ScrollView
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        historyLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        scrollView.addView(historyLayout)
        rootLayout.addView(scrollView)

        // EditText
        etTexto = EditText(this).apply {
            setBackgroundColor(Color.parseColor("#2D2D2D"))
            setTextColor(Color.WHITE)
            hint = getString(R.string.hint)
            setHintTextColor(Color.LTGRAY)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        rootLayout.addView(etTexto)

        // Buttons row
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val btnTranslate = Button(this).apply {
            text = getString(R.string.translate)
            setBackgroundColor(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnSpeak = Button(this).apply {
            text = getString(R.string.speak)
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnPaste = Button(this).apply {
            text = getString(R.string.paste)
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        buttonRow.addView(btnTranslate)
        buttonRow.addView(btnSpeak)
        buttonRow.addView(btnPaste)
        rootLayout.addView(buttonRow)

        // Add overlay to window
        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            // TYPE_APPLICATION_OVERLAY is available from API 26 and is the recommended type.
            // It works on newer devices and also on older ones when the app's minSdk is >= 26.
            // For devices with API < 26 you could fallback to TYPE_SYSTEM_ALERT, but that
            // constant was removed in API 34, causing compilation errors. Using only
            // TYPE_APPLICATION_OVERLAY avoids the compile‑time issue.
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER

        overlayView = rootLayout
        windowManager.addView(rootLayout, params)

        // Listeners
        btnTranslate.setOnClickListener {
            val originalText = etTexto.text.toString()
            if (originalText.isBlank()) return@setOnClickListener

            val srcLang = languageCodes[spinnerSource.selectedItem.toString()] ?: "en"
            val destLang = languageCodes[spinnerDest.selectedItem.toString()] ?: "es"

            CoroutineScope(Dispatchers.IO).launch {
                val url = "https://api.mymemory.translated.net/get?q=${URLEncoder.encode(originalText, "UTF-8")}&langpair=$srcLang|$destLang"
                val request = Request.Builder().url(url).build()
                try {
                    val response = client.newCall(request).execute()
                    val body = response.body?.string()
                    val json = JSONObject(body)
                    val translated = json.getJSONObject("responseData").getString("translatedText")
                    withContext(Dispatchers.Main) {
                        addBubble(originalText, Color.parseColor("#808080"))
                        addBubble(translated, Color.parseColor("#2196F3"))
                        lastTranslation = translated
                        etTexto.text.clear()
                        handler.postDelayed({
                            scrollView.fullScroll(View.FOCUS_DOWN)
                        }, 100)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        btnSpeak.setOnClickListener {
            if (lastTranslation.isNotBlank()) {
                tts.speak(lastTranslation, TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }

        btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val pasteData = clip.getItemAt(0).coerceToText(this).toString()
                etTexto.setText(pasteData)
            }
        }

        spinnerDest.setOnItemSelectedListener(object :
            android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) {
                val lang = languageCodes[languageNames[position]] ?: "es"
                tts.language = Locale(lang)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        })
    }

    private fun addBubble(text: String, bgColor: Int) {
        val bubble = TextView(this).apply {
            this.text = text
            setBackgroundColor(bgColor)
            setTextColor(Color.WHITE)
            setPadding(12, 8, 12, 8)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 8, 0, 8)
            layoutParams = params
        }
        historyLayout.addView(bubble)
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
        overlayView?.let { windowManager.removeView(it) }
    }
}