package by.innowise.trainchecker

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import by.innowise.trainchecker.databinding.ActivityBookingWebviewBinding
import kotlinx.coroutines.launch

class BookingWebViewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBookingWebviewBinding
    private lateinit var request: BookingRequest
    private lateinit var logger: BookingLogger
    private var stateMachine: BookingStateMachine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var resultBroadcastSent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val parsedRequest = BookingRequest.from(intent)
        if (parsedRequest == null) {
            finish()
            return
        }
        request = parsedRequest
        logger = BookingLogger(this, request.routeId)

        binding = ActivityBookingWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLockScreenWindow()
        acquireWakeLock()
        setupBackHandling()
        setupWebView()
        updateHeader(BookingState.LOAD_ROUTE, "Starting")

        logger.log("WebView booking started for route ${request.routeName} train ${request.primaryTrainNumber}")
        sendTelegram("TrainChecker: WebView booking started for ${request.routeName}. Train: ${request.primaryTrainNumber}")

        stateMachine = BookingStateMachine(
            webView = binding.bookingWebView,
            request = request,
            logger = logger,
            onStateChanged = ::updateHeader,
            onVerification = ::onVerificationDetected,
            onDone = ::onBookingDone,
            onError = ::onBookingError
        ).also { it.start() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onDestroy() {
        if (!resultBroadcastSent && isFinishing) {
            val message = "Booking activity was closed before completion"
            logger.log(message, MonitoringLogLevel.WARNING)
            broadcastResult(false, message, BookingState.ERROR)
        }
        stateMachine?.stop()
        releaseWakeLock()
        logger.close()
        super.onDestroy()
    }

    private fun setupLockScreenWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:BookingWebView"
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.bookingWebView.canGoBack()) {
                    binding.bookingWebView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.bookingWebView, true)

        binding.bookingWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        binding.bookingWebView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                binding.progressBar.isIndeterminate = newProgress < 5
            }
        }

        binding.bookingWebView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.detailsText.text = url.orEmpty()
                binding.progressBar.isIndeterminate = true
                logger.log("WEBVIEW PAGE_STARTED url=${url.orEmpty()}")
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.detailsText.text = url.orEmpty()
                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = 100
                logger.log("WEBVIEW PAGE_FINISHED url=${url.orEmpty()} title=${view?.title.orEmpty()}")
                stateMachine?.onPageFinished()
                super.onPageFinished(view, url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    logger.log(
                        "WebView main frame error: ${error?.description?.toString().orEmpty()}",
                        MonitoringLogLevel.WARNING
                    )
                }
                super.onReceivedError(view, request, error)
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    logger.log(
                        "WebView HTTP ${errorResponse?.statusCode} for ${request.url}",
                        MonitoringLogLevel.WARNING
                    )
                }
                super.onReceivedHttpError(view, request, errorResponse)
            }
        }
    }

    private fun updateHeader(state: BookingState, message: String) {
        binding.titleText.text = "Booking: ${state.name}"
        binding.stateText.text = message
        binding.detailsText.text = binding.bookingWebView.url ?: request.routeUrl
    }

    private fun onVerificationDetected() {
        sendTelegram("TrainChecker: Verification page detected for ${request.routeName}. WebView is waiting.")
    }

    private fun onBookingDone(message: String) {
        resultBroadcastSent = true
        sendTelegram(
            "TrainChecker: WebView booking finished for ${request.routeName}.\n" +
                "$message\nCheck cart/payment on pass.rw.by."
        )
        broadcastResult(true, message, BookingState.DONE)
    }

    private fun onBookingError(state: BookingState, message: String) {
        resultBroadcastSent = true
        sendTelegram("TrainChecker: WebView booking failed for ${request.routeName} at $state.\n$message")
        broadcastResult(false, message, state)
    }

    private fun sendTelegram(message: String) {
        lifecycleScope.launch {
            val sent = TelegramNotifier.send(request.telegramToken, request.chatId, message)
            if (!sent) {
                logger.log("Telegram status notification was not sent", MonitoringLogLevel.WARNING)
            }
        }
    }

    private fun broadcastResult(success: Boolean, message: String, state: BookingState) {
        resultBroadcastSent = true
        val intent = Intent(ACTION_BOOKING_RESULT).apply {
            putExtra(EXTRA_ROUTE_ID, request.routeId)
            putExtra(EXTRA_SUCCESS, success)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_STATE, state.name)
            putExtra(EXTRA_IS_RENEWAL, request.isRenewal)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    companion object {
        const val ACTION_BOOKING_RESULT = "by.innowise.trainchecker.ACTION_BOOKING_RESULT"
        const val EXTRA_ROUTE_ID = "route_id"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_STATE = "state"
        const val EXTRA_IS_RENEWAL = "is_renewal"
        private const val WAKE_LOCK_TIMEOUT_MS = 15 * 60 * 1000L

        fun createIntent(context: Context, request: BookingRequest): Intent {
            return request.putInto(Intent(context, BookingWebViewActivity::class.java))
        }
    }
}
