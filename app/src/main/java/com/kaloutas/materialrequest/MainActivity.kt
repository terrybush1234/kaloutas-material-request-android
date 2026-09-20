package com.kaloutas.materialrequest

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
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

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
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

                        function hideGoogleChrome(root) {
                            var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
                            var node;
                            var hidAny = false;
                            while (node = walker.nextNode()) {
                                if (/created (this|the) app|application was created|created.*apps script|report abuse/i.test(node.nodeValue)) {
                                    var el = node.parentElement;
                                    for (var i = 0; el && i < 5; i++) {
                                        var rect = el.getBoundingClientRect();
                                        if (rect.top < 200 && rect.height > 0 && rect.height < 150) {
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

        binding.swipeRefresh.setOnRefreshListener { loadForm() }
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
}
