package net.candiago.bluegleam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Insets
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.net.URLDecoder
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("bluegleam", MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()

    private var webView: WebView? = null
    private var printing: WebView? = null // the page being printed (see Bridge.print)
    private var root: View? = null
    private var topBar: View? = null
    private var bottomBar: View? = null
    private var scope: Uri? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private var topColor = 0
    private var bottomColor = 0

    private var backCallback: Any? = null // OnBackInvokedCallback on API 33+
    private var backRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        topColor = getColor(R.color.bluegleam)
        bottomColor = topColor

        // Draw behind the system bars (mandatory from Android 15, opt-in before):
        // the bars are coloured by views we place under them.
        window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false

        if (prefs.getString(PushWorker.KEY_TOKEN, null) != null) PushWorker.schedule(this) // after an update or a reboot too
        val start = prefs.getString(KEY_START, null)
        val savedScope = prefs.getString(KEY_SCOPE, null)
        if (start == null || savedScope == null) {
            showSetup()
        } else {
            showWeb(start, Uri.parse(savedScope), savedInstanceState)
        }
    }

    // ---------------------------------------------------------------- first launch

    private fun showSetup() {
        setContentView(R.layout.activity_setup)
        val setupRoot = findViewById<View>(R.id.setup_root)
        onInsets(setupRoot) { v, bars -> v.setPadding(bars.left, bars.top, bars.right, bars.bottom) }
        setBarColors(topColor, topColor)

        val input = findViewById<EditText>(R.id.url_input)
        val message = findViewById<TextView>(R.id.setup_message)
        val button = findViewById<Button>(R.id.setup_continue)

        fun showError(text: String) {
            message.text = text
            message.setTextColor(ERROR_TEXT)
            message.setTypeface(message.typeface, android.graphics.Typeface.BOLD)
        }

        fun submit() {
            val raw = input.text.toString().trim()
            if (raw.isEmpty()) return showError(getString(R.string.error_empty))
            val address = normalize(raw) ?: return showError(getString(R.string.error_invalid))

            input.isEnabled = false
            button.isEnabled = false
            button.setText(R.string.setup_checking)
            val userAgent = userAgent(WebSettingsDefaults.userAgent(this))
            executor.execute {
                val result = InstanceProbe.probe(address, userAgent)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    input.isEnabled = true
                    button.isEnabled = true
                    button.setText(R.string.setup_continue)
                    when (result) {
                        is InstanceProbe.Result.Ok -> {
                            prefs.edit()
                                .putString(KEY_START, result.start)
                                .putString(KEY_SCOPE, result.scope)
                                .apply()
                            window.insetsController?.hide(WindowInsets.Type.ime())
                            showWeb(result.start, Uri.parse(result.scope), null)
                        }
                        is InstanceProbe.Result.HttpError -> showError(getString(R.string.error_http, result.code))
                        InstanceProbe.Result.Unreachable -> showError(getString(R.string.error_unreachable))
                    }
                }
            }
        }

        button.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                submit(); true
            } else false
        }
        input.requestFocus()
        input.post { window.insetsController?.show(WindowInsets.Type.ime()) }
    }

    private fun normalize(raw: String): String? {
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        val uri = Uri.parse(withScheme)
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank() || raw.contains(' ')) return null
        return uri.buildUpon().scheme(scheme).build().toString()
    }

    // ---------------------------------------------------------------- web app

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWeb(start: String, scope: Uri, state: Bundle?) {
        this.scope = scope
        val top = View(this)
        val bottom = View(this)
        val web = WebView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
            addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
        }
        webView = web
        root = layout
        topBar = top
        bottomBar = bottom
        setContentView(layout)
        setBarColors(topColor, bottomColor)

        onInsets(layout) { v, bars ->
            v.setPadding(bars.left, 0, bars.right, 0)
            top.layoutParams.height = bars.top
            bottom.layoutParams.height = bars.bottom
            top.requestLayout()
            bottom.requestLayout()
        }

        // debug builds only: the page inside can be inspected and tested (chrome://inspect, chromedriver)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
        // passkeys (two-step sign-in): the page may use WebAuthn, answered by Android's Credential Manager for this
        // app. The site must list the app in /.well-known/assetlinks.json (ANDROID_APP_PACKAGE / ANDROID_APP_SHA256).
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_AUTHENTICATION)) {
            androidx.webkit.WebSettingsCompat.setWebAuthenticationSupport(web.settings, androidx.webkit.WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP)
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(false) // target=_blank / window.open go through shouldOverrideUrlLoading
            allowFileAccess = false
            userAgentString = userAgent(userAgentString)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)

        web.addJavascriptInterface(Bridge(), "BluegleamAndroid")
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            download(url, userAgent, contentDisposition, mimeType)
        }

        // Keep the brand colour on screen until the first page paints.
        web.visibility = View.INVISIBLE
        if (state == null || web.restoreState(state) == null) web.loadUrl(start)
    }

    private fun inScope(uri: Uri): Boolean {
        val s = scope ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        if (!uri.host.equals(s.host, ignoreCase = true) || port(uri) != port(s)) return false
        val prefix = s.path ?: "/"
        val path = uri.path ?: "/"
        return path.startsWith(prefix) || "$path/" == prefix
    }

    private fun port(u: Uri) = when {
        u.port != -1 -> u.port
        u.scheme.equals("http", ignoreCase = true) -> 80
        else -> 443
    }

    private fun openExternally(uri: Uri) {
        try {
            val intent = if (uri.scheme == "intent") {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            }
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun download(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") return
        try {
            val name = fileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(uri)
                .setMimeType(mimeType)
                .addRequestHeader("User-Agent", userAgent)
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            getSystemService(DownloadManager::class.java).enqueue(request)
            Toast.makeText(this, getString(R.string.download_started, name), Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            openExternally(uri)
        }
    }

    /**
     * The name a download is saved with. Bluegleam sends it twice (RFC 6266): filename*=UTF-8''… with the real name,
     * accents and all, and an ASCII filename="…". URLUtil.guessFileName reads neither form reliably and falls back to
     * the last piece of the address plus ".bin" ("download.bin"), so the header is read here first.
     */
    private fun fileName(url: String, disposition: String?, mimeType: String?): String {
        val header = disposition.orEmpty()
        val extended = Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(header)?.let {
            runCatching { URLDecoder.decode(it.groupValues[2].trim().replace("+", "%2B"), it.groupValues[1].ifBlank { "UTF-8" }) }.getOrNull()
        }
        val plain = Regex("""filename\s*=\s*(?:"((?:\\.|[^"\\])*)"|([^;\s]+))""", RegexOption.IGNORE_CASE).find(header)?.let {
            it.groupValues[1].ifEmpty { it.groupValues[2] }.replace(Regex("""\\(.)"""), "$1")
        }
        val name = (extended ?: plain)?.replace(Regex("""[/\\:*?"<>|\u0000-\u001f]"""), "_")?.trim()
        return if (name.isNullOrEmpty()) URLUtil.guessFileName(url, disposition, mimeType) else name.take(200)
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (inScope(uri)) return false
            // An https instance whose server sends us to plain http on the same host: stay on https.
            val secure = uri.buildUpon().scheme("https").encodedAuthority(uri.host).build()
            if (uri.scheme == "http" && scope?.scheme == "https" && (uri.port == -1 || uri.port == 80) && inScope(secure)) {
                view.loadUrl(secure.toString())
                return true
            }
            val scheme = uri.scheme?.lowercase()
            // Embedded frames (maps, videos…) may navigate freely; only whole pages leave the app.
            if (!request.isForMainFrame && (scheme == "http" || scheme == "https")) return false
            openExternally(uri)
            return true
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            view.visibility = View.VISIBLE
            view.evaluateJavascript(BAR_SCRIPT, null)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            view.visibility = View.VISIBLE
            view.evaluateJavascript(BAR_SCRIPT, null)
            CookieManager.getInstance().flush()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            updateBack()
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            val intent = params.createIntent()
            if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            return try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_FILES)
                true
            } catch (_: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }
    }

    @Deprecated("Platform Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_FILES) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val callback = fileCallback ?: return
        fileCallback = null
        val clip = data?.clipData
        val uris = when {
            resultCode != RESULT_OK -> null
            clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { clip.getItemAt(it).uri }
            else -> WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        }
        callback.onReceiveValue(uris)
    }

    // ---------------------------------------------------------------- bar colours

    /** Receives the page's top-bar colour (theme-color / root background) and body background. */
    private inner class Bridge {
        /** Notifications on the phone: does this phone already have its token for /api/push? */
        @JavascriptInterface
        fun pushReady(): Boolean = prefs.getString(PushWorker.KEY_TOKEN, null) != null

        /** How the phone is called in Settings › Devices. */
        @JavascriptInterface
        fun deviceName(): String = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL).distinct().joinToString(" ")

        /** The token the signed-in page made for this phone: kept, the permission asked, the checks started. */
        @JavascriptInterface
        fun pushToken(token: String?) {
            if (token.isNullOrBlank() || !token.startsWith("gleampush_")) return
            prefs.edit().putString(PushWorker.KEY_TOKEN, token).apply()
            runOnUiThread {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
                }
                PushWorker.schedule(this@MainActivity)
            }
        }

        /**
         * Print (Editor, Sheets, Boards…): the page sends the document as a whole HTML page; it is laid out in a web
         * view of its own and handed to Android's print service, which also offers «Save as PDF».
         */
        @JavascriptInterface
        fun print(html: String?, title: String?) {
            if (html.isNullOrBlank()) return
            val name = (title ?: "Bluegleam").ifBlank { "Bluegleam" }.take(80)
            runOnUiThread {
                val view = WebView(this@MainActivity)
                printing = view // kept until printed: a web view nobody holds can go before it is done
                view.settings.javaScriptEnabled = false
                view.webViewClient = object : WebViewClient() {
                    private var sent = false
                    override fun onPageFinished(page: WebView, url: String?) {
                        if (sent) return
                        sent = true
                        val manager = getSystemService(Context.PRINT_SERVICE) as PrintManager
                        manager.print(name, page.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
                    }
                }
                view.loadDataWithBaseURL(webView?.url, html, "text/html", "utf-8", null)
            }
        }

        @JavascriptInterface
        fun colors(top: String?, bottom: String?) {
            val t = parseCssColor(top)
            val b = parseCssColor(bottom)
            runOnUiThread { setBarColors(t ?: topColor, b ?: bottomColor) }
        }
    }

    private fun setBarColors(top: Int, bottom: Int) {
        topColor = top
        bottomColor = bottom
        root?.setBackgroundColor(top)
        topBar?.setBackgroundColor(top)
        bottomBar?.setBackgroundColor(bottom)
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        var appearance = 0
        if (Color.luminance(top) > 0.5f) appearance = appearance or WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        if (Color.luminance(bottom) > 0.5f) appearance = appearance or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(appearance, mask)
    }

    private fun parseCssColor(value: String?): Int? {
        val v = value?.trim()?.lowercase().orEmpty()
        if (v.isEmpty() || v == "transparent") return null
        RGB.matchEntire(v)?.let { m ->
            val (r, g, b, a) = m.destructured
            val alpha = when {
                a.isEmpty() -> 1f
                a.endsWith("%") -> a.dropLast(1).toFloatOrNull()?.div(100f) ?: 1f
                else -> a.toFloatOrNull() ?: 1f
            }
            if (alpha < 0.5f) return null
            fun c(s: String) = (s.toFloatOrNull() ?: 0f).roundToInt().coerceIn(0, 255)
            return Color.rgb(c(r), c(g), c(b))
        }
        val hex = when {
            Regex("#[0-9a-f]{3}").matches(v) -> "#" + v.drop(1).map { "$it$it" }.joinToString("")
            Regex("#[0-9a-f]{8}").matches(v) -> "#" + v.substring(7, 9) + v.substring(1, 7) // CSS RRGGBBAA → AARRGGBB
            else -> v
        }
        return try {
            Color.parseColor(hex)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    // ---------------------------------------------------------------- back, lifecycle

    private fun updateBack() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val wanted = webView?.canGoBack() == true
        if (wanted == backRegistered) return
        val callback = (backCallback as? OnBackInvokedCallback)
            ?: OnBackInvokedCallback { webView?.goBack() }.also { backCallback = it }
        if (wanted) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        } else {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        }
        backRegistered = wanted
    }

    @Deprecated("Used only below Android 13")
    override fun onBackPressed() {
        val web = webView
        if (web != null && web.canGoBack()) {
            web.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        PushWorker.visible = true // the page shows its own notifications meanwhile
        webView?.onResume()
    }

    override fun onPause() {
        PushWorker.visible = false
        webView?.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- helpers

    private fun onInsets(view: View, apply: (View, Insets) -> Unit) {
        view.setOnApplyWindowInsetsListener { v, insets ->
            val types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            apply(v, insets.getInsets(types))
            WindowInsets.CONSUMED
        }
        view.requestApplyInsets()
    }

    private fun userAgent(base: String): String {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            null
        } ?: "1"
        return "$base BluegleamAndroid/$version"
    }

    private object WebSettingsDefaults {
        fun userAgent(activity: Activity): String = android.webkit.WebSettings.getDefaultUserAgent(activity)
    }

    companion object {
        private const val KEY_START = "start_url"
        private const val KEY_SCOPE = "scope_url"
        private const val REQUEST_FILES = 1001
        private const val REQUEST_NOTIFICATIONS = 1002
        private const val ERROR_TEXT = 0xFFFFE1E1.toInt()

        private val RGB = Regex("""rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)(?:\s*[,/]\s*([\d.]+%?))?\s*\)""")

        /**
         * Reports the colours for the phone's status and navigation bars, and keeps reporting when they
         * change: on Bluegleam its theme-color and bluegleam-nav-color (the colours of its own bars, which
         * follow the theme), elsewhere the root and body backgrounds.
         */
        private val BAR_SCRIPT = """
            (function () {
              if (window.__bluegleamBars) { window.__bluegleamBars(); return; }
              var last = '', queued = false, bodySeen = false, ctx = null;
              // Any CSS colour (color-mix(), color(srgb …), oklch() …) → "rgb(r, g, b)", or '' if transparent.
              function rgb(c) {
                if (!c) return '';
                try {
                  if (!ctx) { var cv = document.createElement('canvas'); cv.width = cv.height = 1; ctx = cv.getContext('2d', { willReadFrequently: true }); }
                  ctx.clearRect(0, 0, 1, 1);
                  ctx.fillStyle = '#000'; ctx.fillStyle = c;
                  ctx.fillRect(0, 0, 1, 1);
                  var d = ctx.getImageData(0, 0, 1, 1).data;
                  return d[3] < 128 ? '' : 'rgb(' + d[0] + ', ' + d[1] + ', ' + d[2] + ')';
                } catch (e) { return ''; }
              }
              function bg(el) { return el ? rgb(getComputedStyle(el).backgroundColor) : ''; }
              function send() {
                // Bluegleam says the colours of its own bars (theme-color for the top, bluegleam-nav-color for the
                // bottom); other pages: the root background, then the body's
                var meta = document.querySelector('meta[name="theme-color"]');
                var nav = document.querySelector('meta[name="bluegleam-nav-color"]');
                var top = (nav && rgb(meta && meta.content)) || bg(document.documentElement) || rgb(meta && meta.content);
                var bottom = rgb(nav && nav.content) || bg(document.body) || top;
                var key = top + '|' + bottom;
                if (key === last) return;
                last = key;
                BluegleamAndroid.colors(top, bottom);
              }
              var observer = new MutationObserver(schedule);
              function attach() {
                if (document.body && !bodySeen) { bodySeen = true; observer.observe(document.body, { attributes: true }); }
              }
              function schedule() {
                attach();
                if (queued) return;
                queued = true;
                requestAnimationFrame(function () { queued = false; send(); });
                setTimeout(send, 400); // after colour transitions
              }
              observer.observe(document.documentElement, { attributes: true });
              if (document.head) observer.observe(document.head, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });
              window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', schedule);
              window.addEventListener('pageshow', function () { last = ''; schedule(); });
              window.__bluegleamBars = function () { last = ''; schedule(); };
              schedule();
            })();
        """.trimIndent()
    }
}
