package com.fullscreen.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.ByteArrayInputStream

class MainActivity : Activity() {

    companion object {
        private const val START_PAGE = "file:///android_asset/start.html"

        // How long the address bar stays visible when a page starts loading.
        private const val LOAD_SHOW_MS = 2500L
        // How long it stays after a firm swipe (and after you stop touching it).
        private const val SWIPE_SHOW_MS = 3500L
        // How "firm" a swipe must be to reveal the bar (pixels per second).
        // Lower number = reveals more easily. Higher = needs a harder flick.
        private const val REVEAL_VELOCITY = 3500f

        // Used only if blocklist.txt is missing from the app.
        private val FALLBACK_BLOCKLIST = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "google-analytics.com", "adservice.google.com", "adnxs.com", "taboola.com",
            "outbrain.com", "criteo.com", "criteo.net", "rubiconproject.com",
            "pubmatic.com", "openx.net", "adsrvr.org", "scorecardresearch.com",
            "quantserve.com", "moatads.com", "amazon-adsystem.com", "popads.net",
            "propellerads.com", "mgid.com", "exoclick.com", "adsterra.com"
        )

        private val ALLOWED_SCHEMES = setOf("http", "https", "file", "about", "data", "blob", "javascript")

        // Hides leftover empty ad boxes after the requests were blocked.
        private val HIDE_ADS_JS = """
            (function(){
              if (document.getElementById('__fsb_adhide')) return;
              var s = document.createElement('style');
              s.id = '__fsb_adhide';
              s.textContent = 'ins.adsbygoogle,.adsbygoogle,iframe[id^="google_ads_iframe"],div[id^="google_ads_"],div[id*="div-gpt-ad"],[data-google-query-id],[data-ad-slot],iframe[src*="doubleclick.net"],iframe[src*="googlesyndication.com"],iframe[src*="adservice."],#taboola-below-article,[id^="taboola-"],.OUTBRAIN,[data-widget-id*="outbrain"]{display:none!important;height:0!important;min-height:0!important;}';
              (document.head || document.documentElement).appendChild(s);
            })();
        """.trimIndent()
    }

    private lateinit var web: WebView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var root: FrameLayout
    private lateinit var bar: LinearLayout
    private lateinit var urlBox: EditText
    private lateinit var barFlingDetector: GestureDetector
    private lateinit var webFlingDetector: GestureDetector

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideBar() }
    private var barVisible = false

    @Volatile
    private var blocked: Set<String> = emptySet()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw behind the status bar / notch
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        loadBlocklist()

        root = FrameLayout(this)
        root.isFocusableInTouchMode = true
        web = WebView(this)
        refresh = SwipeRefreshLayout(this)
        refresh.addView(
            web,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            refresh,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        bar = buildAddressBar()
        root.addView(bar)
        setContentView(root)

        // Keep the bar below a camera notch, if the phone has one.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val cutoutTop = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
            (bar.layoutParams as FrameLayout.LayoutParams).topMargin = dp(8) + cutoutTop
            bar.requestLayout()
            insets
        }

        // Swipe DOWN from the top of a page = refresh.
        refresh.setOnRefreshListener { web.reload() }
        refresh.setOnChildScrollUpCallback { _, _ -> web.scrollY > 0 }

        // A firm swipe (up or down) on the page brings the address bar back.
        webFlingDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                if (Math.abs(velocityY) > REVEAL_VELOCITY && Math.abs(velocityY) > Math.abs(velocityX)) {
                    showBar(SWIPE_SHOW_MS)
                }
                return false
            }
        })
        web.setOnTouchListener { _, ev ->
            webFlingDetector.onTouchEvent(ev)
            false
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        web.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                updateUrlBox(url)
                // Show the bar briefly while a real website loads (not on the start page).
                if (url != null && !url.startsWith("file:")) showBar(LOAD_SHOW_MS)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                view?.evaluateJavascript(HIDE_ADS_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                refresh.isRefreshing = false
                updateUrlBox(url)
                view?.evaluateJavascript(HIDE_ADS_JS, null)
            }

            // Ad blocking: ad / tracker requests get an empty answer.
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request != null && !request.isForMainFrame && isBlocked(request.url.host)) {
                    return WebResourceResponse(
                        "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            // Pop-up style redirects to ad sites (no tap from you) are stopped.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request == null) return false
                val u = request.url
                val scheme = u.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") {
                    return !request.hasGesture() && isBlocked(u.host)
                }
                if (scheme !in ALLOWED_SCHEMES) {
                    // tel:, mailto:, upi:, intent: ... open the matching app, but only after a tap
                    if (request.hasGesture()) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, u))
                        } catch (_: Exception) {
                        }
                    }
                    return true
                }
                return false
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            // Lets videos (e.g. YouTube) go full screen
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                refresh.visibility = View.GONE
                root.addView(view)
            }

            override fun onHideCustomView() {
                customView?.let { root.removeView(it) }
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                refresh.visibility = View.VISIBLE
            }
        }

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            web.loadUrl(START_PAGE)
        }

        hideSystemBars()
    }

    // ---------------------------------------------------------------- address bar

    @SuppressLint("ClickableViewAccessibility")
    private fun buildAddressBar(): LinearLayout {
        barFlingDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                // Swipe UP on the bar hides it.
                if (velocityY < -800f && Math.abs(velocityY) > Math.abs(velocityX)) {
                    hideBar(force = true)
                    return true
                }
                return false
            }
        })

        val row = object : LinearLayout(this@MainActivity) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                barFlingDetector.onTouchEvent(ev)
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> handler.removeCallbacks(hideRunnable)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> scheduleHide(SWIPE_SHOW_MS)
                }
                return super.dispatchTouchEvent(ev)
            }
        }
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(6), dp(5), dp(6), dp(5))
        row.elevation = dp(8).toFloat()
        row.background = GradientDrawable().apply {
            setColor(Color.parseColor("#F2151B23"))
            cornerRadius = dp(18).toFloat()
        }

        fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        }

        urlBox = EditText(this).apply {
            setSingleLine(true)
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8B96A3"))
            hint = "Search or type a website"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A333F"))
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    navigate(text.toString())
                    true
                } else false
            }
            onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) handler.removeCallbacks(hideRunnable) else scheduleHide(2000L)
            }
        }

        row.addView(button("←") { if (web.canGoBack()) web.goBack() })
        row.addView(button("🏠") { web.loadUrl(START_PAGE) })
        row.addView(urlBox)
        row.addView(button("↻") { web.reload() })

        row.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ).apply {
            topMargin = dp(8)
            marginStart = dp(8)
            marginEnd = dp(8)
        }
        row.translationY = -dp(150).toFloat()
        row.visibility = View.GONE
        return row
    }

    private fun showBar(autoHideMs: Long) {
        handler.removeCallbacks(hideRunnable)
        barVisible = true
        bar.visibility = View.VISIBLE
        bar.animate().cancel()
        bar.animate().translationY(0f).setDuration(220).start()
        if (!urlBox.hasFocus()) scheduleHide(autoHideMs)
    }

    private fun scheduleHide(ms: Long) {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, ms)
    }

    private fun hideBar(force: Boolean = false) {
        if (!barVisible) return
        if (urlBox.hasFocus()) {
            if (!force) return
            urlBox.clearFocus()
            hideKeyboard()
        }
        handler.removeCallbacks(hideRunnable)
        barVisible = false
        bar.animate().cancel()
        bar.animate()
            .translationY(-dp(150).toFloat())
            .setDuration(250)
            .withEndAction { if (!barVisible) bar.visibility = View.GONE }
            .start()
    }

    private fun updateUrlBox(url: String?) {
        if (urlBox.hasFocus()) return
        urlBox.setText(if (url == null || url.startsWith("file:")) "" else url)
    }

    private fun navigate(input: String) {
        val q = input.trim()
        if (q.isEmpty()) return
        val target = when {
            Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(q) -> q
            Regex("^[^\\s]+\\.[^\\s]{2,}$").matches(q) -> "https://$q"
            else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")
        }
        urlBox.clearFocus()
        root.requestFocus()
        hideKeyboard()
        web.loadUrl(target)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBox.windowToken, 0)
    }

    // ---------------------------------------------------------------- ad blocking

    private fun loadBlocklist() {
        Thread {
            val set = HashSet<String>(100_000)
            try {
                assets.open("blocklist.txt").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val d = line.trim()
                        if (d.isNotEmpty() && !d.startsWith("#")) set.add(d)
                    }
                }
            } catch (_: Exception) {
            }
            FALLBACK_BLOCKLIST.forEach { set.add(it) }
            blocked = set
        }.start()
    }

    // Checks the host and its parent domains: ads.example.com -> example.com
    private fun isBlocked(host: String?): Boolean {
        if (host == null) return false
        val set = blocked
        if (set.isEmpty()) return false
        var h = host.lowercase()
        while (true) {
            if (set.contains(h)) return true
            val first = h.indexOf('.')
            if (first < 0 || h.indexOf('.', first + 1) < 0) return false
            h = h.substring(first + 1)
        }
    }

    // ---------------------------------------------------------------- screen / lifecycle

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> web.webChromeClient?.onHideCustomView()
            urlBox.hasFocus() -> {
                urlBox.clearFocus()
                root.requestFocus()
                hideKeyboard()
            }
            web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }
}
