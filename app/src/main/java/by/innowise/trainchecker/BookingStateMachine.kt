package by.innowise.trainchecker

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener

enum class BookingState {
    LOAD_ROUTE,
    WAIT_ROUTE,
    SELECT_TRAIN,
    WAIT_PLACES,
    SELECT_CARRIAGE,
    ENTER_PASSENGERS,
    LOGIN_IF_NEEDED,
    RESELECT_AFTER_LOGIN,
    FILL_PASSENGER,
    SUBMIT_ORDER,
    WAIT_SUCCESS,
    DONE,
    ERROR
}

class BookingStateMachine(
    private val webView: WebView,
    private val request: BookingRequest,
    private val logger: BookingLogger,
    private val onStateChanged: (BookingState, String) -> Unit,
    private val onVerification: () -> Unit,
    private val onDone: (String) -> Unit,
    private val onError: (BookingState, String) -> Unit
) {
    private data class StateConfig(
        val timeoutMs: Long,
        val maxRetries: Int
    )

    private val handler = Handler(Looper.getMainLooper())
    private val retryCounts = mutableMapOf<BookingState, Int>()
    private var state = BookingState.LOAD_ROUTE
    private var stateStartedAt = 0L
    private var running = false
    private var waitingForJs = false
    private var verificationWasReported = false
    private var evaluationSequence = 0L

    private val tickRunnable = Runnable { tick() }

    fun start() {
        running = true
        enter(BookingState.LOAD_ROUTE, "Starting WebView booking")
    }

    fun stop() {
        running = false
        waitingForJs = false
        handler.removeCallbacksAndMessages(null)
    }

    fun onPageFinished() {
        if (running && !state.isTerminal()) {
            schedule(700L)
        }
    }

    private fun tick() {
        if (!running || waitingForJs || state.isTerminal()) return

        if (isTimedOut()) {
            handleTimeout()
            return
        }

        when (state) {
            BookingState.LOAD_ROUTE -> loadRoute()
            BookingState.WAIT_ROUTE -> waitForRoute()
            BookingState.SELECT_TRAIN -> selectTrain()
            BookingState.WAIT_PLACES -> waitForPlaces()
            BookingState.SELECT_CARRIAGE -> selectCarriage()
            BookingState.ENTER_PASSENGERS -> enterPassengers()
            BookingState.LOGIN_IF_NEEDED -> loginIfNeeded()
            BookingState.RESELECT_AFTER_LOGIN -> reselectAfterLogin()
            BookingState.FILL_PASSENGER -> fillPassenger()
            BookingState.SUBMIT_ORDER -> submitOrder()
            BookingState.WAIT_SUCCESS -> waitForSuccess()
            BookingState.DONE,
            BookingState.ERROR -> Unit
        }
    }

    private fun loadRoute() {
        logger.log("WEBVIEW LOAD_ROUTE url=${request.routeUrl}")
        webView.loadUrl(request.routeUrl)
        enter(BookingState.WAIT_ROUTE, "Route URL loaded")
    }

    private fun waitForRoute() {
        evaluate("wait_for_route/status", STATUS_SCRIPT) { result ->
            when {
                result.optBoolean("mainRedirect") -> keepWaiting(result.optString("message", "Main page is open"))
                result.optBoolean("placesReady") -> enter(BookingState.SELECT_CARRIAGE, "Places page is already open")
                result.optBoolean("passengerReady") -> enter(BookingState.FILL_PASSENGER, "Passenger form is already open")
                result.optBoolean("routeReady") -> enter(BookingState.SELECT_TRAIN, "Route page DOM is ready")
                else -> keepWaiting(result.optString("message", "Waiting for route page"))
            }
        }
    }

    private fun selectTrain() {
        evaluate("select_train/click_places", selectTrainScript()) { result ->
            when {
                result.optBoolean("mainRedirect") -> {
                    webView.loadUrl(request.routeUrl)
                    enter(BookingState.WAIT_ROUTE, result.optString("message", "Returned to main page"))
                }
                result.optBoolean("loginRequired") -> enter(BookingState.LOGIN_IF_NEEDED, "Login form detected")
                result.optBoolean("placesReady") -> enter(BookingState.SELECT_CARRIAGE, "Places page is open")
                result.optBoolean("ok") -> enter(BookingState.WAIT_PLACES, result.optString("message", "Train selected"))
                else -> keepWaiting(result.optString("message", "Target train is not ready yet"))
            }
        }
    }

    private fun waitForPlaces() {
        evaluate("wait_for_places/status", STATUS_SCRIPT) { result ->
            when {
                result.optBoolean("mainRedirect") -> {
                    webView.loadUrl(request.routeUrl)
                    enter(BookingState.WAIT_ROUTE, result.optString("message", "Returned to main page"))
                }
                result.optBoolean("loginRequired") -> enter(BookingState.LOGIN_IF_NEEDED, "Login form detected")
                result.optBoolean("passengerReady") -> enter(BookingState.FILL_PASSENGER, "Passenger form is open")
                result.optBoolean("placesReady") -> enter(BookingState.SELECT_CARRIAGE, "Places page DOM is ready")
                else -> keepWaiting(result.optString("message", "Waiting for places page"))
            }
        }
    }

    private fun selectCarriage() {
        evaluate("select_carriage/click_carriage", selectCarriageScript()) { result ->
            when {
                result.optBoolean("mainRedirect") -> {
                    webView.loadUrl(request.routeUrl)
                    enter(BookingState.WAIT_ROUTE, result.optString("message", "Returned to main page"))
                }
                result.optBoolean("loginRequired") -> enter(BookingState.LOGIN_IF_NEEDED, "Login form detected")
                result.optBoolean("passengerReady") -> enter(BookingState.FILL_PASSENGER, "Passenger form is open")
                result.optBoolean("ok") -> enter(
                    BookingState.ENTER_PASSENGERS,
                    result.optString("message", "Carriage selected")
                )
                else -> keepWaiting(result.optString("message", "Target carriage is not ready yet"))
            }
        }
    }

    private fun enterPassengers() {
        evaluate("enter_passengers/click_or_submit", enterPassengersScript()) { result ->
            when {
                result.optBoolean("mainRedirect") -> {
                    fail(
                        BookingState.ENTER_PASSENGERS,
                        result.optString("message", "Unexpected redirect to main page")
                    )
                }
                result.optBoolean("loginRequired") -> enter(BookingState.LOGIN_IF_NEEDED, "Login form detected")
                result.optBoolean("passengerReady") -> enter(BookingState.FILL_PASSENGER, "Passenger form is open")
                result.optBoolean("ok") -> enter(
                    BookingState.FILL_PASSENGER,
                    result.optString("message", "Passenger step requested")
                )
                else -> keepWaiting(result.optString("message", "Passenger button is not ready yet"))
            }
        }
    }

    private fun loginIfNeeded() {
        if (request.rwLogin.isBlank() || request.rwPassword.isBlank()) {
            fail(BookingState.LOGIN_IF_NEEDED, "RW login or password is empty")
            return
        }

        evaluate("login_if_needed/submit_login", loginScript()) { result ->
            when {
                result.optBoolean("ok") -> enter(
                    BookingState.RESELECT_AFTER_LOGIN,
                    result.optString("message", "Login submitted")
                )
                else -> keepWaiting(result.optString("message", "Waiting for login form"))
            }
        }
    }

    private fun reselectAfterLogin() {
        val ageMs = System.currentTimeMillis() - stateStartedAt
        if (ageMs < 3_000L) {
            logger.log("WAIT[$state] ageMs=$ageMs message=Waiting for login redirect url=${webView.url.orEmpty()}")
            keepWaiting("Waiting for login redirect")
            return
        }

        evaluate("reselect_after_login/status", STATUS_SCRIPT) { result ->
            when {
                result.optBoolean("mainRedirect") -> {
                    webView.loadUrl(request.routeUrl)
                    enter(BookingState.WAIT_ROUTE, result.optString("message", "Main page after login, reloading route"))
                }
                result.optBoolean("passengerReady") -> enter(BookingState.FILL_PASSENGER, "Passenger form is open after login")
                result.optBoolean("placesReady") -> enter(BookingState.SELECT_CARRIAGE, "Places page is open after login")
                result.optBoolean("routeReady") -> enter(BookingState.SELECT_TRAIN, "Route page is open after login")
                result.optBoolean("loginRequired") && ageMs < 10_000L -> keepWaiting("Waiting for login to finish")
                result.optBoolean("loginRequired") -> fail(BookingState.LOGIN_IF_NEEDED, "Login form is still visible after submit")
                else -> {
                    webView.loadUrl(request.routeUrl)
                    enter(BookingState.WAIT_ROUTE, "Reloading route after login")
                }
            }
        }
    }

    private fun fillPassenger() {
        evaluate("fill_passenger/fill_form", fillPassengerScript()) { result ->
            when {
                result.optBoolean("mainRedirect") -> {
                    fail(
                        BookingState.FILL_PASSENGER,
                        result.optString("message", "Unexpected redirect to main page after passenger transition")
                    )
                }
                result.optBoolean("loginRequired") -> enter(BookingState.LOGIN_IF_NEEDED, "Login form detected")
                result.optBoolean("ok") -> enter(
                    BookingState.SUBMIT_ORDER,
                    result.optString("message", "Passenger data filled")
                )
                else -> keepWaiting(result.optString("message", "Waiting for passenger form"))
            }
        }
    }

    private fun submitOrder() {
        evaluate("submit_order/click_final", submitOrderScript()) { result ->
            when {
                result.optBoolean("dryRun") -> complete("Dry run finished before final submit")
                result.optBoolean("ok") -> enter(BookingState.WAIT_SUCCESS, "Order submit clicked")
                else -> keepWaiting(result.optString("message", "Waiting for final submit button"))
            }
        }
    }

    private fun waitForSuccess() {
        evaluate("wait_success/status", successScript()) { result ->
            when {
                result.optBoolean("mainRedirect") -> fail(
                    BookingState.WAIT_SUCCESS,
                    result.optString("message", "Unexpected redirect to main page after submit")
                )
                result.optBoolean("success") -> complete(
                    result.optString("message", "Order was moved to cart or payment")
                )
                result.optBoolean("hasErrors") -> fail(
                    BookingState.WAIT_SUCCESS,
                    result.optString("message", "Passenger form validation error")
                )
                result.optBoolean("loginRequired") -> enter(BookingState.LOGIN_IF_NEEDED, "Login form detected")
                else -> keepWaiting(result.optString("message", "Waiting for order result"))
            }
        }
    }

    private fun evaluate(action: String, scriptBody: String, callback: (JSONObject) -> Unit) {
        waitingForJs = true
        webView.evaluateJavascript(wrapScript(scriptBody)) { raw ->
            waitingForJs = false
            if (!running) return@evaluateJavascript

            val result = parseResult(raw)
            logEvaluation(action, result)
            logScrollRequest(action, result)
            if (result.optBoolean("verification")) {
                handleVerification()
                return@evaluateJavascript
            }

            verificationWasReported = false
            callback(result)
        }
    }

    private fun logScrollRequest(action: String, result: JSONObject) {
        val actionTaken = result.optString("actionTaken")
        if (!actionTaken.startsWith("scroll_to_")) return

        val lastScroll = result
            .optJSONObject("diagnostics")
            ?.optJSONObject("lastScroll")
            ?.toString()
            ?.take(LOG_MESSAGE_VALUE_LIMIT)
            .orEmpty()
        logger.log(
            "SCROLL_REQUEST[$state] action=$action actionTaken=$actionTaken " +
                "webViewScrollY=${webView.scrollY} jsLastScroll=$lastScroll"
        )
    }

    private fun handleVerification() {
        stateStartedAt = System.currentTimeMillis()
        if (!verificationWasReported) {
            verificationWasReported = true
            logger.log("Verification page detected, waiting for WebView to pass it")
            onVerification()
        }
        onStateChanged(state, "Verification page is active")
        schedule(2_000L)
    }

    private fun keepWaiting(message: String) {
        logger.log("WAIT[$state] message=${message.take(LOG_MESSAGE_VALUE_LIMIT)} url=${webView.url.orEmpty()}")
        onStateChanged(state, message)
        schedule()
    }

    private fun complete(message: String) {
        running = false
        state = BookingState.DONE
        logger.log("WebView booking success: $message", MonitoringLogLevel.SUCCESS)
        onStateChanged(BookingState.DONE, message)
        onDone(message)
    }

    private fun fail(failedState: BookingState, message: String) {
        running = false
        state = BookingState.ERROR
        val currentUrl = webView.url.orEmpty()
        val fullMessage = if (currentUrl.isBlank()) {
            "$failedState: $message"
        } else {
            "$failedState: $message. URL: $currentUrl"
        }
        logger.log("WebView booking error: $fullMessage", MonitoringLogLevel.ERROR)
        onStateChanged(BookingState.ERROR, fullMessage)
        onError(failedState, fullMessage)
    }

    private fun handleTimeout() {
        val currentState = state
        val config = configFor(currentState)
        val retry = retryCounts.getOrDefault(currentState, 0)
        logger.log(
            "TIMEOUT[$currentState] retry=$retry/${config.maxRetries} " +
                "elapsedMs=${System.currentTimeMillis() - stateStartedAt} url=${webView.url.orEmpty()}",
            MonitoringLogLevel.WARNING
        )
        if (retry >= config.maxRetries) {
            fail(currentState, "Timeout after ${config.timeoutMs / 1000}s")
            return
        }

        retryCounts[currentState] = retry + 1
        logger.log("Retry ${retry + 1}/${config.maxRetries} for $currentState")
        when (currentState) {
            BookingState.WAIT_ROUTE,
            BookingState.SELECT_TRAIN -> {
                webView.loadUrl(request.routeUrl)
                enter(BookingState.WAIT_ROUTE, "Retrying route load")
            }
            BookingState.WAIT_PLACES -> enter(BookingState.SELECT_TRAIN, "Retrying train selection")
            BookingState.SELECT_CARRIAGE,
            BookingState.ENTER_PASSENGERS -> enter(BookingState.WAIT_PLACES, "Retrying places page")
            BookingState.LOGIN_IF_NEEDED,
            BookingState.RESELECT_AFTER_LOGIN -> {
                webView.loadUrl(request.routeUrl)
                enter(BookingState.WAIT_ROUTE, "Retrying after login")
            }
            BookingState.FILL_PASSENGER -> enter(BookingState.ENTER_PASSENGERS, "Retrying passenger step")
            BookingState.LOAD_ROUTE,
            BookingState.SUBMIT_ORDER,
            BookingState.WAIT_SUCCESS,
            BookingState.DONE,
            BookingState.ERROR -> fail(currentState, "Retry is not allowed for this state")
        }
    }

    private fun enter(newState: BookingState, reason: String) {
        state = newState
        stateStartedAt = System.currentTimeMillis()
        waitingForJs = false
        onStateChanged(newState, reason)
        logger.log("STATE[$newState] reason=${reason.take(LOG_MESSAGE_VALUE_LIMIT)} url=${webView.url.orEmpty()}")
        schedule(250L)
    }

    private fun schedule(delayMs: Long = 1_000L) {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, delayMs)
    }

    private fun isTimedOut(): Boolean {
        return System.currentTimeMillis() - stateStartedAt > configFor(state).timeoutMs
    }

    private fun configFor(state: BookingState): StateConfig {
        return when (state) {
            BookingState.LOAD_ROUTE -> StateConfig(5_000L, 0)
            BookingState.WAIT_ROUTE -> StateConfig(60_000L, 2)
            BookingState.SELECT_TRAIN -> StateConfig(35_000L, 2)
            BookingState.WAIT_PLACES -> StateConfig(45_000L, 2)
            BookingState.SELECT_CARRIAGE -> StateConfig(35_000L, 2)
            BookingState.ENTER_PASSENGERS -> StateConfig(35_000L, 2)
            BookingState.LOGIN_IF_NEEDED -> StateConfig(35_000L, 2)
            BookingState.RESELECT_AFTER_LOGIN -> StateConfig(20_000L, 2)
            BookingState.FILL_PASSENGER -> StateConfig(35_000L, 1)
            BookingState.SUBMIT_ORDER -> StateConfig(20_000L, 0)
            BookingState.WAIT_SUCCESS -> StateConfig(60_000L, 0)
            BookingState.DONE,
            BookingState.ERROR -> StateConfig(Long.MAX_VALUE, 0)
        }
    }

    private fun logEvaluation(action: String, result: JSONObject) {
        evaluationSequence += 1
        val message = buildString {
            append("JS#")
            append(evaluationSequence)
            append("[")
            append(state)
            append("/")
            append(action)
            append("] ")
            append(sanitizeForLog(result.toString()).take(LOG_ENTRY_VALUE_LIMIT))
        }
        logger.log(message)
    }

    private fun sanitizeForLog(value: String): String {
        var sanitized = value
        if (request.rwPassword.isNotBlank()) {
            sanitized = sanitized.replace(request.rwPassword, "***")
        }
        return sanitized
    }

    private fun parseResult(raw: String?): JSONObject {
        if (raw.isNullOrBlank() || raw == "null") return JSONObject()
        return try {
            when (val value = JSONTokener(raw).nextValue()) {
                is JSONObject -> value
                is String -> JSONObject(value)
                else -> JSONObject()
            }
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun selectTrainScript(): String {
        return """
            var wanted = ${jsArray(request.trainNumbers)};
            var wantedNormalized = wanted.map(norm).filter(Boolean);
            if (hasLoginForm()) return status({ loginRequired: true, message: "Login form detected" });
            if (isPlacesPage()) return status({ placesReady: true, message: "Places page is already open" });
            var rows = Array.prototype.slice.call(document.querySelectorAll(".sch-table__row[data-train-number], .sch-table__row-wrap[data-train-number]"));
            if (!rows.length) {
                rows = Array.prototype.slice.call(document.querySelectorAll(".sch-table__row, .sch-table__row-wrap"))
                    .filter(function(row) {
                        return row.querySelector("form.js-sch-item-form[action*='/order/places'], form[action*='/order/places'], .btn-index");
                    });
            }
            var row = rows.find(function(item) {
                var trainNumber = item.getAttribute("data-train-number") || text(item.querySelector(".train-number"));
                var normalized = norm(trainNumber);
                return wantedNormalized.length === 0 || wantedNormalized.indexOf(normalized) !== -1;
            });
            if (!row) {
                return status({ ok: false, message: "Target train was not found in " + rows.length + " rows" });
            }
            var form = row.querySelector("form.js-sch-item-form[action*='/order/places'], form[action*='/order/places']");
            var button = row.querySelector(".btn-index") || (form && form.querySelector("button, input[type='submit'], a"));
            var trainNumber = row.getAttribute("data-train-number") || text(row.querySelector(".train-number"));
            if (button) {
                clickElement(button);
                return status({
                    ok: true,
                    actionTaken: "click_train_button",
                    trainNumber: trainNumber,
                    formAction: formAction(form),
                    button: describeElement(button),
                    message: "Clicked train " + trainNumber
                });
            }
            if (form) {
                form.submit();
                return status({
                    ok: true,
                    actionTaken: "submit_train_form",
                    trainNumber: trainNumber,
                    formAction: formAction(form),
                    message: "Submitted train form"
                });
            }
            return status({ ok: false, message: "Train form/button was not found" });
        """.trimIndent()
    }

    private fun selectCarriageScript(): String {
        return """
            var classes = ${jsArray(request.serviceClasses)};
            var normalizedClasses = classes.map(norm).filter(Boolean);
            if (hasLoginForm()) return status({ loginRequired: true, message: "Login form detected" });
            if (isPassengerPage()) return status({ passengerReady: true, message: "Passenger page is already open" });
            var links = Array.prototype.slice.call(document.querySelectorAll(".pl-accord__acc-link"));
            var target = links.find(function(link) {
                var value = norm(text(link));
                return normalizedClasses.some(function(serviceClass) {
                    return value.indexOf(serviceClass) !== -1;
                });
            });
            if (!target) {
                var found = links.map(function(link) { return text(link); }).filter(Boolean).slice(0, 5).join("; ");
                return status({
                    ok: false,
                    wantedClasses: classes,
                    foundCarriages: found,
                    message: "Matching carriage not found. Found: " + found
                });
            }
            if (!isVisible(target) || !isInViewport(target)) {
                var scroll = scrollElementIntoActionView(target);
                return status({
                    ok: false,
                    actionTaken: "scroll_to_carriage",
                    carriageText: text(target),
                    target: describeElement(target),
                    scroll: scroll,
                    visibleCarriages: visibleCarriagesSummary(),
                    message: "Scrolled to carriage " + text(target)
                });
            }
            var targetText = text(target);
            var now = Date.now();
            var targetExpanded = carriageLooksExpanded(target);
            var clickedThisTarget =
                window.__trainCheckerSelectedCarriage === targetText &&
                now - (window.__trainCheckerSelectedCarriageAt || 0) < 7000;
            var readyForm = (targetExpanded || clickedThisTarget) ? findPassengerTransitionForm() : null;
            if (readyForm) {
                return status({
                    ok: true,
                    actionTaken: "carriage_already_selected",
                    carriageText: targetText,
                    targetExpanded: targetExpanded,
                    clickedThisTarget: clickedThisTarget,
                    passengerTransition: summarizePassengerTransition(),
                    message: "Passenger transition form is ready: " + formAction(readyForm)
                });
            }

            var alreadyClicked =
                window.__trainCheckerSelectedCarriage === targetText &&
                now - (window.__trainCheckerSelectedCarriageAt || 0) < 1800;
            if (alreadyClicked) {
                return status({
                    ok: false,
                    actionTaken: "wait_after_carriage_click",
                    carriageText: targetText,
                    targetExpanded: targetExpanded,
                    message: "Waiting for passenger transition form after clicking " + targetText
                });
            }
            window.__trainCheckerSelectedCarriage = targetText;
            window.__trainCheckerSelectedCarriageAt = now;
            clickElement(target);
            return status({
                ok: false,
                actionTaken: "click_carriage",
                carriageText: targetText,
                targetExpandedBeforeClick: targetExpanded,
                target: describeElement(target),
                message: "Clicked carriage " + targetText + ", waiting for form"
            });
        """.trimIndent()
    }

    private fun enterPassengersScript(): String {
        return """
            if (isUnexpectedMainPage()) return status({ mainRedirect: true, message: "Unexpected redirect to main page" });
            if (hasLoginForm()) return status({ loginRequired: true, message: "Login form detected" });
            if (isPassengerPage()) return status({ passengerReady: true, message: "Passenger form is open" });
            var form = findPassengerTransitionForm();
            if (!form) {
                return status({ ok: false, message: "Passenger transition form not ready. " + visibleFormsSummary() });
            }
            var action = formAction(form);
            var button = findPassengerTransitionButton(form);
            var href = buttonHref(button);
            var scrollTarget = button || form;
            if (!button) {
                var formScroll = scrollElementIntoActionView(form);
                return status({
                    ok: false,
                    actionTaken: "scroll_to_passenger_transition_form",
                    formAction: action,
                    scroll: formScroll,
                    passengerTransition: summarizePassengerTransition(),
                    message: "Passenger transition button is not ready; scrolled to form"
                });
            }
            if (!isVisible(button) || !isInViewport(button)) {
                var buttonScroll = scrollElementIntoActionView(scrollTarget);
                return status({
                    ok: false,
                    actionTaken: "scroll_to_passenger_transition_button",
                    formAction: action,
                    href: href,
                    button: describeElement(button),
                    scroll: buttonScroll,
                    passengerTransition: summarizePassengerTransition(),
                    message: "Passenger transition button is not visible yet; scrolled to it"
                });
            }
            if (href.indexOf("/order/passengers") !== -1 || href.indexOf("passenger") !== -1) {
                clickElement(button);
                return status({
                    ok: true,
                    actionTaken: "click_passenger_transition_link",
                    formAction: action,
                    href: href,
                    button: describeElement(button),
                    message: "Clicked passenger transition link: " + href
                });
            }
            if (button && (!href || href.indexOf("javascript") !== -1 || button.getAttribute("onclick"))) {
                clickElement(button);
                return status({
                    ok: true,
                    actionTaken: "click_js_passenger_transition_button",
                    formAction: action,
                    href: href,
                    button: describeElement(button),
                    message: "Clicked JS passenger transition button action=" + action + ", href=" + href
                });
            }
            if (button) {
                clickElement(button);
                return status({
                    ok: true,
                    actionTaken: "click_passenger_transition_button",
                    formAction: action,
                    href: href,
                    button: describeElement(button),
                    message: "Clicked passenger transition button action=" + action + ", href=" + href
                });
            }
            return status({
                ok: false,
                formAction: action,
                href: href,
                button: describeElement(button),
                message: "Passenger button is not ready. action=" + action + ", href=" + href
            });
        """.trimIndent()
    }

    private fun loginScript(): String {
        return """
            var form = document.querySelector("form#form-auth");
            if (!form) return status({ ok: false, message: "Auth form not found" });
            var login = form.querySelector("input[name='login']");
            var password = form.querySelector("input[name='password']");
            if (!login || !password) return status({ ok: false, message: "Auth inputs not found" });
            setValue(login, ${jsString(request.rwLogin)});
            setValue(password, ${jsString(request.rwPassword)});
            var submit = form.querySelector("input[name='dologin'], button[type='submit'], input[type='submit']");
            if (submit) {
                clickElement(submit);
            } else {
                form.submit();
            }
            return status({ ok: true, message: "Login submitted" });
        """.trimIndent()
    }

    private fun fillPassengerScript(): String {
        return """
            if (isUnexpectedMainPage()) return status({ mainRedirect: true, message: "Unexpected redirect to main page" });
            if (hasLoginForm()) return status({ loginRequired: true, message: "Login form detected" });
            var form = document.querySelector("form#contact-info_form") || document.querySelector("form[action*='/order/passengers']");
            var lastName = document.querySelector("[name='last_name_1']");
            var firstName = document.querySelector("[name='first_name_1']");
            var documentNumber = document.querySelector("[name='document_number_1']");
            if (!form || !lastName || !firstName || !documentNumber) {
                return status({
                    ok: false,
                    missingPassengerForm: !form,
                    missingLastName: !lastName,
                    missingFirstName: !firstName,
                    missingDocumentNumber: !documentNumber,
                    message: "Passenger form fields not ready. " + visibleFormsSummary()
                });
            }
            setValue(lastName, ${jsString(request.passengerLastName)});
            setValue(firstName, ${jsString(request.passengerFirstName)});
            setValue(document.querySelector("[name='middle_name_1']"), ${jsString(request.passengerMiddleName)});
            setDocumentType();
            setValue(documentNumber, ${jsString(request.passengerDocumentNumber)});
            setAgreement();
            return status({ ok: true, message: "Passenger form filled" });
        """.trimIndent()
    }

    private fun submitOrderScript(): String {
        return """
            if (${request.dryRun}) return status({ dryRun: true, message: "Dry run stopped before submit" });
            var form = document.querySelector("form#contact-info_form") || document.querySelector("form[action*='/order/passengers']");
            if (!form) return status({ ok: false, message: "Final form not found" });
            var button = form.querySelector("button[type='submit'], input[type='submit']");
            if (button) {
                clickElement(button);
            } else {
                form.submit();
            }
            return status({ ok: true, message: "Final submit clicked" });
        """.trimIndent()
    }

    private fun successScript(): String {
        return """
            if (hasLoginForm()) return status({ loginRequired: true, message: "Login form detected" });
            var lowerUrl = location.href.toLowerCase();
            var body = text(document.body).toLowerCase();
            var success =
                lowerUrl.indexOf("/cart") !== -1 ||
                lowerUrl.indexOf("/basket") !== -1 ||
                lowerUrl.indexOf("/order/payment") !== -1 ||
                lowerUrl.indexOf("added_to_basket=1") !== -1 ||
                body.indexOf("\u043a\u043e\u0440\u0437\u0438\u043d") !== -1 ||
                body.indexOf("\u0437\u0430\u043a\u0430\u0437 \u043e\u0444\u043e\u0440\u043c") !== -1;
            if (success) return status({ success: true, message: "Order page/cart detected" });
            var errors = Array.prototype.slice.call(document.querySelectorAll(".error, .alert-danger, .message-error, .help-block, .invalid-feedback"))
                .map(function(item) { return text(item); })
                .filter(Boolean)
                .filter(function(value) { return value.indexOf("+375") === -1; });
            if (errors.length) {
                return status({ hasErrors: true, message: errors.slice(0, 3).join("; ") });
            }
            return status({ success: false, message: "Current URL: " + location.href });
        """.trimIndent()
    }

    private fun wrapScript(body: String): String {
        return """
            (function() {
                try {
                    $JS_HELPERS
                    $body
                } catch (error) {
                    var diagnostics = null;
                    try {
                        diagnostics = typeof collectDiagnostics === "function" ? collectDiagnostics() : null;
                    } catch (diagnosticError) {
                        diagnostics = { diagnosticError: String(diagnosticError && diagnosticError.message ? diagnosticError.message : diagnosticError) };
                    }
                    return {
                        ok: false,
                        message: String(error && error.message ? error.message : error),
                        url: location.href,
                        diagnostics: diagnostics
                    };
                }
            })();
        """.trimIndent()
    }

    private fun jsString(value: String): String = JSONObject.quote(value)

    private fun jsArray(values: List<String>): String {
        return values.joinToString(prefix = "[", postfix = "]") { jsString(it) }
    }

    private fun BookingState.isTerminal(): Boolean {
        return this == BookingState.DONE || this == BookingState.ERROR
    }

    private companion object {
        private const val LOG_ENTRY_VALUE_LIMIT = 7000
        private const val LOG_MESSAGE_VALUE_LIMIT = 700

        private val STATUS_SCRIPT = """
            return status({
                routeReady: isRoutePage(),
                placesReady: isPlacesPage(),
                passengerReady: isPassengerPage(),
                loginRequired: hasLoginForm(),
                mainRedirect: isUnexpectedMainPage(),
                message: "URL: " + location.href
            });
        """.trimIndent()

        private val JS_HELPERS = """
            function text(element) {
                return ((element && (element.innerText || element.textContent)) || "").trim();
            }
            function norm(value) {
                return String(value || "").replace(/\s+/g, "").toUpperCase();
            }
            function hasVerification() {
                var body = text(document.body).toLowerCase();
                var url = location.href.toLowerCase();
                return url.indexOf("verification") !== -1 ||
                    body.indexOf("verification") !== -1 ||
                    body.indexOf("verify") !== -1 ||
                    body.indexOf("\u043f\u0440\u043e\u0432\u0435\u0440") !== -1;
            }
            function isVisible(element) {
                if (!element) return false;
                var current = element;
                while (current && current !== document.documentElement) {
                    if (current.hidden || current.getAttribute("aria-hidden") === "true") return false;
                    var style = window.getComputedStyle(current);
                    if (style.display === "none" || style.visibility === "hidden" || style.opacity === "0") {
                        return false;
                    }
                    current = current.parentElement;
                }
                var rect = element.getBoundingClientRect();
                return element.getClientRects().length > 0 && rect.width > 0 && rect.height > 0;
            }
            function isInViewport(element) {
                if (!element || !isVisible(element)) return false;
                var rect = element.getBoundingClientRect();
                var viewportHeight = window.innerHeight || document.documentElement.clientHeight || 0;
                var viewportWidth = window.innerWidth || document.documentElement.clientWidth || 0;
                return rect.bottom > 80 &&
                    rect.right > 0 &&
                    rect.top < viewportHeight - 80 &&
                    rect.left < viewportWidth;
            }
            function rectSummary(element) {
                if (!element || !element.getBoundingClientRect) return null;
                var rect = element.getBoundingClientRect();
                return {
                    top: Math.round(rect.top),
                    bottom: Math.round(rect.bottom),
                    height: Math.round(rect.height),
                    width: Math.round(rect.width)
                };
            }
            function visibleCarriagesSummary() {
                return Array.prototype.slice.call(document.querySelectorAll(".pl-accord__acc-link"))
                    .filter(function(link) { return isVisible(link) && isInViewport(link); })
                    .slice(0, 8)
                    .map(function(link) {
                        return {
                            text: trimLogValue(text(link), 80),
                            rect: rectSummary(link)
                        };
                    });
            }
            function carriageLooksExpanded(link) {
                if (!link) return false;
                var className = String(link.className || "");
                var aria = String(link.getAttribute("aria-expanded") || "").toLowerCase();
                if (aria === "true") return true;
                if (aria === "false") return false;
                if (/(^|\s)collapsed(\s|$)/.test(className)) return false;
                if (/(^|\s)(active|open|selected)(\s|$)/.test(className)) return true;

                var current = link.parentElement;
                for (var index = 0; current && index < 5; index += 1) {
                    var visibleTransition = Array.prototype.slice.call(current.querySelectorAll("form.carriage-cost__form"))
                        .find(function(form) { return isVisible(form); });
                    if (visibleTransition) return true;
                    current = current.parentElement;
                }
                return false;
            }
            function nearestScrollableParent(element) {
                var current = element ? element.parentElement : null;
                while (current && current !== document.body && current !== document.documentElement) {
                    var style = window.getComputedStyle(current);
                    var canScrollY = /(auto|scroll)/.test(style.overflowY) && current.scrollHeight > current.clientHeight + 4;
                    if (canScrollY) return current;
                    current = current.parentElement;
                }
                return document.scrollingElement || document.documentElement || document.body;
            }
            function applyMediumScroll(scroller, delta) {
                if (!scroller || !delta || Math.abs(delta) < 1) return 0;
                if (
                    scroller === document.scrollingElement ||
                    scroller === document.documentElement ||
                    scroller === document.body
                ) {
                    try {
                        window.scrollBy({ top: delta, left: 0, behavior: "smooth" });
                    } catch (ignored) {
                        scroller.scrollTop += delta;
                    }
                    return delta;
                }
                try {
                    scroller.scrollBy({ top: delta, left: 0, behavior: "smooth" });
                } catch (ignored) {
                    scroller.scrollTop += delta;
                }
                return delta;
            }
            function scrollElementIntoActionView(element) {
                if (!element) return null;
                var target = element;
                var before = getScrollSnapshot();
                var viewportHeight = window.innerHeight || document.documentElement.clientHeight || 700;
                var safeTop = 105;
                var safeBottom = Math.max(safeTop + 140, viewportHeight - 125);
                var desiredCenter = Math.round((safeTop + safeBottom) / 2);
                var rect = target.getBoundingClientRect ? target.getBoundingClientRect() : null;
                var targetRectBefore = rectSummary(target);
                var requestedDelta = 0;
                var appliedDelta = 0;
                var reason = "already_visible";
                var targetVisibleBefore = isInViewport(target);

                if (rect) {
                    var targetCenter = Math.round(rect.top + rect.height / 2);
                    var needsMove = !targetVisibleBefore || rect.top < safeTop || rect.bottom > safeBottom;
                    if (needsMove) {
                        requestedDelta = targetCenter - desiredCenter;
                        if (Math.abs(requestedDelta) < 30 && rect.bottom > safeBottom) {
                            requestedDelta = rect.bottom - safeBottom;
                        } else if (Math.abs(requestedDelta) < 30 && rect.top < safeTop) {
                            requestedDelta = rect.top - safeTop;
                        }
                        var direction = requestedDelta >= 0 ? 1 : -1;
                        var maxStep = Math.min(170, Math.max(105, Math.floor(viewportHeight * 0.2)));
                        var minStep = 55;
                        var step = Math.min(Math.max(Math.abs(requestedDelta) * 0.35, minStep), maxStep);
                        appliedDelta = direction * Math.round(step);
                        reason = appliedDelta > 0 ? "target_below" : "target_above";
                        applyMediumScroll(nearestScrollableParent(target), appliedDelta);
                    }
                }

                window.dispatchEvent(new Event("scroll", { bubbles: true }));
                document.dispatchEvent(new Event("scroll", { bubbles: true }));
                window.__trainCheckerLastScroll = {
                    before: before,
                    after: getScrollSnapshot(),
                    target: describeElement(target),
                    targetRectBefore: targetRectBefore,
                    targetRectAfter: rectSummary(target),
                    requestedDelta: Math.round(requestedDelta),
                    appliedDelta: appliedDelta,
                    reason: reason,
                    targetVisibleBefore: targetVisibleBefore,
                    targetVisibleAfter: isInViewport(target),
                    visibleCarriages: visibleCarriagesSummary()
                };
                return window.__trainCheckerLastScroll;
            }
            function getScrollSnapshot() {
                var scrollingElement = document.scrollingElement || document.documentElement || document.body;
                return {
                    windowX: window.pageXOffset || document.documentElement.scrollLeft || document.body.scrollLeft || 0,
                    windowY: window.pageYOffset || document.documentElement.scrollTop || document.body.scrollTop || 0,
                    docTop: scrollingElement ? scrollingElement.scrollTop : 0,
                    docHeight: scrollingElement ? scrollingElement.scrollHeight : 0,
                    viewportHeight: window.innerHeight || document.documentElement.clientHeight || 0
                };
            }
            function hasLoginForm() {
                var form = document.querySelector("form#form-auth");
                if (!form) return false;
                var login = form.querySelector("input[name='login']");
                var password = form.querySelector("input[name='password']");
                if (!login || !password) return false;
                if (isVisible(form) || isVisible(login) || isVisible(password)) return true;

                var lowerUrl = location.href.toLowerCase();
                var lowerBody = text(document.body).toLowerCase();
                var pageLooksLikeAuthGate =
                    lowerUrl.indexOf("/login") !== -1 ||
                    lowerUrl.indexOf("authpopup=true") !== -1 ||
                    lowerBody.indexOf("\u0432\u043e\u0439\u0434\u0438\u0442\u0435") !== -1 ||
                    lowerBody.indexOf("\u0430\u0432\u0442\u043e\u0440\u0438\u0437") !== -1;

                return pageLooksLikeAuthGate && !isRoutePage() && !isPlacesPage() && !isPassengerPage();
            }
            function isRoutePage() {
                return !!document.querySelector(".sch-table__row[data-train-number], .sch-table__row-wrap[data-train-number], form.js-sch-item-form[action*='/order/places']");
            }
            function isPlacesPage() {
                return location.href.indexOf("/order/places") !== -1 ||
                    !!document.querySelector(".pl-accord__acc-link, form.carriage-cost__form");
            }
            function isPassengerPage() {
                return location.href.indexOf("/order/passengers") !== -1 ||
                    !!document.querySelector("[name='last_name_1'], [name='first_name_1'], form#contact-info_form");
            }
            function isUnexpectedMainPage() {
                var path = location.pathname.replace(/\/+$/, "");
                return (path === "/ru" || path === "") &&
                    !isRoutePage() &&
                    !isPlacesPage() &&
                    !isPassengerPage();
            }
            function formAction(form) {
                return String((form && form.getAttribute("action")) || "");
            }
            function buttonHref(button) {
                return String((button && button.getAttribute("href")) || "");
            }
            function controlLooksVisible(control) {
                return isVisible(control) ||
                    !!(control && control.offsetParent) ||
                    !!(control && control.getClientRects && control.getClientRects().length);
            }
            function findPassengerTransitionButton(form) {
                if (!form) return null;
                var buttons = Array.prototype.slice.call(form.querySelectorAll(
                    "a.btn-index-2, button.btn-index-2, input.btn-index-2, button[type='submit'], input[type='submit'], a[href*='passenger'], a[href*='order']"
                ));
                return buttons.find(controlLooksVisible) || buttons[0] || null;
            }
            function formCanTransitionToPassengers(form) {
                var action = formAction(form).toLowerCase();
                if (action.indexOf("/order/passengers") !== -1 || action.indexOf("passenger") !== -1) {
                    return true;
                }
                var button = findPassengerTransitionButton(form);
                var href = buttonHref(button).toLowerCase();
                return !!button && (
                    href.indexOf("/order/passengers") !== -1 ||
                    href.indexOf("passenger") !== -1 ||
                    !href ||
                    href.indexOf("javascript") !== -1 ||
                    !!button.getAttribute("onclick")
                );
            }
            function transitionFormLooksVisible(form) {
                if (!form) return false;
                if (isVisible(form)) return true;
                return Array.prototype.slice.call(form.querySelectorAll("a, button, input"))
                    .some(function(control) { return isVisible(control); });
            }
            function findPassengerTransitionForm() {
                var forms = Array.prototype.slice.call(document.querySelectorAll(
                    "form.carriage-cost__form, form[action*='/order/passengers'], form[action*='passenger']"
                ));
                var visibleForms = forms.filter(function(form) {
                    return transitionFormLooksVisible(form);
                });
                var passengerActionForm = visibleForms.find(function(form) {
                    var action = formAction(form).toLowerCase();
                    return action.indexOf("/order/passengers") !== -1 || action.indexOf("passenger") !== -1;
                });
                if (passengerActionForm) return passengerActionForm;

                return visibleForms.find(function(form) {
                    return formCanTransitionToPassengers(form);
                }) || null;
            }
            function visibleFormsSummary() {
                var forms = Array.prototype.slice.call(document.querySelectorAll("form"));
                return forms
                    .filter(function(form) {
                        return isVisible(form) || Array.prototype.slice.call(form.querySelectorAll("a, button, input")).some(controlLooksVisible);
                    })
                    .slice(0, 5)
                    .map(function(form) {
                        return (form.getAttribute("class") || "form") + " -> " + formAction(form);
                    })
                    .join("; ");
            }
            function trimLogValue(value, limit) {
                value = String(value || "").replace(/\s+/g, " ").trim();
                return value.length > limit ? value.substring(0, limit) + "..." : value;
            }
            function describeElement(element) {
                if (!element) return null;
                return {
                    tag: (element.tagName || "").toLowerCase(),
                    id: element.id || "",
                    className: trimLogValue(element.className || "", 120),
                    text: trimLogValue(text(element), 180),
                    href: trimLogValue(element.getAttribute("href") || "", 220),
                    action: trimLogValue(element.getAttribute("action") || "", 220),
                    method: trimLogValue(element.getAttribute("method") || "", 40),
                    name: trimLogValue(element.getAttribute("name") || "", 80),
                    type: trimLogValue(element.getAttribute("type") || "", 40),
                    visible: isVisible(element),
                    disabled: !!element.disabled
                };
            }
            function summarizeForms() {
                return Array.prototype.slice.call(document.querySelectorAll("form"))
                    .slice(0, 12)
                    .map(function(form) {
                        var controls = Array.prototype.slice.call(form.querySelectorAll("a, button, input, select"))
                            .slice(0, 8)
                            .map(describeElement);
                        var hiddenNames = Array.prototype.slice.call(form.querySelectorAll("input[type='hidden']"))
                            .slice(0, 12)
                            .map(function(input) { return input.getAttribute("name") || ""; })
                            .filter(Boolean);
                        return {
                            form: describeElement(form),
                            controls: controls,
                            hiddenNames: hiddenNames
                        };
                    });
            }
            function summarizeButtons() {
                return Array.prototype.slice.call(document.querySelectorAll("a, button, input[type='submit']"))
                    .filter(function(item) {
                        var value = text(item).toLowerCase() + " " +
                            String(item.value || "").toLowerCase() + " " +
                            String(item.className || "").toLowerCase() + " " +
                            String(item.getAttribute("href") || "").toLowerCase();
                        return isVisible(item) ||
                            value.indexOf("btn-index") !== -1 ||
                            value.indexOf("passenger") !== -1 ||
                            value.indexOf("\u043f\u0430\u0441\u0441\u0430\u0436") !== -1 ||
                            value.indexOf("\u0432\u044b\u0431\u0440") !== -1 ||
                            value.indexOf("\u0434\u0430\u043d\u043d") !== -1;
                    })
                    .slice(0, 20)
                    .map(describeElement);
            }
            function summarizePassengerTransition() {
                var form = findPassengerTransitionForm();
                var button = findPassengerTransitionButton(form);
                return {
                    found: !!form,
                    form: describeElement(form),
                    button: describeElement(button),
                    action: formAction(form),
                    href: buttonHref(button),
                    visibleFormsSummary: visibleFormsSummary()
                };
            }
            function collectDiagnostics() {
                var selectedCarriage = window.__trainCheckerSelectedCarriage || "";
                var selectedAt = window.__trainCheckerSelectedCarriageAt || 0;
                return {
                    title: trimLogValue(document.title, 180),
                    url: location.href,
                    path: location.pathname,
                    readyState: document.readyState,
                    routeReady: isRoutePage(),
                    placesReady: isPlacesPage(),
                    passengerReady: isPassengerPage(),
                    loginRequired: hasLoginForm(),
                    mainRedirect: isUnexpectedMainPage(),
                    verification: hasVerification(),
                    trainRows: document.querySelectorAll(".sch-table__row[data-train-number], .sch-table__row-wrap[data-train-number]").length,
                    selectPlacesForms: document.querySelectorAll("form.js-sch-item-form[action*='/order/places'], form[action*='/order/places']").length,
                    carriageLinks: document.querySelectorAll(".pl-accord__acc-link").length,
                    visibleCarriages: visibleCarriagesSummary(),
                    carriageCostForms: document.querySelectorAll("form.carriage-cost__form").length,
                    passengerForms: document.querySelectorAll("form#contact-info_form, form[action*='/order/passengers']").length,
                    passengerFields: {
                        lastName: !!document.querySelector("[name='last_name_1']"),
                        firstName: !!document.querySelector("[name='first_name_1']"),
                        middleName: !!document.querySelector("[name='middle_name_1']"),
                        documentType: !!document.querySelector("[name='document_type_1']"),
                        documentNumber: !!document.querySelector("[name='document_number_1']"),
                        agreement: !!document.querySelector("input[name='agreement']")
                    },
                    selectedCarriage: selectedCarriage,
                    selectedCarriageAgeMs: selectedAt ? Date.now() - selectedAt : null,
                    lastScroll: window.__trainCheckerLastScroll || null,
                    passengerTransition: summarizePassengerTransition(),
                    forms: summarizeForms(),
                    buttons: summarizeButtons(),
                    bodyText: trimLogValue(text(document.body), 900)
                };
            }
            function clickElement(element) {
                scrollElementIntoActionView(element);
                element.dispatchEvent(new MouseEvent("mouseover", { bubbles: true }));
                element.dispatchEvent(new MouseEvent("mousedown", { bubbles: true }));
                element.dispatchEvent(new MouseEvent("mouseup", { bubbles: true }));
                element.click();
            }
            function submitPassengerTransitionForm(form) {
                form.scrollIntoView({ block: "center", inline: "center" });
                if (!form.getAttribute("method")) {
                    form.setAttribute("method", "post");
                }
                HTMLFormElement.prototype.submit.call(form);
            }
            function setValue(element, value) {
                if (!element) return;
                element.focus();
                element.value = value;
                element.dispatchEvent(new Event("input", { bubbles: true }));
                element.dispatchEvent(new Event("change", { bubbles: true }));
                element.blur();
            }
            function setDocumentType() {
                var type = document.querySelector("[name='document_type_1']");
                if (!type) return;
                var passport = "\u041f\u0411";
                if (type.tagName && type.tagName.toLowerCase() === "select") {
                    var option = Array.prototype.slice.call(type.options).find(function(item) {
                        return item.value === passport || text(item).indexOf("\u0411\u0435\u043b\u0430\u0440") !== -1;
                    });
                    type.value = option ? option.value : passport;
                    type.dispatchEvent(new Event("change", { bubbles: true }));
                } else {
                    setValue(type, passport);
                }
            }
            function setAgreement() {
                var agreement = document.querySelector("input[name='agreement']");
                if (!agreement) return;
                agreement.value = "2";
                agreement.checked = true;
                agreement.dispatchEvent(new Event("input", { bubbles: true }));
                agreement.dispatchEvent(new Event("change", { bubbles: true }));
            }
            function status(extra) {
                extra = extra || {};
                extra.verification = hasVerification();
                extra.url = location.href;
                extra.mainRedirect = !!extra.mainRedirect || isUnexpectedMainPage();
                extra.routeReady = !!extra.routeReady || isRoutePage();
                extra.placesReady = !!extra.placesReady || isPlacesPage();
                extra.passengerReady = !!extra.passengerReady || isPassengerPage();
                extra.loginRequired = !!extra.loginRequired || hasLoginForm();
                extra.diagnostics = collectDiagnostics();
                return extra;
            }
        """.trimIndent()
    }
}
