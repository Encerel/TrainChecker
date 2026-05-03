package by.innowise.trainchecker

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import by.innowise.trainchecker.databinding.ActivityBookingWebviewBinding

class BookingWebViewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBookingWebviewBinding
    private lateinit var request: BookingRequest
    private lateinit var logger: BookingLogger
    private var stateMachine: BookingStateMachine? = null
    private var resultBroadcastSent = false
    private var lastState: BookingState = BookingState.LOAD_ROUTE
    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_CANCEL_BOOKING) return
            val routeId = intent.getLongExtra(EXTRA_ROUTE_ID, -1L)
            if (routeId != request.routeId) return

            val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "Booking cancelled by service"
            logger.log(message, MonitoringLogLevel.WARNING)
            resultBroadcastSent = true
            stateMachine?.stop()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val parsedRequest = BookingRequest.from(intent)
        if (parsedRequest == null) {
            finish()
            return
        }
        request = parsedRequest
        logger = BookingLogger(
            context = this,
            routeId = request.routeId,
            webViewDebugLogsEnabled = request.webViewDebugLogsEnabled
        )

        binding = ActivityBookingWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLockScreenWindow()
        setupCancelReceiver()
        setupBackHandling()
        setupManualStop()
        setupWebView()
        updateHeader(BookingState.LOAD_ROUTE, "Starting")

        logger.log("WebView booking started for route ${request.routeName} train ${request.primaryTrainNumber}")

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
        if (::logger.isInitialized && !resultBroadcastSent && isFinishing) {
            val message = "Booking activity was closed before completion"
            logger.log(message, MonitoringLogLevel.WARNING)
            broadcastResult(false, message, BookingState.ERROR)
        }
        stateMachine?.stop()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(cancelReceiver)
        if (::logger.isInitialized) {
            logger.close()
        }
        super.onDestroy()
    }

    private fun setupLockScreenWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setupCancelReceiver() {
        LocalBroadcastManager.getInstance(this).registerReceiver(
            cancelReceiver,
            IntentFilter(ACTION_CANCEL_BOOKING)
        )
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

    private fun setupManualStop() {
        binding.buttonStopBooking.setOnClickListener {
            val message = "Booking cancelled manually"
            logger.log(message, MonitoringLogLevel.WARNING)
            resultBroadcastSent = true
            stateMachine?.stop()
            updateHeader(BookingState.ERROR, message)
            broadcastResult(false, message, BookingState.ERROR)
            finish()
        }
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
                logger.technical(
                    message = "WEBVIEW PAGE_STARTED url=${url.orEmpty()}",
                    state = lastState.name,
                    action = "page_started"
                )
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.detailsText.text = url.orEmpty()
                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = 100
                logger.technical(
                    message = "WEBVIEW PAGE_FINISHED url=${url.orEmpty()} title=${view?.title.orEmpty()}",
                    state = lastState.name,
                    action = "page_finished"
                )
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
        lastState = state
        binding.titleText.text = "Booking: ${state.name}"
        binding.stateText.text = message
        binding.detailsText.text = binding.bookingWebView.url ?: request.routeUrl
        broadcastStatus(state, message)
    }

    private fun onVerificationDetected() {
        broadcastStatus(lastState, "Verification page detected")
    }

    private fun onBookingDone(message: String) {
        resultBroadcastSent = true
        broadcastResult(true, message, BookingState.DONE)
    }

    private fun onBookingError(state: BookingState, message: String) {
        resultBroadcastSent = true
        broadcastResult(false, message, state)
    }

    private fun broadcastStatus(state: BookingState, message: String) {
        val intent = Intent(ACTION_BOOKING_STATUS).apply {
            putExtra(EXTRA_ROUTE_ID, request.routeId)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_STATE, state.name)
            putExtra(EXTRA_URL, binding.bookingWebView.url ?: request.routeUrl)
            putExtra(EXTRA_IS_RENEWAL, request.isRenewal)
            putExtra(EXTRA_DRY_RUN, request.dryRun)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastResult(success: Boolean, message: String, state: BookingState) {
        resultBroadcastSent = true
        val intent = Intent(ACTION_BOOKING_RESULT).apply {
            putExtra(EXTRA_ROUTE_ID, request.routeId)
            putExtra(EXTRA_SUCCESS, success)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_STATE, state.name)
            putExtra(EXTRA_IS_RENEWAL, request.isRenewal)
            putExtra(EXTRA_DRY_RUN, request.dryRun)
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
        const val EXTRA_URL = "url"
        const val EXTRA_DRY_RUN = "dry_run"
        const val ACTION_BOOKING_STATUS = "by.innowise.trainchecker.ACTION_BOOKING_STATUS"
        const val ACTION_CANCEL_BOOKING = "by.innowise.trainchecker.ACTION_CANCEL_BOOKING"

        fun createIntent(context: Context, request: BookingRequest): Intent {
            return request.putInto(Intent(context, BookingWebViewActivity::class.java))
        }
    }
}
