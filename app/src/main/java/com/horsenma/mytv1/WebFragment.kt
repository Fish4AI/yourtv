package com.horsenma.mytv1

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient as AndroidWebChromeClient
import android.webkit.WebResourceRequest as AndroidWebResourceRequest
import android.webkit.WebResourceResponse as AndroidWebResourceResponse
import android.webkit.WebView as AndroidWebView
import android.webkit.WebViewClient as AndroidWebViewClient
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.horsenma.mytv1.models.TVModel
import android.net.http.SslError as AndroidSslError
import android.webkit.SslErrorHandler as AndroidSslErrorHandler
import com.tencent.smtt.sdk.WebChromeClient as X5WebChromeClient
import com.tencent.smtt.sdk.WebView as X5WebView
import com.tencent.smtt.sdk.WebViewClient as X5WebViewClient
import com.tencent.smtt.export.external.interfaces.SslError as X5SslError
import com.tencent.smtt.export.external.interfaces.SslErrorHandler as X5SslErrorHandler
import com.tencent.smtt.export.external.interfaces.WebResourceRequest as X5WebResourceRequest
import com.tencent.smtt.export.external.interfaces.WebResourceResponse as X5WebResourceResponse
import com.tencent.smtt.export.external.interfaces.ConsoleMessage as X5ConsoleMessage
import com.horsenma.yourtv.R
import com.horsenma.yourtv.PlayerFragment
import com.horsenma.yourtv.YourTVApplication
import java.io.ByteArrayInputStream
import androidx.core.graphics.createBitmap
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import android.view.Gravity
import android.content.res.Resources


@Suppress("DEPRECATION")
class WebFragment : Fragment(), WebFragmentCallback {
    private var webView: View? = null // 可空类型
    private var tvModel: TVModel? = null
    private val handler = Handler(Looper.myLooper()!!)
    private val delayHideVolume = 2 * 1000L
    private lateinit var icon: AppCompatImageView
    private lateinit var volume: ProgressBar
    private val scriptMap get() = com.horsenma.yourtv.data.Global.scriptMap
    private val blockMap get() = com.horsenma.yourtv.data.Global.blockMap
    private var finished = 0
    private var callback: WebFragmentCallback? = null
    internal var isPlaying = false
    private var playbackStartTime = 0L
    private var lastErrorTime = 0L
    private val revealFallbackDelay = 30_000L
    private val revealAnimationDuration = 160L
    private val playbackTimeoutDelay = 35_000L
    private var playbackLoadToken = 0
    private var loadingOverlay: View? = null
    private val errorSuppressionWindow = 2_000L // 2秒窗口

    // 设置回调
    fun setCallback(callback: WebFragmentCallback) {
        this.callback = callback
    }

    private fun prepareWebViewForPlayback() {
        playbackLoadToken++
        isPlaying = false
        webView?.apply {
            animate().cancel()
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            visibility = View.VISIBLE
        }
        showLoadingOverlay()
        val token = playbackLoadToken

        handler.postDelayed({
            if (token == playbackLoadToken && !isPlaying) {
                Log.w(TAG, "WebView playback is still waiting")
            }
        }, revealFallbackDelay)
    }

    private fun revealWebView(reason: String) {
        val view = webView ?: return
        view.post {
            hideLoadingOverlay()
            if (view.alpha >= 0.99f && view.visibility == View.VISIBLE) return@post
            view.visibility = View.VISIBLE
            view.animate()
                .alpha(1f)
                .setDuration(revealAnimationDuration)
                .withEndAction {
                    Log.d(TAG, "WebView revealed: reason=$reason, title=${tvModel?.tv?.title}")
                }
                .start()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        Log.d(TAG, "onActivityCreated called")
        // 动态调用 ready
        when (activity) {
            is com.horsenma.yourtv.MainActivity -> (activity as com.horsenma.yourtv.MainActivity).ready()
            is com.horsenma.mytv1.MainActivity -> (activity as com.horsenma.mytv1.MainActivity).ready(TAG)
            else -> Log.w(TAG, "Activity not supported for ready call")
        }
        super.onActivityCreated(savedInstanceState)
    }

    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled", "SuspiciousIndentation")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.black))
        }

        // 动态调整WebView布局
        fun updateWebViewLayout() {
            val application = requireActivity().applicationContext as YourTVApplication
            val isFullScreen = com.horsenma.yourtv.SP.fullScreenMode
            webView?.layoutParams = FrameLayout.LayoutParams(
                if (isFullScreen) ViewGroup.LayoutParams.MATCH_PARENT else application.videoWidthPx(),
                if (isFullScreen) ViewGroup.LayoutParams.MATCH_PARENT else application.videoHeightPx()
            ).apply {
                gravity = Gravity.CENTER // 确保非全屏时居中
            }
            webView?.requestLayout()
            // 强制刷新 WebView 内容缩放
            injectScalingCssForPiP() // 复用 PiP 的缩放逻辑，确保视频适配新尺寸
            Log.d(TAG, "WebView layout updated, fullScreenMode: $isFullScreen, width=${webView?.width}, height=${webView?.height}")
        }

        // 初始设置布局
        updateWebViewLayout()

        // 监听全屏模式变化
        YourTVApplication.getInstance().setFullScreenModeListener {
            Log.d(TAG, "Full screen mode changed via listener")
            updateWebViewLayout()
        }

        // 创建音量/亮度控件
        icon = AppCompatImageView(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                rightMargin = 8
            }
            setImageResource(R.drawable.volume_up_24px)
            visibility = View.GONE
        }
        volume = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                200.dpToPx(),
                5.dpToPx()
            ).apply {
                gravity = Gravity.CENTER
                setPadding(5.dpToPx(), 0, 0, 0)
            }
            max = 100
            progress = 50
            progressDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.custom_progress_drawable)
            visibility = View.GONE
        }
        root.addView(icon)
        root.addView(volume)
        loadingOverlay = createLoadingOverlay()

        // 动态创建 WebView
        val application = requireActivity().applicationContext as YourTVApplication
        webView = null
        try {
            if (SP.useX5WebView && YourTVApplication.getInstance().isX5Available()) {
                Log.i(TAG, "Creating X5 WebView")
                webView = X5WebView(requireContext()).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        javaScriptCanOpenWindowsAutomatically = true
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString =
                            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
                    }
                    webChromeClient = object : X5WebChromeClient() {
                        override fun getDefaultVideoPoster(): Bitmap {
                            return createBitmap(1, 1)
                        }

                        override fun onConsoleMessage(consoleMessage: X5ConsoleMessage?): Boolean {
                            if (consoleMessage != null) {
                                Log.d(TAG, "Console: ${consoleMessage.message()} [${consoleMessage.lineNumber()}]")
                                if (consoleMessage.message() == "success") {
                                    Log.i(TAG, "${tvModel?.tv?.title} success")
                                    tvModel?.tv?.finished?.let {
                                        evaluateJavascript(it) { res ->
                                            Log.i(TAG, "${tvModel?.tv?.title} finished: $res")
                                        }
                                    }
                                    tvModel?.setErrInfo("web ok")
                                    isPlaying = true
                                    playbackStartTime = System.currentTimeMillis()
                                    injectSuccessCleanup()
                                    revealWebView("playback-started")
                                    callback?.onPlaybackStarted()
                                    // Force a check in PlayerFragment to save stable source
                                    (parentFragment as? PlayerFragment)?.ensurePlaying()
                                }
                            }
                            return super.onConsoleMessage(consoleMessage)
                        }

                        override fun onShowCustomView(
                            view: View,
                            callback: com.tencent.smtt.export.external.interfaces.IX5WebChromeClient.CustomViewCallback
                        ) {
                            Log.i(TAG, "X5 onShowCustomView")
                            revealWebView("custom-view")
                            // 确保自定义视图无父视图
                            (view.parent as? ViewGroup)?.removeView(view)
                            root.addView(
                                view,
                                ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            )
                        }
                        override fun onHideCustomView() {
                            Log.i(TAG, "X5 onHideCustomView")
                            // 移除自定义视图
                            root.removeAllViews()
                            root.addView(webView)
                            root.addView(icon)
                            root.addView(volume)
                        }
                    }
                    webViewClient = object : X5WebViewClient() {
                        override fun onReceivedSslError(
                            view: X5WebView?,
                            handler: X5SslErrorHandler,
                            error: X5SslError?
                        ) {
                            handler.proceed()
                        }

                        override fun onReceivedError(view: X5WebView?, request: X5WebResourceRequest?, error: com.tencent.smtt.export.external.interfaces.WebResourceError?) {
                            val currentTime = System.currentTimeMillis()
                            // 避免短时间内重复触发
                            if (currentTime - lastErrorTime < errorSuppressionWindow) {
                                Log.d(TAG, "Suppressed X5 WebView error (within ${errorSuppressionWindow}ms): ${error?.description}, url=${request?.url}")
                                return
                            }
                            lastErrorTime = currentTime

                            val isMainFrame = request?.isForMainFrame ?: false
                            val errorDesc = error?.description ?: "Unknown error"
                            val url = request?.url?.toString() ?: "Unknown URL"

                            Log.e(TAG, "X5 WebView error: $errorDesc, url=$url, isMainFrame=$isMainFrame, isPlaying=$isPlaying")

                            // 仅对主框架错误或未开始播放时触发 onPlaybackError
                            if (isMainFrame || !isPlaying) {
                                isPlaying = false
                                //callback?.onPlaybackError("WebView error: $errorDesc (url=$url)")
                            } else {
                                Log.d(TAG, "Ignored non-main frame error for ${tvModel?.tv?.title}: $errorDesc, url=$url")
                            }
                        }

                        override fun shouldInterceptRequest(
                            view: X5WebView?,
                            request: X5WebResourceRequest?
                        ): X5WebResourceResponse? {
                            val uri = request?.url
                            Log.d(
                                TAG,
                                "${request?.method} ${uri.toString()} ${request?.requestHeaders}"
                            )
                            blockMap[tvModel?.tv?.group]?.let {
                                for (i in it) {
                                    if (uri?.path?.contains(i) == true) {
                                        Log.i(TAG, "block path ${uri.path}")
                                        return X5WebResourceResponse(
                                            "text/plain",
                                            "utf-8",
                                            null
                                        )
                                    }
                                }
                            }
                            tvModel?.tv?.block?.let {
                                for (i in it) {
                                    if (uri?.path?.contains(i) == true) {
                                        Log.i(TAG, "block path ${uri.path}")
                                        return X5WebResourceResponse(
                                            "text/plain",
                                            "utf-8",
                                            null
                                        )
                                    }
                                }
                            }
                            if (uri?.path?.endsWith(".css") == true) {
                                return X5WebResourceResponse(
                                    "text/css",
                                    "utf-8",
                                    ByteArrayInputStream(ByteArray(0))
                                )
                            }
                            if (!request?.isForMainFrame!! && (uri?.path?.endsWith(".jpg") == true || uri?.path?.endsWith(
                                    ".jpeg"
                                ) == true ||
                                        uri?.path?.endsWith(".png") == true || uri?.path?.endsWith(
                                    ".gif"
                                ) == true ||
                                        uri?.path?.endsWith(".webp") == true || uri?.path?.endsWith(
                                    ".svg"
                                ) == true)
                            ) {
                                return X5WebResourceResponse(
                                    "image/png",
                                    "utf-8",
                                    ByteArrayInputStream(ByteArray(0))
                                )
                            }
                            return null
                        }

                        override fun onPageStarted(view: X5WebView?, url: String?, favicon: Bitmap?) {
                            Log.i(TAG, "X5 onPageStarted $url")
                            (webView as X5WebView).evaluateJavascript(initialVideoStyleScript(), null)
                        }

                        @SuppressLint("UseKtx")
                        override fun onPageFinished(view: X5WebView?, url: String?) {
                            if ((webView as X5WebView).progress < 100) {
                                super.onPageFinished(view, url)
                                return
                            }
                            finished++
                            if (finished < 1) {
                                super.onPageFinished(view, url)
                                return
                            }
                            Log.i(TAG, "X5 onPageFinished $finished $url")
                            tvModel?.tv?.started?.let {
                                (webView as X5WebView).evaluateJavascript(it, null)
                                Log.i(TAG, "started")
                            }
                            tvModel?.tv?.script?.let {
                                (webView as X5WebView).evaluateJavascript(it, null)
                                Log.i(TAG, "script")
                            }
                            val uri = Uri.parse(url)
                            var script = scriptMap[uri?.host]
                            if (script == null) {
                                script = R.raw.ahtv1
                            }
                            var s = requireContext().resources.openRawResource(script)
                                .bufferedReader()
                                .use { it.readText() }
                            // 替换 object-fit 和 body.style.left
                            s = s.replace(
                                "video.style.objectFit = 'contain';",
                                "video.style.objectFit = 'fill';"
                            ).replace(
                                "body.style.left = '100vw';",
                                "body.style.left = '0'; body.style.width = '100vw'; body.style.height = '100vh';"
                            )
                            tvModel?.tv?.id?.let {
                                s = s.replace("{id}", "$it")
                            }
                            tvModel?.tv?.selector?.let {
                                s = s.replace("{selector}", it)
                            }
                            tvModel?.tv?.index?.let {
                                s = s.replace("{index}", "$it")
                            }
                            Log.d(TAG, "Modified script: $s")
                            (webView as X5WebView).evaluateJavascript(s, null)
                            // 注入视频播放监听脚本
                            val jsCode = """
        (() => {
            const video = document.querySelector('video');
            if (video && !video.paused && !video.ended) {
                console.log('success');
                return;
            }
            setTimeout(() => {
                const v = document.querySelector('video');
                if (v && !v.paused && !v.ended) {
                    console.log('success');
                }
            }, 1000);
        })();
    """.trimIndent()
                            (webView as X5WebView).evaluateJavascript(jsCode, null)
                            Log.i(TAG, "Injected enhanced playback listener for X5WebView")
                        }
                    }
                }
            } else {
                Log.i(TAG, "Creating System WebView")
                webView = AndroidWebView(requireContext()).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        application.videoWidthPx(),
                        application.videoHeightPx()
                    ).apply {
                        gravity = Gravity.CENTER
                    }
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        javaScriptCanOpenWindowsAutomatically = true
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString =
                            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
                    }
                    webChromeClient =
                        object : AndroidWebChromeClient() {
                            override fun getDefaultVideoPoster(): Bitmap {
                                return createBitmap(1, 1)
                            }

                            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                                if (consoleMessage != null) {
                                    Log.d(TAG, "Console: ${consoleMessage.message()} [${consoleMessage.lineNumber()}]")
                                    if (consoleMessage.message() == "success") {
                                        Log.i(TAG, "${tvModel?.tv?.title} success")
                                        tvModel?.tv?.finished?.let {
                                            evaluateJavascript(it) { res ->
                                                Log.i(TAG, "${tvModel?.tv?.title} finished: $res")
                                            }
                                        }
                                        tvModel?.setErrInfo("web ok")
                                        isPlaying = true
                                        playbackStartTime = System.currentTimeMillis()
                                        injectSuccessCleanup()
                                        revealWebView("playback-started")
                                        callback?.onPlaybackStarted()
                                    }
                                }
                                return super.onConsoleMessage(consoleMessage)
                            }

                            override fun onShowCustomView(
                                view: View?,
                                callback: AndroidWebChromeClient.CustomViewCallback?
                            ) {
                                Log.i(TAG, "System onShowCustomView")
                                revealWebView("custom-view")
                                view?.let {
                                    (it.parent as? ViewGroup)?.removeView(it)
                                    root.addView(
                                        it,
                                        ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    )
                                }
                            }

                            override fun onHideCustomView() {
                                Log.i(TAG, "System onHideCustomView")
                                root.removeAllViews()
                                root.addView(webView)
                                root.addView(icon)
                                root.addView(volume)
                            }
                        }
                    webViewClient =
                        object : AndroidWebViewClient() {
                            @SuppressLint("WebViewClientOnReceivedSslError")
                            override fun onReceivedSslError(
                                view: AndroidWebView?,
                                handler: AndroidSslErrorHandler?,
                                error: AndroidSslError?
                            ) {
                                handler?.proceed()
                            }

                            override fun onReceivedError(view: AndroidWebView?, request: AndroidWebResourceRequest?, error: android.webkit.WebResourceError?) {
                                val currentTime = System.currentTimeMillis()
                                // 避免短时间内重复触发
                                if (currentTime - lastErrorTime < errorSuppressionWindow) {
                                    Log.d(TAG, "Suppressed System WebView error (within ${errorSuppressionWindow}ms): ${error?.description}, url=${request?.url}")
                                    return
                                }
                                lastErrorTime = currentTime

                                val isMainFrame = request?.isForMainFrame ?: false
                                val errorDesc = error?.description ?: "Unknown error"
                                val url = request?.url?.toString() ?: "Unknown URL"

                                Log.e(TAG, "System WebView error: $errorDesc, url=$url, isMainFrame=$isMainFrame, isPlaying=$isPlaying")

                                // 仅对主框架错误或未开始播放时触发 onPlaybackError
                                if (isMainFrame || !isPlaying) {
                                    isPlaying = false
                                    //callback?.onPlaybackError("WebView error: $errorDesc (url=$url)")
                                } else {
                                    Log.d(TAG, "Ignored non-main frame error for ${tvModel?.tv?.title}: $errorDesc, url=$url")
                                }
                            }

                            override fun shouldInterceptRequest(
                                view: AndroidWebView?,
                                request: AndroidWebResourceRequest?
                            ): AndroidWebResourceResponse? {
                                val uri = request?.url
                                //Log.d(TAG, "${request?.method} ${uri.toString()} ${request?.requestHeaders}")
                                blockMap[tvModel?.tv?.group]?.let {
                                    for (i in it) {
                                        if (uri?.path?.contains(i) == true) {
                                            Log.i(TAG, "block path ${uri.path}")
                                            return AndroidWebResourceResponse(
                                                "text/plain",
                                                "utf-8",
                                                null
                                            )
                                        }
                                    }
                                }
                                tvModel?.tv?.block?.let {
                                    for (i in it) {
                                        if (uri?.path?.contains(i) == true) {
                                            Log.i(TAG, "block path ${uri.path}")
                                            return AndroidWebResourceResponse(
                                                "text/plain",
                                                "utf-8",
                                                null
                                            )
                                        }
                                    }
                                }
                                if (uri?.path?.endsWith(".css") == true) {
                                    return AndroidWebResourceResponse(
                                        "text/css",
                                        "utf-8",
                                        ByteArrayInputStream(ByteArray(0))
                                    )
                                }
                                if (!request?.isForMainFrame!! && (uri?.path?.endsWith(".jpg") == true || uri?.path?.endsWith(
                                        ".jpeg"
                                    ) == true ||
                                            uri?.path?.endsWith(".png") == true || uri?.path?.endsWith(
                                        ".gif"
                                    ) == true ||
                                            uri?.path?.endsWith(".webp") == true || uri?.path?.endsWith(
                                        ".svg"
                                    ) == true)
                                ) {
                                    return AndroidWebResourceResponse(
                                        "image/png",
                                        "utf-8",
                                        ByteArrayInputStream(ByteArray(0))
                                    )
                                }
                                return null
                            }

                            override fun onPageStarted(view: AndroidWebView?, url: String?, favicon: Bitmap?) {
                                Log.i(TAG, "System onPageStarted $url")
                                super.onPageStarted(view, url, favicon)
                                (webView as AndroidWebView).evaluateJavascript(initialVideoStyleScript(), null)
                            }

                            @SuppressLint("UseKtx")
                            override fun onPageFinished(view: AndroidWebView?, url: String?) {
                                if ((webView as AndroidWebView).progress < 100) {
                                    super.onPageFinished(view, url)
                                    return
                                }
                                finished++
                                if (finished < 1) {
                                    super.onPageFinished(view, url)
                                    return
                                }
                                Log.i(TAG, "System onPageFinished $finished $url")
                                tvModel?.tv?.started?.let {
                                    (webView as AndroidWebView).evaluateJavascript(it, null)
                                    Log.i(TAG, "started")
                                }
                                tvModel?.tv?.script?.let {
                                    (webView as AndroidWebView).evaluateJavascript(it, null)
                                    Log.i(TAG, "script")
                                }
                                val uri = Uri.parse(url)
                                var script = scriptMap[uri?.host]
                                if (script == null) {
                                    script = R.raw.ahtv1
                                }
                                var s = requireContext().resources.openRawResource(script)
                                    .bufferedReader()
                                    .use { it.readText() }
                                // 替换 object-fit 和 body.style.left
                                s = s.replace(
                                    "video.style.objectFit = 'contain';",
                                    "video.style.objectFit = 'fill';"
                                ).replace(
                                    "body.style.left = '100vw';",
                                    "body.style.left = '0'; body.style.width = '100vw'; body.style.height = '100vh';"
                                )
                                tvModel?.tv?.id?.let {
                                    s = s.replace("{id}", "$it")
                                }
                                tvModel?.tv?.selector?.let {
                                    s = s.replace("{selector}", it)
                                }
                                tvModel?.tv?.index?.let {
                                    s = s.replace("{index}", "$it")
                                }
                                Log.d(TAG, "Modified script: $s")
                                (webView as AndroidWebView).evaluateJavascript(s, null)
                                // 注入视频播放监听脚本
                                val jsCode = """
        (() => {
            const video = document.querySelector('video');
            if (video && !video.paused && !video.ended) {
                console.log('success');
                return;
            }
            setTimeout(() => {
                const v = document.querySelector('video');
                if (v && !v.paused && !v.ended) {
                    console.log('success');
                }
            }, 1000);
        })();
    """.trimIndent()
                                (webView as AndroidWebView).evaluateJavascript(jsCode, null)
                                Log.i(TAG, "Injected enhanced playback listener for AndroidWebView")
                            }
                        }
                }
            }
            webView?.let {
                // 确保 webView 无父视图
                (it.parent as? ViewGroup)?.removeView(it)
                root.addView(it)
                loadingOverlay?.let { overlay -> root.addView(overlay) }
            } ?: run {
                Log.e(TAG, "Failed to create WebView, webView is null")
                tvModel?.setErrInfo("WebView creation failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create System WebView: ${e.message}", e)
            webView = null
            tvModel?.setErrInfo("System WebView 初始化失败")
        }

        // 配置 WebView 属性
        webView?.apply {
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            webView?.apply {
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
                setOnTouchListener { _, event ->
                    when (activity) {
                        is com.horsenma.yourtv.MainActivity -> {
                            (activity as com.horsenma.yourtv.MainActivity).gestureDetector.onTouchEvent(event)
                            true // 强制返回 true
                        }
                        is com.horsenma.mytv1.MainActivity -> {
                            (activity as com.horsenma.mytv1.MainActivity).gestureDetector.onTouchEvent(event)
                            true // 强制返回 true
                        }
                        else -> {
                            Log.w(TAG, "Activity not supported for gesture handling")
                            false
                        }
                    }
                }
            }
        }

        when (activity) {
            is com.horsenma.yourtv.MainActivity -> (activity as com.horsenma.yourtv.MainActivity).ready()
            is com.horsenma.mytv1.MainActivity -> (activity as com.horsenma.mytv1.MainActivity).ready(TAG)
            else -> Log.w(TAG, "Activity not supported for ready call")
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (webView == null) {
            Log.e(TAG, "WebView not initialized")
            tvModel?.setErrInfo("WebView initialization failed")
        }
    }

    fun play(tvModel: TVModel) {
        finished = 0
        if (this.tvModel != tvModel) {
            stopPlayback()
        }
        this.tvModel = tvModel
        val url = tvModel.videoUrl.value as String
        Log.i(TAG, "play ${tvModel.tv.id} ${tvModel.tv.title} $url")

        if (webView == null) {
            Log.e(TAG, "WebView is null, cannot play $url")
            tvModel.setErrInfo("WebView not initialized")
            callback?.onPlaybackError("WebView not initialized")
            return
        }
        prepareWebViewForPlayback()
        val token = playbackLoadToken

        // 添加超时检测
        handler.postDelayed({
            if (token == playbackLoadToken && !isPlaying && webView != null) {
                Log.w(TAG, "Playback timeout for ${tvModel.tv.title}, attempting recovery")
                when (webView) {
                    is X5WebView -> {
                        if ((webView as X5WebView).canGoBack()) {
                            (webView as X5WebView).goBack()
                        } else {
                            (webView as X5WebView).reload()
                        }
                    }
                    is AndroidWebView -> {
                        if ((webView as AndroidWebView).canGoBack()) {
                            (webView as AndroidWebView).goBack()
                        } else {
                            (webView as AndroidWebView).reload()
                        }
                    }
                }
                // 通知 PlayerFragment 切换源
                hideLoadingOverlay()
                callback?.onPlaybackError("Playback timeout")
            }
        }, playbackTimeoutDelay)

        when (webView) {
            is X5WebView -> (webView as X5WebView).loadUrl(url)
            is AndroidWebView -> (webView as AndroidWebView).loadUrl(url)
            else -> {
                Log.e(TAG, "Invalid WebView type: $webView")
                tvModel.setErrInfo("Invalid WebView type")
                return
            }
        }
        webView?.visibility = View.VISIBLE
    }

    fun updateWebViewLayout() {
        val application = requireActivity().applicationContext as YourTVApplication
        webView?.layoutParams = FrameLayout.LayoutParams(
            application.videoWidthPx(),
            application.videoHeightPx()
        ).apply {
            gravity = Gravity.CENTER // 确保非全屏时居中
        }
        webView?.requestLayout()
        Log.d(TAG, "WebView layout updated, fullScreenMode: ${com.horsenma.yourtv.SP.fullScreenMode}")
    }

    fun showVolume(visibility: Int) {
        icon.visibility = visibility
        volume.visibility = visibility
        hideVolume()
    }

    fun setVolumeMax(max: Int) {
        volume.max = max
    }

    fun setVolume(progress: Int, isVolume: Boolean = false) {
        volume.progress = progress
        icon.setImageDrawable(ContextCompat.getDrawable(
            requireContext(),
            if (isVolume) {
                if (progress > 0) R.drawable.volume_up_24px else R.drawable.volume_off_24px
            } else {
                R.drawable.light_mode_24px
            }
        ))
    }

    fun hideVolume() {
        handler.removeCallbacks(hideVolumeRunnable)
        handler.postDelayed(hideVolumeRunnable, delayHideVolume)
    }

    fun hideVolumeNow() {
        handler.removeCallbacks(hideVolumeRunnable)
        handler.postDelayed(hideVolumeRunnable, 0)
    }

    private fun createLoadingOverlay(): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            addView(TextView(context).apply {
                text = context.getString(R.string.web_buffering_message)
                setTextColor(Color.WHITE)
                textSize = 18f
                alpha = 0.88f
                gravity = Gravity.CENTER
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
        }
    }

    private fun showLoadingOverlay() {
        loadingOverlay?.apply {
            animate().cancel()
            alpha = 1f
            visibility = View.VISIBLE
            bringToFront()
        }
    }

    private fun hideLoadingOverlay() {
        loadingOverlay?.apply {
            animate().cancel()
            if (visibility != View.VISIBLE) return
            animate()
                .alpha(0f)
                .setDuration(revealAnimationDuration)
                .withEndAction {
                    visibility = View.GONE
                }
                .start()
        }
    }

    private val hideVolumeRunnable = Runnable {
        icon.visibility = View.GONE
        volume.visibility = View.GONE
    }

    private fun initialVideoStyleScript(): String = """
        (() => {
            const style = document.createElement('style');
            style.type = 'text/css';
            style.innerHTML = `
            body {
                margin: 0;
                padding: 0;
                background-color: #000;
                width: 100vw !important;
                height: 100vh !important;
                position: absolute !important;
                left: 0 !important;
                top: 0 !important;
                opacity: 0 !important;
            }
            img:not([role="presentation"]) {
                display: none !important;
            }
            video, iframe, object, embed {
                display: block !important;
                position: fixed !important;
                top: 0 !important;
                left: 0 !important;
                width: 100vw !important;
                height: 100vh !important;
                object-fit: fill !important;
                background-color: transparent !important;
                z-index: 9999 !important;
            }
            `;
            document.head.appendChild(style);
        })();
    """.trimIndent()

    private fun injectSuccessCleanup() {
        val jsCode = successCleanupScript()
        when (webView) {
            is X5WebView -> {
                (webView as X5WebView).evaluateJavascript(jsCode, null)
                Log.d(TAG, "Injected success cleanup for X5WebView")
            }
            is AndroidWebView -> {
                (webView as AndroidWebView).evaluateJavascript(jsCode, null)
                Log.d(TAG, "Injected success cleanup for AndroidWebView")
            }
            else -> {
                Log.w(TAG, "Invalid WebView type, cannot inject success cleanup")
            }
        }
    }

    private fun successCleanupScript(): String = """
        (() => {
            const STYLE_ID = 'fish-success-cleanup-style';
            const style = document.getElementById(STYLE_ID) || document.createElement('style');
            style.id = STYLE_ID;
            style.textContent = `
                html, body {
                    margin: 0 !important;
                    padding: 0 !important;
                    width: 100vw !important;
                    height: 100vh !important;
                    overflow: hidden !important;
                    background: #000 !important;
                    opacity: 1 !important;
                }
                video {
                    display: block !important;
                    position: fixed !important;
                    inset: 0 !important;
                    width: 100vw !important;
                    height: 100vh !important;
                    object-fit: fill !important;
                    background: #000 !important;
                    opacity: 1 !important;
                    visibility: visible !important;
                    z-index: 2147483647 !important;
                    transform: none !important;
                }
            `;
            if (!style.parentNode) {
                (document.head || document.documentElement).appendChild(style);
            }

            const areaOf = (element) => {
                try {
                    const rect = element.getBoundingClientRect();
                    return Math.max(0, rect.width) * Math.max(0, rect.height);
                } catch (_) {
                    return 0;
                }
            };

            const findVideo = () => {
                const videos = Array.from(document.querySelectorAll('video'));
                return videos
                    .filter((video) => video.currentSrc || video.src || video.readyState > 0 || video.videoWidth > 0 || areaOf(video) > 0)
                    .sort((a, b) => areaOf(b) - areaOf(a))[0] || videos[0] || null;
            };

            const hideElement = (element) => {
                const tagName = (element.tagName || '').toLowerCase();
                if (tagName === 'script' || tagName === 'style' || tagName === 'link') return;
                element.style.setProperty('display', 'none', 'important');
                element.style.setProperty('visibility', 'hidden', 'important');
                element.style.setProperty('opacity', '0', 'important');
                element.style.setProperty('pointer-events', 'none', 'important');
            };

            const revealPath = (video) => {
                let node = video;
                while (node && node !== document.documentElement) {
                    if (node.style) {
                        node.style.setProperty('display', 'block', 'important');
                        node.style.setProperty('visibility', 'visible', 'important');
                        node.style.setProperty('opacity', '1', 'important');
                        node.style.setProperty('background', 'transparent', 'important');
                    }
                    node = node.parentElement;
                }
            };

            const cleanAround = (root, video) => {
                Array.from(root.children || []).forEach((child) => {
                    if (child === video || child.contains(video)) {
                        cleanAround(child, video);
                    } else {
                        hideElement(child);
                    }
                });
            };

            const promoteVideo = () => {
                const video = findVideo();
                if (!video || !document.body) return false;
                document.body.style.setProperty('opacity', '1', 'important');
                document.body.style.setProperty('background', '#000', 'important');
                document.body.style.setProperty('overflow', 'hidden', 'important');
                revealPath(video);
                video.style.setProperty('display', 'block', 'important');
                video.style.setProperty('position', 'fixed', 'important');
                video.style.setProperty('inset', '0', 'important');
                video.style.setProperty('width', '100vw', 'important');
                video.style.setProperty('height', '100vh', 'important');
                video.style.setProperty('object-fit', 'fill', 'important');
                video.style.setProperty('z-index', '2147483647', 'important');
                video.style.setProperty('opacity', '1', 'important');
                video.style.setProperty('visibility', 'visible', 'important');
                video.controls = false;
                video.autoplay = true;
                try {
                    video.muted = false;
                    video.volume = 1;
                    if (video.paused) {
                        const p = video.play();
                        if (p && p.catch) p.catch(() => {});
                    }
                } catch (_) {}
                cleanAround(document.body, video);
                return true;
            };

            let count = 0;
            const tick = () => {
                promoteVideo();
                count += 1;
                if (count >= 24 && window.__fishSuccessCleanupTimer) {
                    clearInterval(window.__fishSuccessCleanupTimer);
                    window.__fishSuccessCleanupTimer = null;
                }
            };
            if (window.__fishSuccessCleanupTimer) {
                clearInterval(window.__fishSuccessCleanupTimer);
            }
            window.__fishSuccessCleanupTimer = setInterval(tick, 500);
            tick();
        })();
    """.trimIndent()

    private fun injectPlaybackGuard() {
        val jsCode = webPlaybackGuardScript()
        when (webView) {
            is X5WebView -> {
                (webView as X5WebView).evaluateJavascript(jsCode, null)
                Log.d(TAG, "Injected playback guard for X5WebView")
            }
            is AndroidWebView -> {
                (webView as AndroidWebView).evaluateJavascript(jsCode, null)
                Log.d(TAG, "Injected playback guard for AndroidWebView")
            }
            else -> {
                Log.w(TAG, "Invalid WebView type, cannot inject playback guard")
            }
        }
    }

    private fun webPlaybackGuardScript(): String = """
        (() => {
            const STYLE_ID = 'fish-web-playback-style';
            const STAGE_ID = 'fish-web-video-stage';

            const installStyle = () => {
                const head = document.head || document.documentElement;
                if (!head || document.getElementById(STYLE_ID)) return;
                const style = document.createElement('style');
                style.id = STYLE_ID;
                style.textContent = `
                    html, body {
                        width: 100vw !important;
                        height: 100vh !important;
                        margin: 0 !important;
                        padding: 0 !important;
                        overflow: hidden !important;
                        background: #000 !important;
                    }
                    #fish-web-video-stage {
                        display: block !important;
                        position: fixed !important;
                        inset: 0 !important;
                        width: 100vw !important;
                        height: 100vh !important;
                        overflow: hidden !important;
                        background: #000 !important;
                        z-index: 2147483647 !important;
                    }
                    #fish-web-video-stage video,
                    #fish-web-video-stage iframe,
                    #fish-web-video-stage object,
                    #fish-web-video-stage embed {
                        display: block !important;
                        position: absolute !important;
                        inset: 0 !important;
                        width: 100vw !important;
                        height: 100vh !important;
                        min-width: 100vw !important;
                        min-height: 100vh !important;
                        margin: 0 !important;
                        padding: 0 !important;
                        border: 0 !important;
                        background: #000 !important;
                        object-fit: fill !important;
                        transform: none !important;
                        opacity: 1 !important;
                        visibility: visible !important;
                        z-index: 2147483647 !important;
                    }
                `;
                head.appendChild(style);
            };

            const ensureStage = () => {
                if (!document.body) return null;
                installStyle();
                document.body.style.setProperty('background', '#000', 'important');
                document.body.style.setProperty('overflow', 'hidden', 'important');
                let stage = document.getElementById(STAGE_ID);
                if (!stage) {
                    stage = document.createElement('div');
                    stage.id = STAGE_ID;
                    document.body.appendChild(stage);
                }
                stage.style.setProperty('display', 'block', 'important');
                stage.style.setProperty('position', 'fixed', 'important');
                stage.style.setProperty('inset', '0', 'important');
                stage.style.setProperty('z-index', '2147483647', 'important');
                stage.style.setProperty('background', '#000', 'important');
                return stage;
            };

            const areaOf = (element) => {
                try {
                    const rect = element.getBoundingClientRect();
                    return Math.max(0, rect.width) * Math.max(0, rect.height);
                } catch (_) {
                    return 0;
                }
            };

            const shadowVideo = () => {
                const all = document.querySelectorAll('*');
                for (const element of all) {
                    if (element.shadowRoot) {
                        const video = element.shadowRoot.querySelector('video');
                        if (video) return video;
                    }
                }
                return null;
            };

            const chooseMedia = () => {
                const videos = Array.from(document.querySelectorAll('video'));
                const readyVideo = videos
                    .filter((video) => video.currentSrc || video.src || video.readyState > 0 || video.videoWidth > 0)
                    .sort((a, b) => areaOf(b) - areaOf(a))[0];
                if (readyVideo) return readyVideo;
                const shadow = shadowVideo();
                if (shadow) return shadow;
                return Array.from(document.querySelectorAll('iframe, object, embed'))
                    .filter((element) => element.src || areaOf(element) > 0)
                    .sort((a, b) => areaOf(b) - areaOf(a))[0] || null;
            };

            const styleMedia = (media) => {
                media.style.setProperty('display', 'block', 'important');
                media.style.setProperty('position', 'absolute', 'important');
                media.style.setProperty('inset', '0', 'important');
                media.style.setProperty('width', '100vw', 'important');
                media.style.setProperty('height', '100vh', 'important');
                media.style.setProperty('min-width', '100vw', 'important');
                media.style.setProperty('min-height', '100vh', 'important');
                media.style.setProperty('margin', '0', 'important');
                media.style.setProperty('padding', '0', 'important');
                media.style.setProperty('border', '0', 'important');
                media.style.setProperty('background', '#000', 'important');
                media.style.setProperty('object-fit', 'fill', 'important');
                media.style.setProperty('transform', 'none', 'important');
                media.style.setProperty('opacity', '1', 'important');
                media.style.setProperty('visibility', 'visible', 'important');
                media.style.setProperty('z-index', '2147483647', 'important');
            };

            const hidePageChrome = (stage) => {
                Array.from(document.body.children).forEach((element) => {
                    if (element === stage) return;
                    const tagName = (element.tagName || '').toLowerCase();
                    if (tagName === 'script' || tagName === 'style' || tagName === 'link') return;
                    element.style.setProperty('display', 'none', 'important');
                    element.style.setProperty('visibility', 'hidden', 'important');
                });
            };

            const reportSuccessOnce = () => {
                if (window.__fishWebPlaybackGuardSuccessSent) return;
                window.__fishWebPlaybackGuardSuccessSent = true;
                console.log('success');
            };

            const promote = () => {
                const stage = ensureStage();
                if (!stage) return;
                const media = chooseMedia();
                if (!media) return;
                try {
                    if (media.parentElement !== stage) {
                        stage.appendChild(media);
                    }
                } catch (_) {
                    // Some shadow-DOM videos cannot be moved; styling them is still helpful.
                }
                styleMedia(media);
                hidePageChrome(stage);
                if ((media.tagName || '').toLowerCase() === 'video') {
                    try {
                        media.autoplay = true;
                        media.controls = false;
                        media.muted = false;
                        media.volume = 1;
                        if (media.paused) {
                            const playPromise = media.play();
                            if (playPromise && playPromise.catch) playPromise.catch(() => {});
                        }
                    } catch (_) {}
                    if (!media.paused || media.readyState >= 2 || media.videoWidth > 0) {
                        reportSuccessOnce();
                    }
                } else {
                    reportSuccessOnce();
                }
            };

            window.__fishWebPlaybackGuardTick = promote;
            if (!window.__fishWebPlaybackGuardInterval) {
                window.__fishWebPlaybackGuardInterval = setInterval(promote, 300);
            }
            if (!window.__fishWebPlaybackGuardObserver && document.documentElement) {
                window.__fishWebPlaybackGuardObserver = new MutationObserver(promote);
                window.__fishWebPlaybackGuardObserver.observe(document.documentElement, {
                    childList: true,
                    subtree: true,
                    attributes: true
                });
            }
            promote();
        })();
    """.trimIndent()

    fun stopPlayback() {
        hideLoadingOverlay()
        if (isPlaying || playbackStartTime > 0) {
            isPlaying = false
            playbackStartTime = 0L
            callback?.onPlaybackStopped()
            when (webView) {
                is X5WebView -> {
                    (webView as X5WebView).stopLoading()
                    (webView as X5WebView).loadUrl("about:blank")
                    Log.d(TAG, "Stopped X5WebView playback")
                }

                is AndroidWebView -> {
                    (webView as AndroidWebView).stopLoading()
                    (webView as AndroidWebView).loadUrl("about:blank")
                    Log.d(TAG, "Stopped AndroidWebView playback")
                }

                else -> {
                    Log.w(TAG, "Invalid WebView type, cannot stop playback")
                }
            }
            Log.d(TAG, "Stopped WebFragment playback for ${tvModel?.tv?.title}")
        } else {
            Log.d(TAG, "Skipped stopPlayback, already stopped for ${tvModel?.tv?.title}")
        }
    }

    fun injectScalingCssForPiP() {
        val jsCode = """
        (function() {
            // 创建全局样式
            var style = document.createElement('style');
            style.type = 'text/css';
            style.innerHTML = `
                body {
                    margin: 0 !important;
                    padding: 0 !important;
                    width: 100% !important;
                    height: 100% !important;
                    overflow: hidden !important;
                    background-color: #000 !important;
                }
                video, iframe, object, embed {
                    display: block !important;
                    position: fixed !important;
                    top: 0 !important;
                    left: 0 !important;
                    width: 100% !important;
                    height: 100% !important;
                    object-fit: contain !important;
                    background-color: transparent !important;
                    z-index: 9999 !important;
                }
            `;
            // 移除现有同类样式以避免冲突
            document.querySelectorAll('style[data-pip-scaling]').forEach(function(el) {
                el.remove();
            });
            style.setAttribute('data-pip-scaling', 'true');
            document.head.appendChild(style);

            // 直接调整视频和 iframe 元素
            var video = document.querySelector('video');
            if (video) {
                video.style.width = '100%';
                video.style.height = '100%';
                video.style.objectFit = 'contain';
                video.style.position = 'fixed';
                video.style.top = '0';
                video.style.left = '0';
                video.style.zIndex = '9999';
            }
            var iframe = document.querySelector('iframe');
            if (iframe) {
                iframe.style.width = '100%';
                iframe.style.height = '100%';
                iframe.style.objectFit = 'contain';
                iframe.style.position = 'fixed';
                iframe.style.top = '0';
                iframe.style.left = '0';
                iframe.style.zIndex = '9999';
            }
        })();
    """.trimIndent()

        when (webView) {
            is X5WebView -> {
                (webView as X5WebView).evaluateJavascript(jsCode, null)
                Log.d(TAG, "Injected enhanced CSS/JS for X5WebView content scaling in PiP mode")
            }
            is AndroidWebView -> {
                (webView as AndroidWebView).evaluateJavascript(jsCode, null)
                Log.d(TAG, "Injected enhanced CSS/JS for AndroidWebView content scaling in PiP mode")
            }
            else -> {
                Log.w(TAG, "Invalid WebView type, cannot inject CSS for PiP mode")
            }
        }
    }

    override fun onPlaybackStarted() {
        isPlaying = true
        playbackStartTime = System.currentTimeMillis()
        Log.d(TAG, "onPlaybackStarted for ${tvModel?.tv?.title}")
    }

    override fun onPlaybackStopped() {
        isPlaying = false
        playbackStartTime = 0L
        Log.d(TAG, "onPlaybackStopped for ${tvModel?.tv?.title}")
    }

    override fun onPlaybackError(error: String) {
        isPlaying = false
        playbackStartTime = 0L
        hideLoadingOverlay()
        Log.e(TAG, "onPlaybackError for ${tvModel?.tv?.title}: $error")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        webView?.let {
            val parent = it.parent as? ViewGroup
            parent?.removeView(it)
            when (it) {
                is X5WebView -> it.destroy()
                is AndroidWebView -> it.destroy()
            }
        }
        webView = null
        loadingOverlay = null
        callback = null
    }

    companion object {
        private const val TAG = "WebFragment"
        private fun Int.dpToPx(): Int = (this * Resources.getSystem().displayMetrics.density).toInt()
    }
}
