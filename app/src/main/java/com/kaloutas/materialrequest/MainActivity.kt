package com.kaloutas.materialrequest

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.kaloutas.materialrequest.databinding.ActivityMainBinding
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // The page's file box (<input type="file">): the callback to answer, and the photo file the camera
    // writes to when the camera is picked instead of the gallery / files.
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraFile: File? = null
    private var cameraUri: Uri? = null

    // Kept while Android lays out a page for printing (the print job reads it after onPageFinished).
    private var printWebView: WebView? = null

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileCallback
        fileCallback = null
        var uris: Array<Uri>? = null
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val clip = data?.clipData
            uris = when {
                clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                data?.data != null -> arrayOf(data.data!!)
                cameraUri != null && (cameraFile?.length() ?: 0L) > 0L -> arrayOf(cameraUri!!)
                else -> null
            }
        }
        if (callback == null) {
            // The phone closed JobLink while the camera or gallery was open (to free memory), so the page that
            // asked is gone. Keep the pictures; the page takes them when it opens again (takePendingPhotos).
            uris?.forEach { keepPending(it) }
            cameraFile?.delete()
        }
        cameraFile = null
        cameraUri = null
        // Always answered (null when cancelled), or the page's next tap would do nothing.
        callback?.onReceiveValue(uris)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Started again after the phone closed it while the camera was open: where the camera is writing the photo.
        savedInstanceState?.getString(STATE_CAMERA_FILE)?.let { path ->
            val f = File(path)
            cameraFile = f
            cameraUri = try { FileProvider.getUriForFile(this, "$packageName.fileprovider", f) } catch (e: Exception) { null }
        }
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
            // Print and Download PDF from the page (a WebView can't print an iframe or save a blob: link itself).
            addJavascriptInterface(NativeDocs(), "KaloutasDocs")
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

                        // Google's bar is the #warning row above the form's iframe; hide it outright.
                        var hideBar = document.createElement('style');
                        hideBar.textContent = '#warning, .warning-bar { display: none !important; }';
                        document.head.appendChild(hideBar);

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

        // The page's photo / PDF buttons: open the gallery or files (what the page accepts), with the camera
        // offered too when it asks for pictures.
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val accept = params.acceptTypes.filter { it.isNotBlank() }
                val wantsImages = accept.isEmpty() || accept.any { it.startsWith("image") }
                val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (accept.size == 1) accept[0] else "*/*"
                    if (accept.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, accept.toTypedArray())
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                }
                val extras = mutableListOf<Intent>()
                if (wantsImages) cameraIntent()?.let { extras.add(it) }
                val chooser = Intent.createChooser(pick, null).apply {
                    if (extras.isNotEmpty()) putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
                }
                return try {
                    pickFiles.launch(chooser)
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    callback.onReceiveValue(null)
                    false
                }
            }
        }

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        cameraFile?.let { outState.putString(STATE_CAMERA_FILE, it.absolutePath) }
    }

    // A picture kept for the page after a restart: made smaller (longest side 1600 px, JPEG, turned upright as the
    // camera recorded it) and saved in the app's cache until the page takes it.
    private fun keepPending(uri: Uri) {
        try {
            val open = { contentResolver.openInputStream(uri) ?: throw IllegalStateException("cannot read") }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open().use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PENDING_MAX_SIDE) sample *= 2
            var bmp = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return
            val turn = open().use { exifDegrees(it) }
            val k = minOf(1f, PENDING_MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height))
            if (k < 1f || turn != 0) {
                val m = Matrix().apply { if (k < 1f) postScale(k, k); if (turn != 0) postRotate(turn.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val dir = File(cacheDir, "pending").apply { mkdirs() }
            File(dir, "p_" + System.currentTimeMillis() + "_" + (dir.list()?.size ?: 0) + ".jpg").outputStream().use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 75, it)
            }
        } catch (e: Exception) {
            // Not a picture or not readable: nothing to keep.
        }
    }

    private fun exifDegrees(stream: InputStream): Int = try {
        when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) { 0 }

    // The kept pictures (a day old at most) as data: URLs, removed once handed over.
    private fun takePending(): String {
        val out = JSONArray()
        val files = File(cacheDir, "pending").listFiles()?.sortedBy { it.name } ?: return out.toString()
        val now = System.currentTimeMillis()
        files.forEach { f ->
            if (now - f.lastModified() < 24L * 3600 * 1000) {
                val b = ByteArrayOutputStream()
                f.inputStream().use { it.copyTo(b) }
                out.put("data:image/jpeg;base64," + Base64.encodeToString(b.toByteArray(), Base64.NO_WRAP))
            }
            f.delete()
        }
        return out.toString()
    }

    // The app may already be running when a review link is tapped, in which case
    // Android delivers it here instead of a fresh onCreate — reload with the new reqId.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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
        val reqId = extractReqId(intent)
        val targetUrl = if (reqId != null) "${BuildConfig.WEB_APP_URL}?reqId=$reqId" else BuildConfig.WEB_APP_URL
        binding.webView.loadUrl(targetUrl)
    }

    // Pulls reqId out of a kaloutasapp://review?reqId=... deep link, if that's what launched us.
    private fun extractReqId(intent: Intent?): String? {
        val uri = intent?.data ?: return null
        return if (uri.scheme == "kaloutasapp") uri.getQueryParameter("reqId") else null
    }

    // A camera intent that writes the photo to a temporary file (shared through the FileProvider), or null
    // when the phone has no camera app. No CAMERA permission is needed: the camera app takes the picture.
    private fun cameraIntent(): Intent? {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) == null) return null
        return try {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            val file = File.createTempFile("survey_", ".jpg", dir)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            cameraFile = file
            cameraUri = uri
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent
        } catch (e: Exception) {
            null
        }
    }

    // Lays the page's printable HTML out in a hidden WebView and hands it to Android's print dialog.
    private fun printHtml(html: String, title: String) {
        val view = WebView(this)
        printWebView = view
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) {
                val manager = getSystemService(Context.PRINT_SERVICE) as PrintManager
                val name = title.ifBlank { "JobLink" }
                manager.print(name, v.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
            }
        }
        view.settings.javaScriptEnabled = false
        view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    // Saves a PDF the page made into the phone's Downloads folder, then offers to open it.
    private fun savePdf(base64: String, name: String) {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|]+"), " ").trim().ifBlank { "JobLink" }
        val fileName = if (clean.endsWith(".pdf", ignoreCase = true)) clean else "$clean.pdf"
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val uri: Uri
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Downloads is not available")
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                val file = File(dir, fileName)
                file.writeBytes(bytes)
                uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            }
            Toast.makeText(this, "Saved $fileName to Downloads", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: ActivityNotFoundException) {
                // No PDF viewer: the file is still in Downloads.
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not save the PDF: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Exposed to the page as window.KaloutasDocs. Calls arrive on a background thread, so they move to the
    // main thread; sizes are capped so a page can't hand over anything unreasonable.
    private inner class NativeDocs {
        @JavascriptInterface
        fun print(html: String?, title: String?) {
            if (html == null || html.length > MAX_DOC_CHARS) return
            runOnUiThread { printHtml(html, (title ?: "").take(120)) }
        }

        @JavascriptInterface
        fun savePdf(base64: String?, name: String?) {
            if (base64 == null || base64.length > MAX_DOC_CHARS) return
            runOnUiThread { savePdf(base64, (name ?: "").take(120)) }
        }

        // Pictures taken while the phone had closed the app (see pickFiles): how many wait, and handing them over.
        @JavascriptInterface
        fun pendingPhotoCount(): Int = File(cacheDir, "pending").list()?.size ?: 0

        @JavascriptInterface
        fun takePendingPhotos(): String = takePending()
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

        // Requester name + phone as a small JSON string, e.g. {"name":"...","phone":"..."}
        @JavascriptInterface
        fun getProfile(): String = prefs.getString(KEY_PROFILE, "").orEmpty()

        @JavascriptInterface
        fun saveProfile(value: String?) {
            if (value != null && value.length <= MAX_PROFILE_LENGTH && value.startsWith("{") && value.endsWith("}")) {
                prefs.edit().putString(KEY_PROFILE, value).apply()
            }
        }

        // Secret the server hands this device once it has been verified by code.
        @JavascriptInterface
        fun getToken(): String = prefs.getString(KEY_TOKEN, "").orEmpty()

        @JavascriptInterface
        fun saveToken(value: String?) {
            if (value != null && TOKEN_PATTERN.matches(value)) {
                prefs.edit().putString(KEY_TOKEN, value).apply()
            }
        }

        // Called when the administrator unclaims this device: forget who it belongs to.
        @JavascriptInterface
        fun clearIdentity() {
            prefs.edit().remove(KEY_EMAIL).remove(KEY_TOKEN).apply()
        }

        private companion object {
            const val KEY_CATEGORY = "lastCategory"
            const val KEY_EMAIL = "verifiedEmail"
            const val KEY_PROFILE = "requesterProfile"
            const val KEY_TOKEN = "deviceToken"
            const val MAX_PROFILE_LENGTH = 500
            val TOKEN_PATTERN = Regex("^[A-Za-z0-9]{20,100}$")
            val VALID_CATEGORIES = setOf("flooring", "fireproofing", "equipment", "paint")
            val EMAIL_PATTERN = Regex("^[A-Za-z0-9._%+-]+@kaloutas\\.com$", RegexOption.IGNORE_CASE)
        }
    }

    private companion object {
        // A survey page or PDF with its pictures, as text: up to about 40 MB.
        const val MAX_DOC_CHARS = 40_000_000
        const val STATE_CAMERA_FILE = "cameraFile"
        const val PENDING_MAX_SIDE = 1600
    }
}
