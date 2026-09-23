package com.kaloutas.materialrequest

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.kaloutas.materialrequest.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
            }
            overScrollMode = View.OVER_SCROLL_NEVER
            addJavascriptInterface(
                NativeStore(getSharedPreferences("kaloutas_native", Context.MODE_PRIVATE)),
                "KaloutasNative"
            )
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, true)
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // The Apps Script form's own HTML doesn't declare a mobile
                // viewport, so useWideViewPort/loadWithOverviewMode (needed
                // for the page to lay out correctly at all) render it as a
                // wide desktop page and zoom it down to fit, squeezing
                // everything. Force a proper mobile viewport instead, and
                // hide the "This application was created by Google Apps
                // Script" bar Google injects at the top of deployed web
                // apps (it's Google's own chrome, not part of the form).
                view.evaluateJavascript(
                    """
                    (function() {
                        var m = document.querySelector('meta[name="viewport"]');
                        if (!m) {
                            m = document.createElement('meta');
                            m.name = 'viewport';
                            document.head.appendChild(m);
                        }
                        m.content = 'width=device-width, initial-scale=1.0';

                        var chromePattern = /created (this|the) app|application was created|created.*apps script|report abuse|learn more/i;

                        function hideGoogleChrome(root) {
                            var hidAny = false;

                            // Individual links (Report Abuse / Learn more) are
                            // usually small <a> tags near the top on their own;
                            // hide them directly regardless of container size.
                            var links = root.querySelectorAll('a');
                            for (var j = 0; j < links.length; j++) {
                                var link = links[j];
                                var linkRect = link.getBoundingClientRect();
                                if (linkRect.top < 250 && chromePattern.test(link.textContent)) {
                                    link.style.display = 'none';
                                    hidAny = true;
                                }
                            }

                            // Also hide the small containing block for any
                            // remaining matching text (e.g. the plain-text
                            // "created by" sentence around the links).
                            var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
                            var node;
                            while (node = walker.nextNode()) {
                                if (chromePattern.test(node.nodeValue)) {
                                    var el = node.parentElement;
                                    for (var i = 0; el && i < 6; i++) {
                                        var rect = el.getBoundingClientRect();
                                        if (rect.top < 250 && rect.height > 0 && rect.height < 200) {
                                            el.style.display = 'none';
                                            hidAny = true;
                                            break;
                                        }
                                        el = el.parentElement;
                                    }
                                }
                            }
                            return hidAny;
                        }

                        // Run immediately, then keep watching briefly in case
                        // Google injects the "report abuse" link separately
                        // and asynchronously from the "created by" banner.
                        hideGoogleChrome(document.body);
                        var observer = new MutationObserver(function() {
                            hideGoogleChrome(document.body);
                        });
                        observer.observe(document.body, { childList: true, subtree: true });
                        setTimeout(function() { observer.disconnect(); }, 5000);
                    })();
                    """.trimIndent(),
                    null
                )
                binding.loadingProgress.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }

        binding.webView.webChromeClient = android.webkit.WebChromeClient()

        // Pull-to-refresh reloads the whole page from scratch, wiping out
        // anything typed into the form and resetting to its default tab.
        // Scrolling up to reach a validation error at the top of a tab was
        // triggering this accidentally, so disable the swipe gesture; the
        // offline screen's retry button is still the way to reload.
        binding.swipeRefresh.isEnabled = false
        binding.retryButton.setOnClickListener { loadForm() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        loadForm()
    }

    private fun loadForm() {
        if (!isNetworkAvailable()) {
            binding.offlineView.visibility = View.VISIBLE
            binding.swipeRefresh.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false
            return
        }

        binding.offlineView.visibility = View.GONE
        binding.swipeRefresh.visibility = View.VISIBLE
        binding.loadingProgress.visibility = View.VISIBLE
        binding.webView.loadUrl(BuildConfig.WEB_APP_URL)
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities =
            connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // Exposed to the page as window.KaloutasNative. Keeps the verified employee email
    // and the last selected category on the device, so they survive closing the app
    // even when the WebView's own storage doesn't hold on to them.
    private class NativeStore(private val prefs: SharedPreferences) {

        @JavascriptInterface
        fun getCategory(): String = prefs.getString(KEY_CATEGORY, "").orEmpty()

        @JavascriptInterface
        fun saveCategory(value: String?) {
            if (value != null && value in VALID_CATEGORIES) {
                prefs.edit().putString(KEY_CATEGORY, value).apply()
            }
        }

        @JavascriptInterface
        fun getEmail(): String = prefs.getString(KEY_EMAIL, "").orEmpty()

        @JavascriptInterface
        fun saveEmail(value: String?) {
            if (value != null && EMAIL_PATTERN.matches(value)) {
                prefs.edit().putString(KEY_EMAIL, value).apply()
            }
        }

        private companion object {
            const val KEY_CATEGORY = "lastCategory"
            const val KEY_EMAIL = "verifiedEmail"
            val VALID_CATEGORIES = setOf("flooring", "fireproofing", "equipment", "paint")
            val EMAIL_PATTERN = Regex("^[A-Za-z0-9._%+-]+@kaloutas\\.com$", RegexOption.IGNORE_CASE)
        }
    }
}
