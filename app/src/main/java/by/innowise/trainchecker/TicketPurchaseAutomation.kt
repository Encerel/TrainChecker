package by.innowise.trainchecker

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class TicketPurchaseAutomation(
    private val route: MonitoringRoute
) {
    companion object {
        private const val TAG = "TicketPurchase"
        private const val BASE_URL = "https://pass.rw.by"
        
        private fun ensureHttps(url: String): String {
            return if (url.startsWith("http://")) {
                url.replaceFirst("http://", "https://")
            } else {
                url
            }
        }
    }

    private val cookieJar = object : CookieJar {
        // Храним куки как Map<Host, Map<CookieName, Cookie>> чтобы обновлять их, а не перезаписывать список
        private val cookieStore = mutableMapOf<String, MutableMap<String, Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(cookieStore) {
                val hostCookies = cookieStore.getOrPut(url.host) { mutableMapOf() }
                cookies.forEach { cookie ->
                    hostCookies[cookie.name] = cookie
                    Log.d(TAG, "Cookie saved: ${cookie.name}=... (domain=${cookie.domain})")
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            synchronized(cookieStore) {
                val cookies = cookieStore[url.host]?.values?.toList() ?: emptyList()
                if (cookies.isNotEmpty()) {
                    Log.d(TAG, "Sending ${cookies.size} cookies to ${url.host}")
                }
                return cookies
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    sealed class PurchaseResult {
        data class Success(val message: String) : PurchaseResult()
        data class Error(val message: String, val step: String) : PurchaseResult()
        data class NeedsLogin(val loginUrl: String) : PurchaseResult()
    }

    fun attemptPurchase(): PurchaseResult {
        return try {
            Log.d(TAG, "Starting purchase automation for train: ${route.trainNumber}")
            
            // === ШАГ 1: ПРОВЕРКА НАЛИЧИЯ МЕСТ ===
            Log.d(TAG, ">>> STEP 1: Checking availability...")
            
            // Загружаем страницу с маршрутами
            val routePageResult = loadRoutePage()
            if (routePageResult is PurchaseResult.Error) return routePageResult

            val routeDoc = (routePageResult as? RoutePageLoaded)?.document
                ?: return PurchaseResult.Error("Не удалось загрузить страницу маршрутов", "load_route")

            // Находим нужный поезд
            val trainFormResult = findTrainForm(routeDoc)
            if (trainFormResult is PurchaseResult.Error) {
                // Если поезд не найден или мест нет, мы даже не пытаемся логиниться
                return trainFormResult
            }
            
            Log.d(TAG, "✓ Train and seats found. Proceeding to login...")

            // === ШАГ 2: АВТОРИЗАЦИЯ НА ГЛАВНОЙ ===
            Log.d(TAG, ">>> STEP 2: Authenticating on main page...")
            val loginResult = loginOnMainPage()
            if (loginResult is PurchaseResult.Error) return loginResult
            
            Log.d(TAG, "✓ Login successful. Proceeding to purchase...")

            // === ШАГ 3: ПОКУПКА БИЛЕТА (с уже активной сессией) ===
            Log.d(TAG, ">>> STEP 3: Purchasing ticket...")
            
            // Важно: нужно заново загрузить страницу маршрута, чтобы сервер "увидел" нашу сессию
            // и чтобы мы получили актуальную форму для авторизованного пользователя
            val routePageResultAuthorized = loadRoutePage()
            if (routePageResultAuthorized is PurchaseResult.Error) return routePageResultAuthorized
            
            val routeDocAuthorized = (routePageResultAuthorized as? RoutePageLoaded)?.document
                ?: return PurchaseResult.Error("Не удалось загрузить страницу маршрутов после входа", "load_route_auth")
            
            // Снова находим поезд (данные могли обновиться)
            val trainFormResultAuthorized = findTrainForm(routeDocAuthorized)
            if (trainFormResultAuthorized is PurchaseResult.Error) return trainFormResultAuthorized
            
            val formData = (trainFormResultAuthorized as? TrainFormFound)?.formData
                ?: return PurchaseResult.Error("Не найден поезд ${route.trainNumber}", "find_train_auth")

            // Отправляем форму выбора мест
            val placesPageResult = submitSelectPlaces(formData)
            if (placesPageResult is PurchaseResult.Error) return placesPageResult

            val placesDoc = (placesPageResult as? PlacesPageLoaded)?.document
                ?: return PurchaseResult.Error("Не удалось загрузить страницу выбора мест", "select_places")

            // Выбираем вагон с окончанием (2П)
            val carriageResult = selectCarriage2P(placesDoc)
            if (carriageResult is PurchaseResult.Error) return carriageResult

            val carriageDoc = (carriageResult as? CarriageSelected)?.document
                ?: return PurchaseResult.Error("Не удалось выбрать вагон (2П)", "select_carriage")

            // Нажимаем "Ввести данные пассажиров"
            // Теперь мы авторизованы, поэтому должна открыться форма пассажира
            val passengerFormResult = submitEnterPassengerData(carriageDoc)
            if (passengerFormResult is PurchaseResult.Error) return passengerFormResult
            
            // Если вдруг снова вернулся NeedsLogin - что-то пошло не так
            if (passengerFormResult is PurchaseResult.NeedsLogin) {
                 return PurchaseResult.Error("Авторизация слетела или не сработала", "relogin_required")
            }

            val passengerDoc = (passengerFormResult as? PassengerFormLoaded)?.document
                ?: return PurchaseResult.Error("Не удалось загрузить форму пассажира", "passenger_form")

            // Заполняем данные пассажира и оформляем заказ (это одно действие)
            return fillAndSubmitPassengerData(passengerDoc)
        } catch (e: Exception) {
            Log.e(TAG, "Purchase automation error", e)
            PurchaseResult.Error("Ошибка: ${e.message}", "exception")
        }
    }

    private fun fillAndSubmitPassengerData(doc: Document): PurchaseResult {
        Log.d(TAG, "=== Filling and Submitting Passenger Data ===")
        
        // Находим форму пассажира
        val formSelectors = listOf(
            "form[action*='/order/passengers']",
            "form[action*='passenger']",
            "form.info-form__form",
            "form#contact-info_form",
            "form"
        )
        
        var form: Element? = null
        for (selector in formSelectors) {
            form = doc.selectFirst(selector)
            if (form != null) {
                Log.d(TAG, "✓ Found form with selector: $selector")
                break
            }
        }
        
        if (form == null) {
            return PurchaseResult.Error("Форма данных пассажира не найдена", "fill_data")
        }

        val action = form.attr("action")
        val url = if (action.startsWith("http")) action else "$BASE_URL$action"
        
        Log.d(TAG, "Form action: $url")

        val formBody = FormBody.Builder()

        // 1. Копируем скрытые поля
        form.select("input[type=hidden]").forEach { input ->
            val name = input.attr("name")
            val value = input.attr("value")
            if (name.isNotEmpty()) {
                formBody.add(name, value)
                Log.d(TAG, "  Hidden field: $name = $value")
            }
        }

        // 2. Заполняем основные поля пассажира 1
        formBody.add("last_name_1", route.passengerLastName)
        formBody.add("first_name_1", route.passengerFirstName)
        formBody.add("middle_name_1", route.passengerMiddleName)
        formBody.add("document_number_1", route.passengerDocumentNumber)
        
        // 3. Тип документа и паспорта
        // Согласно анализу: value="ПБ" (Паспорт гражданина Республики Беларусь)
        // Но лучше проверить, какой value в select, если он есть
        val docTypeSelect = form.selectFirst("select[name='document_type_1']")
        val passportOptionValue = docTypeSelect?.select("option")?.find { 
            it.text().contains("Беларусь", ignoreCase = true) || it.attr("value") == "ПБ" 
        }?.attr("value") ?: "ПБ"
        
        formBody.add("document_type_1", passportOptionValue)
        Log.d(TAG, "  Document Type: $passportOptionValue")
        
        // 4. Checkbox соглашения (agreement=2)
        // Ищем checkbox, чтобы узнать точное имя и value
        val agreementCheckbox = form.selectFirst("input[name='agreement'], input[name*='agree']")
        val agreementName = agreementCheckbox?.attr("name") ?: "agreement"
        val agreementValue = agreementCheckbox?.attr("value") ?: "2" // Default to 2 based on analysis
        
        formBody.add(agreementName, agreementValue)
        Log.d(TAG, "  Agreement: $agreementName = $agreementValue")

        // 5. Другие чекбоксы если есть
         form.select("input[type=checkbox]").filter { it.hasAttr("checked") }.forEach { input ->
             val name = input.attr("name")
             val value = input.attr("value")
             if (name != agreementName && name.isNotEmpty()) {
                 formBody.add(name, value)
             }
         }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", doc.location())
            .header("Origin", "https://pass.rw.by")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(formBody.build())
            .build()

        val response = client.newCall(request).execute()
        val responseUrl = response.request.url.toString()
        val html = response.body?.string() ?: return PurchaseResult.Error("Пустой ответ", "fill_data")
        
        Log.d(TAG, "✓ Submitted order. Response code: ${response.code}")
        Log.d(TAG, "Response URL: $responseUrl")
        
        // 6. Проверяем результат
        val htmlLower = html.lowercase()
        val resultDoc = Jsoup.parse(html)

        // А) Ошибки
        // Мы ищем специфичные классы ошибок, так как поиск просто по слову "error" 
        // может найти скрытые модальные окна (например, форму обратной связи с "+375")
        val errorElements = resultDoc.select(".error, .alert-danger, .message-error, .help-block")
        val visibleError = errorElements.find { 
            val text = it.text()
            text.isNotEmpty() && !text.contains("+375") && !text.contains("Например")
        }
        
        if (visibleError != null) {
             Log.d(TAG, "✗ Response contains error text: ${visibleError.text()}")
             return PurchaseResult.Error("Ошибка валидации: ${visibleError.text()}", "submit_order_validation")
        }
             
        // Если мы все еще на той же странице (есть поля ввода) и нет признаков успеха
        val hasPassengerInputs = html.contains("last_name_1")
        val hasSuccessRedirect = responseUrl.contains("/cart") || responseUrl.contains("/basket") || responseUrl.contains("added_to_basket=1")
         
        if (hasPassengerInputs && !hasSuccessRedirect) {
             Log.d(TAG, "✗ Still on passenger form without explicit error text")
             return PurchaseResult.Error("Не удалось оформить заказ (форма вернулась снова)", "submit_order_retry")
        }

        // Б) Успех
        // Строгая проверка URL
        if (responseUrl.contains("/order/payment") || 
            responseUrl.contains("/cart") || 
            responseUrl.contains("/basket") || 
            responseUrl.contains("added_to_basket=1")) {
             return PurchaseResult.Success("Заказ успешно оформлен! Проверьте корзину.")
        }
        
        // Поиск специфичных элементов успеха
        val hasStrictSuccessElement = resultDoc.select(".alert-success, .success-message, h1:contains(Заказ)").isNotEmpty()
        if (hasStrictSuccessElement) {
             return PurchaseResult.Success("Заказ успешно оформлен (найдено сообщение об успехе)!")
        }
        
        // В) Проверки на СБОЙ сессии или редирект на главную
        if (responseUrl.contains("authpopup=true") || 
            responseUrl.contains("/login") || 
            (responseUrl.endsWith("/ru/") && !responseUrl.contains("order"))) {
             Log.d(TAG, "✗ Redirected to login/main page - Session likely lost")
             return PurchaseResult.Error("Сессия истекла или сброшена. Попробуйте еще раз.", "session_lost")
        }
        
        // Если мы все еще на странице пассажиров - это НЕ успех
        if (responseUrl.contains("/passengers") || html.contains("last_name_1")) {
             Log.d(TAG, "⚠ Still on passenger page after submission")
             return PurchaseResult.Error("Не удалось оформить заказ (остались на форме пассажира без явных ошибок)", "submit_order_stuck")
        }
        
        // Если редирект непонятно куда - это ошибка/неизвестность
        Log.d(TAG, "⚠ Unknown redirect: $responseUrl")
        return PurchaseResult.Error("Неясный результат. URL: $responseUrl", "submit_order_unknown")

        return PurchaseResult.Error("Неизвестный результат оформления заказа", "submit_order_unknown")
    }

    private fun loginOnMainPage(): PurchaseResult {
        Log.d(TAG, "=== Performing login on Main Page ===")
        val mainPageUrl = "$BASE_URL/ru/"
        
        // 1. Загружаем главную страницу
        val request = Request.Builder()
            .url(mainPageUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
            
        val response = client.newCall(request).execute()
        val html = response.body?.string() 
            ?: return PurchaseResult.Error("Не удалось загрузить главную страницу", "login_page")
            
        val doc = Jsoup.parse(html)
        
        // 2. Ищем форму авторизации
        var loginForm = doc.selectFirst("form[action*='login']")
        
        if (loginForm == null) {
            val passwordInput = doc.selectFirst("input[type=password]")
            loginForm = passwordInput?.parents()?.find { it.tagName() == "form" }
        }
        
        if (loginForm == null) {
             Log.d(TAG, "Login form not found. Checking if already logged in...")
             // Если формы нет, может мы уже залогинены?
             val verify = verifySession()
             if (verify is PurchaseResult.Success) {
                 return verify
             }
             // Если не залогинены и формы нет - пробуем generic login
             Log.d(TAG, "Login form not found and session invalid. Trying generic request.")
             return performGenericLogin(mainPageUrl)
        }
        
        // 3. Формируем запрос авторизации
        val formBodyBuilder = FormBody.Builder()
        
        loginForm.select("input[type=hidden]").forEach { input ->
             val name = input.attr("name")
             val value = input.attr("value")
             if (name.isNotEmpty()) formBodyBuilder.add(name, value)
        }
        
        val loginInputName = loginForm.selectFirst("input[name*='login'], input[name*='user'], input[type=text]")?.attr("name") ?: "login"
        val passwordInputName = loginForm.selectFirst("input[type=password]")?.attr("name") ?: "password"
        
        formBodyBuilder.add(loginInputName, route.rwLogin)
        formBodyBuilder.add(passwordInputName, route.rwPassword)
        
        val submitButton = loginForm.selectFirst("input[type=submit], button[type=submit]")
        if (submitButton != null) {
            val name = submitButton.attr("name")
            if (name.isNotEmpty()) formBodyBuilder.add(name, submitButton.attr("value"))
        } else {
             formBodyBuilder.add("dologin", "Войти")
        }
        
        val action = loginForm.attr("action")
        val submitUrl = if (action.isNotEmpty()) {
             val raw = if (action.startsWith("http")) action else "$BASE_URL$action"
             ensureHttps(raw)
        } else {
             mainPageUrl
        }
        
        Log.d(TAG, "Submitting login to: $submitUrl")
        val loginRequest = Request.Builder()
            .url(submitUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", mainPageUrl)
            .post(formBodyBuilder.build())
            .build()
        // Выполняем запрос БЕЗ авто-редиректа
        val loginResponse = client.newCall(loginRequest).execute()
        
        // Читаем текст ответа на всякий случай (для диагностики ошибок, если не редирект)
        val responseHtml = loginResponse.body?.string() ?: ""
        
        Log.d(TAG, "Login response code: ${loginResponse.code}")
        
        if (loginResponse.isRedirect) {
            val location = loginResponse.header("Location")
            Log.d(TAG, "Login resulted in redirect to: $location")
            if (location != null) {
                // Если есть редирект, ОБЯЗАТЕЛЬНО следуем по нему, чтобы сервер зафиксировал сессию
                val safeLocation = ensureHttps(if (location.startsWith("http")) location else "$BASE_URL$location")
                Log.d(TAG, "Following login redirect to: $safeLocation")
                
                val redirectReq = Request.Builder()
                    .url(safeLocation)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val redirectResponse = client.newCall(redirectReq).execute()
                redirectResponse.body?.close() // Нам не важно тело, главное пройти по ссылке
            }
        } else {
             // Если не редирект - возможно ошибка
             val responseText = responseHtml.take(500)
             Log.d(TAG, "Login response (not redirect): $responseText")
             
             // Проверяем текст на ошибки
             val errorIndicators = listOf("Неверный логин", "Неверный пароль", "Ошибка авторизации", "Неверные данные")
             val error = errorIndicators.find { responseHtml.contains(it, ignoreCase = true) }
             if (error != null) {
                 return PurchaseResult.Error("Ошибка входа: $error", "login_submit")
             }
        }
        
        // 4. Проверяем результат через доступ к защищенной странице
        return verifySession()
    }
    
    private fun verifySession(): PurchaseResult {
        Log.d(TAG, "=== Verifying Session ===")
        // Используем подтвержденный URL личного кабинета (заказы)
        val protectedUrl = "$BASE_URL/ru/lk/orders/" 
        
        val request = Request.Builder()
            .url(protectedUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
            
        try {
            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: ""
            val url = response.request.url.toString()
            
            Log.d(TAG, "Verify URL: $url")
            Log.d(TAG, "Response code: ${response.code}")
            
            // Если нас перекинуло на страницу логина
            if (url.contains("/login") || url.contains("?login=yes")) {
                 Log.d(TAG, "✗ Session verification FAILED (redirected to login URL)")
                 return PurchaseResult.Error("Не удалось войти (перенаправление на логин)", "auth_check")
            }
            
            // Ищем положительные признаки: Имя пользователя
            val hasName = html.contains(route.passengerLastName, ignoreCase = true)
            if (hasName) {
                 Log.d(TAG, "✓ Session verification PASSED (found user name: ${route.passengerLastName})")
                 return PurchaseResult.Success("Авторизация подтверждена")
            }
            
            // Ищем другие признаки
            val successIndicators = listOf("Выход", "Logout", "История заказов", "Текущие заказы", "Архив поездок", "profile-active")
            val foundSuccess = successIndicators.find { html.contains(it, ignoreCase = true) }
            
            if (foundSuccess != null) {
                 Log.d(TAG, "✓ Session verification PASSED (found '$foundSuccess')")
                 return PurchaseResult.Success("Авторизация подтверждена")
            }
            
            // Если ПОЛОЖИТЕЛЬНЫХ признаков нет, проверяем отрицательные
            // Но осторожно, форма логина может быть скрыта в шапке
            val hasLoginForm = html.contains("action=\"/login\"", ignoreCase = true) || 
                              (html.contains("name=\"login\"", ignoreCase = true) && html.contains("name=\"password\"", ignoreCase = true))

            if (hasLoginForm) {
                 // Если есть форма логина И нет признаков успеха - скорее всего мы не зашли
                 Log.d(TAG, "✗ Session verification FAILED (login form present and no success info)")
                 Log.d(TAG, "HTML preview: ${html.take(300)}")
                 return PurchaseResult.Error("Не удалось войти (форма входа найдена)", "auth_check")
            }
            
            // Если ни того ни другого - непонятно, но допустим ок
            Log.d(TAG, "⚠ Session verification ambiguous (no explicit success or failure). Assuming OK.")
            return PurchaseResult.Success("Авторизация предположительно успешна (неявная)")
            
        } catch (e: Exception) {
            Log.e(TAG, "Session verification error", e)
            return PurchaseResult.Error("Ошибка проверки сессии: ${e.message}", "auth_check")
        }
    }
    
    private fun performGenericLogin(referer: String): PurchaseResult {
        // Запасной вариант, если форму не нашли
        val url = "$BASE_URL/ru/" // Обычно авторизация на главной шлется на саму страницу или /login/
        
        val formBody = FormBody.Builder()
            .add("login", route.rwLogin)
            .add("password", route.rwPassword)
            .add("dologin", "Войти")
            .build()
            
         val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", referer)
            .post(formBody)
            .build()
            
         val response = client.newCall(request).execute()
         val html = response.body?.string() ?: ""
         return checkLoginSuccess(html)
    }
    
    private fun checkLoginSuccess(html: String): PurchaseResult {
         val successIndicators = listOf("Выход", "logout", "/ru/lk/orders/")
         val errorIndicators = listOf("Неверный логин", "Неверный пароль", "Ошибка авторизации", "Неверные данные")
         
         // Проверяем наличие Имени и Фамилии пользователя (как подтверждение входа)
         val userNamePresent = html.contains(route.passengerLastName, ignoreCase = true) || 
                              (route.passengerFirstName.isNotEmpty() && html.contains(route.passengerFirstName, ignoreCase = true))
         
         if (userNamePresent) {
             Log.d(TAG, "✓ Login SUCCESSFUL - User name found in page")
             return PurchaseResult.Success("Авторизация успешна (найдено имя пользователя)")
         }

         val isSuccess = successIndicators.any { html.contains(it, ignoreCase = true) }
         val isError = errorIndicators.any { html.contains(it, ignoreCase = true) }
         
         if (isSuccess && !isError) {
             return PurchaseResult.Success("Авторизация успешна")
         }
         
         if (isError) {
             return PurchaseResult.Error("Ошибка авторизации: Неверный логин или пароль", "login_failed")
         }
         
         // По просьбе пользователя: если кнопки "Личный кабинет" НЕТ - значит мы авторизованы (она меняется на что-то другое)
         val hasPersonalCabinetButton = html.contains("Личный кабинет", ignoreCase = true)
         if (!hasPersonalCabinetButton) {
              Log.d(TAG, "✓ Login SUCCESSFUL - 'Личный кабинет' button not found (replaced by profile?)")
              return PurchaseResult.Success("Авторизация успешна")
         }



         
         if (html.contains("Выход", ignoreCase = true) || html.contains("logout", ignoreCase = true)) {
              return PurchaseResult.Success("Авторизация успешна")
         }
         
         // Если мы не нашли явного подтверждения (слов "Выход"), но "Личный кабинет" есть...
         val hasLoginInput = html.contains("name=\"login\"", ignoreCase = true) || html.contains("name='login'", ignoreCase = true)
         if (hasLoginInput) {
             return PurchaseResult.Error("Не удалось авторизоваться (форма входа всё ещё на странице)", "login_failed_form_present")
         }

         Log.d(TAG, "Login ambiguous: 'Личный кабинет' present, but no error. Assuming failure to be safe.")
         return PurchaseResult.Error("Не удалось авторизоваться (кнопка 'Личный кабинет' все еще есть)", "login_failed_cabinet_present")
    }

    private sealed class InternalResult
    private data class RoutePageLoaded(val document: Document) : InternalResult()
    private data class TrainFormFound(val formData: FormData) : InternalResult()
    private data class PlacesPageLoaded(val document: Document) : InternalResult()
    private data class CarriageSelected(val document: Document) : InternalResult()
    private data class PassengerFormLoaded(val document: Document) : InternalResult()
    private data class PassengerDataFilled(val document: Document) : InternalResult()

    private data class FormData(
        val action: String,
        val routeValue: String,
        val urlValue: String,
        val allInputs: Map<String, String> = emptyMap()
    )

    private fun loadRoutePage(): Any {
        val safeUrl = ensureHttps(route.url)
        val request = Request.Builder()
            .url(safeUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        val response = client.newCall(request).execute()
        
        val html = if (response.isRedirect) {
             // Handle manual redirect if needed
             val location = response.header("Location") ?: return PurchaseResult.Error("Redirect without location", "load_route")
             val newUrl = if (location.startsWith("http")) location else "$BASE_URL$location"
             Log.d(TAG, "Follow redirect manually to: $newUrl")
             val redirectReq = request.newBuilder().url(newUrl).build()
             client.newCall(redirectReq).execute().body?.string()
        } else {
             response.body?.string()
        } ?: return PurchaseResult.Error("Пустой ответ", "load_route")
        
        Log.d(TAG, "Route page loaded, length: ${html.length}")
        return RoutePageLoaded(Jsoup.parse(html))
    }

    private fun findTrainForm(doc: Document): Any {
        Log.d(TAG, "=== Finding train form for: ${route.trainNumber} ===")
        
        // Используем правильный селектор согласно анализу сайта
        val trainRouteLinks = doc.select(".sch-table__route")
        Log.d(TAG, "Found ${trainRouteLinks.size} train route links")

        for (routeLink in trainRouteLinks) {
            // Номер поезда в первом span элементе
            val trainNumberSpan = routeLink.selectFirst("span:first-child")
            val trainNumber = trainNumberSpan?.text()?.trim() ?: continue
            
            if (trainNumber.isEmpty()) continue
            
            Log.d(TAG, "Checking train: '$trainNumber' vs '${route.trainNumber}'")
            
            // Проверяем совпадение (игнорируя регистр и пробелы)
            val normalizedFound = trainNumber.replace("\\s+".toRegex(), "").uppercase()
            val normalizedTarget = route.trainNumber.replace("\\s+".toRegex(), "").uppercase()
            
            if (normalizedFound == normalizedTarget) {
                Log.d(TAG, "✓ Found matching train: $trainNumber")
                
                // Ищем форму - она должна быть родительским элементом или рядом
                var form = routeLink.closest("form")
                
                // Если форма не найдена через closest, ищем в родительских элементах
                if (form == null) {
                    var parent = routeLink.parent()
                    var attempts = 0
                    while (parent != null && form == null && attempts < 5) {
                        form = parent.selectFirst("form")
                        if (form == null) {
                            parent = parent.parent()
                        }
                        attempts++
                    }
                }
                
                if (form != null) {
                    Log.d(TAG, "✓ Found form for train $trainNumber")
                    
                    // Проверяем наличие кнопки "Выбрать места"
                    val selectButton = form.selectFirst("a.btn-index, a.btn.btn-index")
                    if (selectButton != null) {
                        Log.d(TAG, "✓ Found 'Выбрать места' button")
                        
                        // Собираем все скрытые поля формы
                        val action = form.attr("action")
                        val formInputs = mutableMapOf<String, String>()
                        
                        form.select("input[type=hidden]").forEach { input ->
                            val name = input.attr("name")
                            val value = input.attr("value")
                            if (name.isNotEmpty()) {
                                formInputs[name] = value
                            }
                        }
                        
                        Log.d(TAG, "✓ Form details: action=$action, fields=${formInputs.keys.size}")
                        formInputs.forEach { (name, value) ->
                            Log.d(TAG, "  Field: $name = ${value.take(50)}${if (value.length > 50) "..." else ""}")
                        }
                        
                        return TrainFormFound(FormData(
                            action = action,
                            routeValue = formInputs["route"] ?: "",
                            urlValue = formInputs["url"] ?: "",
                            allInputs = formInputs
                        ))
                    } else {
                        Log.d(TAG, "✗ Form found but no 'Выбрать места' button (no available seats?)")
                    }
                } else {
                    Log.d(TAG, "✗ Train $trainNumber found but no associated form")
                }
            }
        }

        // Дополнительная диагностика если не нашли
        Log.d(TAG, "=== Diagnostic info ===")
        val allTrainNumbers = trainRouteLinks.mapNotNull { 
            it.selectFirst("span:first-child")?.text()?.trim() 
        }
        Log.d(TAG, "All train numbers found: $allTrainNumbers")
        
        val forms = doc.select("form")
        Log.d(TAG, "Total forms on page: ${forms.size}")
        val formsWithButton = forms.count { it.selectFirst("a.btn-index, a.btn.btn-index") != null }
        Log.d(TAG, "Forms with 'Select' button: $formsWithButton")

        return PurchaseResult.Error(
            "Поезд ${route.trainNumber} не найден или нет свободных мест", 
            "find_train"
        )
    }

    private fun submitSelectPlaces(formData: FormData): Any {
        val rawUrl = if (formData.action.startsWith("http")) {
            formData.action
        } else if (formData.action.startsWith("/")) {
            "$BASE_URL${formData.action}"
        } else {
            "$BASE_URL/${formData.action}"
        }
        val url = ensureHttps(rawUrl)
        
        Log.d(TAG, "Submitting to: $url")
        Log.d(TAG, "Form fields to submit: ${formData.allInputs.size}")
        
        // Отправляем POST с всеми полями формы
        val formBodyBuilder = FormBody.Builder()
        
        // Добавляем все поля из формы
        formData.allInputs.forEach { (name, value) ->
            formBodyBuilder.add(name, value)
            Log.d(TAG, "Adding form field: $name = ${value.take(50)}")
        }
        
        val formBody = formBodyBuilder.build()
        
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", route.url)
            .post(formBody)
            .build()

        val response = client.newCall(request).execute()
        val responseUrl = response.request.url.toString()
        val html = response.body?.string() ?: return PurchaseResult.Error("Пустой ответ", "select_places")
        
        Log.d(TAG, "Response URL: $responseUrl")
        Log.d(TAG, "Places page loaded, length: ${html.length}")
        
        // Сохраняем HTML для диагностики
        val doc = Jsoup.parse(html)
        Log.d(TAG, "Page title: ${doc.title()}")
        
        // Проверяем, что мы действительно на странице выбора мест
        if (!responseUrl.contains("/order/places") && !doc.title().contains("мест", ignoreCase = true)) {
            Log.w(TAG, "Warning: Response might not be the places page")
            Log.d(TAG, "HTML preview: ${html.take(500)}")
        }
        
        return PlacesPageLoaded(doc)
    }

    private fun selectCarriage2P(doc: Document): Any {
        Log.d(TAG, "=== Checking carriage selection page ===")
        Log.d(TAG, "Page title: ${doc.title()}")
        
        // Проверяем, что мы на странице выбора мест
        val pageUrl = doc.location()
        if (pageUrl.isNotEmpty()) {
            Log.d(TAG, "Current page URL: $pageUrl")
        }
        
        // Проверяем наличие ключевых слов страницы выбора мест
        val pageText = doc.text()
        val isPlacesPage = pageUrl.contains("/order/places") || 
                          pageUrl.contains("/ru/route/") ||
                          pageText.contains("Выбрать места", ignoreCase = true) ||
                          pageText.contains("Вагон", ignoreCase = true)
        
        if (!isPlacesPage) {
            Log.d(TAG, "⚠ Warning: Might not be on places selection page")
            Log.d(TAG, "Page text preview: ${pageText.take(200)}")
        }
        
        // Логируем что нашли на странице (для диагностики)
        val has2P = pageText.contains("2П", ignoreCase = true)
        val hasVagon = pageText.contains("Вагон", ignoreCase = true)
        val hasSidyachiy = pageText.contains("Сидячий", ignoreCase = true)
        
        Log.d(TAG, "Page analysis:")
        Log.d(TAG, "  - Contains '2П': $has2P")
        Log.d(TAG, "  - Contains 'Вагон': $hasVagon")
        Log.d(TAG, "  - Contains 'Сидячий': $hasSidyachiy")
        
        // Пытаемся найти конкретные вагоны для логирования
        val vagonElements = doc.select("span, a, div").filter { 
            it.text().contains("Вагон №", ignoreCase = true) 
        }
        
        if (vagonElements.isNotEmpty()) {
            Log.d(TAG, "Found ${vagonElements.size} carriage elements:")
            vagonElements.take(5).forEach { element ->
                Log.d(TAG, "  - ${element.text().trim().take(50)}")
            }
        } else {
            Log.d(TAG, "No specific carriage elements found yet (might load dynamically)")
        }
        
        // Для поездов "Региональные линии бизнес-класса" вагоны (2П) появляются
        // после клика на "Выбрать места" в категории "Сидячий".
        // Мы просто продолжаем процесс - следующий шаг найдет кнопку "Ввести данные пассажиров"
        Log.d(TAG, "✓ Proceeding to passenger data entry")
        return CarriageSelected(doc)
    }

    private fun submitEnterPassengerData(doc: Document): Any {
        Log.d(TAG, "=== Submitting passenger data form ===")
        
        // Сначала проверяем, может мы уже на странице с формой пассажира
        val hasPassengerForm = doc.selectFirst("form[action*='/order/passengers'], form[action*='passenger']") != null
        val hasPassengerFields = doc.selectFirst("input[name*='last_name'], input[name*='first_name'], input[name*='lastname'], input[name*='firstname']") != null
        
        if (hasPassengerForm || hasPassengerFields) {
            Log.d(TAG, "✓ Already on passenger form page, returning it directly")
            return PassengerFormLoaded(doc)
        }
        
        // На странице выбора мест должна быть кнопка "Ввести данные пассажиров" (ВВЕСТИ ДАННЫЕ ПАССАЖИРОВ)
        // Ищем кнопку с различными вариантами текста
        val buttonSelectors = listOf(
            "a:containsOwn(ВВЕСТИ ДАННЫЕ ПАССАЖИРОВ)",
            "a:contains(Ввести данные пассажиров)",
            "button:contains(ВВЕСТИ ДАННЫЕ ПАССАЖИРОВ)",
            "button:contains(Ввести данные пассажиров)",
            "a.btn:contains(ВВЕСТИ)",
            "button.btn:contains(ВВЕСТИ)",
            "a:contains(ДАННЫЕ ПАССАЖИРОВ)",
            "button:contains(ДАННЫЕ ПАССАЖИРОВ)"
        )
        
        var button: Element? = null
        for (selector in buttonSelectors) {
            button = doc.selectFirst(selector)
            if (button != null) {
                Log.d(TAG, "✓ Found button with selector: $selector, text: '${button.text()}'")
                break
            }
        }
        
        if (button == null) {
            Log.d(TAG, "✗ Button not found with standard selectors, searching all links and buttons...")
            
            // Ищем среди всех ссылок и кнопок
            val allLinks = doc.select("a, button")
            for (link in allLinks) {
                val text = link.text().trim()
                if (text.contains("ВВЕСТИ", ignoreCase = true) && 
                    (text.contains("ДАННЫЕ", ignoreCase = true) || text.contains("ПАССАЖИР", ignoreCase = true))) {
                    button = link
                    Log.d(TAG, "✓ Found button by text search: '$text'")
                    break
                }
            }
        }
        
        if (button == null) {
            Log.d(TAG, "✗ Button not found, looking for form directly...")
            
            // Пытаемся найти форму напрямую
            val form = doc.selectFirst("form[action*='/order/passengers'], form[action*='passenger']")
            if (form != null) {
                Log.d(TAG, "✓ Found form directly: ${form.attr("action")}")
                return submitFormToPassengers(form, doc)
            }
            
            // Логируем все кнопки и ссылки для диагностики
            Log.d(TAG, "=== Available buttons and links ===")
            doc.select("a.btn, button, a[href*='passenger'], a[href*='order']").take(10).forEach { elem ->
                Log.d(TAG, "  ${elem.tagName()}: text='${elem.text().take(50)}', href='${elem.attr("href")}', class='${elem.attr("class")}'")
            }
            
            return PurchaseResult.Error("Кнопка 'Ввести данные пассажиров' не найдена", "passenger_button_missing")
        }
        
        // Если кнопка - это ссылка с href
        val href = button.attr("href")
        if (href.isNotEmpty() && !href.contains("javascript")) {
            Log.d(TAG, "Button is a link, navigating to: $href")
            val url = if (href.startsWith("http")) href else "$BASE_URL$href"
            val safeUrl = ensureHttps(url)
            
            val request = Request.Builder()
                .url(safeUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", doc.location())
                .build()
            
            val response = client.newCall(request).execute()
            val responseUrl = response.request.url.toString()
            val html = response.body?.string() ?: return PurchaseResult.Error("Пустой ответ", "passenger_form")
            
            Log.d(TAG, "Response URL after clicking button: $responseUrl")
            Log.d(TAG, "Response length: ${html.length}")
            
            return handlePassengerPageResponse(html)
        }
        
        // Если кнопка вызывает JavaScript или находится в форме
        val form = button.closest("form")
        if (form != null) {
            Log.d(TAG, "✓ Found form for button")
            return submitFormToPassengers(form, doc)
        }
        
        // Если кнопка без href и без формы - возможно это JavaScript кнопка
        Log.d(TAG, "⚠ Button found but no href or form. Button details:")
        Log.d(TAG, "  Tag: ${button.tagName()}, Class: ${button.attr("class")}, OnClick: ${button.attr("onclick")}")
        
        return PurchaseResult.Error("Не удалось определить действие для кнопки (возможно требуется JavaScript)", "passenger_button_action")
    }
    
    private fun submitFormToPassengers(form: Element, doc: Document): Any {
        val action = form.attr("action")
        val rawUrl = if (action.startsWith("http")) action else "$BASE_URL$action"
        val url = ensureHttps(rawUrl)
        
        Log.d(TAG, "Submitting form to: $url")
        
        val formBody = FormBody.Builder()
        form.select("input[type=hidden]").forEach { input ->
            val name = input.attr("name")
            val value = input.attr("value")
            if (name.isNotEmpty()) {
                formBody.add(name, value)
                Log.d(TAG, "  Form field: $name = ${value.take(30)}")
            }
        }
        
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", doc.location())
            .post(formBody.build())
            .build()
        
        val response = client.newCall(request).execute()
        Log.d(TAG, "Passenger form response code: ${response.code}")
        val html = response.body?.string() ?: return PurchaseResult.Error("Пустой ответ", "passenger_form")
        
        return handlePassengerPageResponse(html)
    }
    
    private fun handlePassengerPageResponse(html: String): Any {
        val resultDoc = Jsoup.parse(html)
        
        Log.d(TAG, "=== Checking if login is required ===")
        Log.d(TAG, "Response length: ${html.length}")
        
        // 1. Сначала проверяем, залогинены ли мы (по имени пользователя)
        val hasName = html.contains(route.passengerLastName, ignoreCase = true) || 
                     (route.passengerFirstName.isNotEmpty() && html.contains(route.passengerFirstName, ignoreCase = true))
                     
        if (hasName) {
             Log.d(TAG, "✓ User name found in response - Session is VALID")
             
             // Расширенная проверка наличия формы пассажира с разными селекторами
             val hasPassengerForm = resultDoc.selectFirst("form[action*='/order/passengers'], form[action*='passenger']") != null
             val hasPassengerFields = resultDoc.selectFirst("input[name*='last_name'], input[name*='first_name'], input[name*='lastname'], input[name*='firstname']") != null
             val hasPassengerInputs = resultDoc.selectFirst("input[placeholder*='Фамилия'], input[placeholder*='Имя']") != null
             val hasPassengerText = html.contains("Данные пассажира", ignoreCase = true) || 
                                   html.contains("Пассажир", ignoreCase = true) ||
                                   html.contains("Фамилия", ignoreCase = true)
             
             Log.d(TAG, "Passenger form checks: hasPassengerForm=$hasPassengerForm, hasPassengerFields=$hasPassengerFields, hasPassengerInputs=$hasPassengerInputs, hasPassengerText=$hasPassengerText")
             
             if (hasPassengerForm || hasPassengerFields || hasPassengerInputs) {
                 Log.d(TAG, "✓ Passenger form found")
                 return PassengerFormLoaded(resultDoc)
             }
             
             // Проверяем, может быть это страница выбора мест, и нужно еще раз нажать кнопку
             val hasEnterDataButton = resultDoc.selectFirst("a:contains(ВВЕСТИ ДАННЫЕ), button:contains(ВВЕСТИ ДАННЫЕ), a:contains(Ввести данные пассажиров)") != null
             if (hasEnterDataButton) {
                 Log.d(TAG, "⚠ Still on places page, button 'Enter passenger data' found. Trying to click it again...")
                 return submitEnterPassengerData(resultDoc)
             }
             
             // Мы залогинены, но формы нет
             Log.d(TAG, "✗ Logged in but passenger form MISSING")
             Log.d(TAG, "Page Title: ${resultDoc.title()}")
             Log.d(TAG, "Page URL: ${resultDoc.location()}")
             
             // Логируем найденные формы
             val forms = resultDoc.select("form")
             if (forms.isEmpty()) {
                 Log.d(TAG, "No forms found on page.")
             } else {
                 Log.d(TAG, "Forms found: ${forms.size}")
                 forms.forEachIndexed { i, f -> 
                     Log.d(TAG, "Form $i action: ${f.attr("action")}")
                     val inputs = f.select("input")
                     Log.d(TAG, "  Inputs: ${inputs.map { "${it.attr("name")}(${it.attr("type")})" }.joinToString(", ")}")
                 }
             }
             
             // Логируем все input поля на странице
             val allInputs = resultDoc.select("input")
             Log.d(TAG, "All inputs on page: ${allInputs.size}")
             allInputs.take(10).forEach { input ->
                 Log.d(TAG, "  Input: name='${input.attr("name")}', type='${input.attr("type")}', placeholder='${input.attr("placeholder")}'")
             }
             
             // Логируем текст (первые 800 символов)
             Log.d(TAG, "Body Text Preview: ${resultDoc.body().text().take(800)}")
             
             // Логируем HTML (первые 1000 символов для детального анализа)
             Log.d(TAG, "HTML Preview: ${html.take(1000)}")
             
             return PurchaseResult.Error("Ошибка: Вход выполнен, но форма пассажира не открылась. Проверьте логи.", "passenger_form_missing")
        }
        
        // 2. Если имени нет, проверяем явную форму логина
        val hasLoginInput = resultDoc.selectFirst("input[name=login]") != null
        val hasPasswordInput = resultDoc.selectFirst("input[name=password]") != null
        val hasLoginForm = hasLoginInput && hasPasswordInput
        
        Log.d(TAG, "Login form check: hasLoginInput=$hasLoginInput, hasPasswordInput=$hasPasswordInput")
        
        // Проверяем наличие формы пассажира (на случай если имя не нашли, но форма есть)
        val hasPassengerForm = resultDoc.selectFirst("form[action*='/order/passengers'], form[action*='passenger']") != null
        val hasPassengerFields = resultDoc.selectFirst("input[name*='last_name'], input[name*='first_name']") != null
        
        Log.d(TAG, "Passenger form check: hasPassengerForm=$hasPassengerForm, hasPassengerFields=$hasPassengerFields")
        
        // Если есть РЕАЛЬНАЯ форма логина (оба поля) - нужна авторизация
        if (hasLoginForm) {
            Log.d(TAG, "✓ Login IS REQUIRED - found login form with both fields")
            return PurchaseResult.NeedsLogin("$BASE_URL/ru/login")
        }
        
        // Если есть форма пассажира или поля пассажира - авторизация НЕ нужна
        if (hasPassengerForm || hasPassengerFields) {
            Log.d(TAG, "✓ Login NOT required - passenger form found")
            return PassengerFormLoaded(resultDoc)
        }
        
        // Если непонятно - выводим больше информации
        Log.d(TAG, "⚠ Page type UNCLEAR")
        Log.d(TAG, "HTML preview (first 500 chars): ${html.take(500)}")
        
        // Раньше мы считали это успехом, теперь лучше вернуть ошибку, чтобы не зависать
        return PurchaseResult.Error("Не удалось определить тип страницы (нет формы входа и нет формы пассажира)", "unknown_page")
    }

    private fun performLogin(): PurchaseResult {
        Log.d(TAG, "=== Performing login ===")
        Log.d(TAG, "Login: ${route.rwLogin}")

        // Согласно анализу браузера, форма авторизации в модальном окне
        // отправляется на /ru/order/places/, а не на /ru/login
        val loginUrl = "$BASE_URL/ru/order/places/"
        
        Log.d(TAG, "Submitting login to: $loginUrl")

        val formBody = FormBody.Builder()
            .add("login", route.rwLogin)
            .add("password", route.rwPassword)
            .add("dologin", "Войти")
            .build()

        val loginRequest = Request.Builder()
            .url(loginUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", "$BASE_URL/ru/order/places/")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(formBody)
            .build()

        val loginResponse = client.newCall(loginRequest).execute()
        val responseCode = loginResponse.code
        val responseUrl = loginResponse.request.url.toString()
        val responseHtml = loginResponse.body?.string() ?: ""
        
        Log.d(TAG, "Login response code: $responseCode")
        Log.d(TAG, "Login response URL: $responseUrl")
        Log.d(TAG, "Response length: ${responseHtml.length}")
        
        // Сохраняем превью для диагностики
        val htmlPreview = responseHtml.take(500)
        Log.d(TAG, "Response HTML preview: $htmlPreview")

        // Проверяем успешность авторизации
        // Ищем признаки НЕУСПЕШНОЙ авторизации
        val errorIndicators = listOf(
            "Неверный логин",
            "Неверный пароль", 
            "Неправильный логин",
            "Неправильный пароль",
            "Ошибка авторизации",
            "Неверные данные",
            "incorrect",
            "invalid"
        )
        
        val hasError = errorIndicators.any { responseHtml.contains(it, ignoreCase = true) }
        
        if (hasError) {
            Log.d(TAG, "✗ Login FAILED - error message detected")
            return PurchaseResult.Error("Ошибка авторизации. Неверный логин или пароль.", "login")
        }
        
        // Проверяем наличие формы логина В ОТВЕТЕ (не просто слово "Авторизация")
        // Ищем конкретно input с name="login" и type="text" или type="email"
        val hasLoginInput = responseHtml.contains("name=\"login\"", ignoreCase = true) ||
                           responseHtml.contains("name='login'", ignoreCase = true)
        val hasPasswordInput = responseHtml.contains("name=\"password\"", ignoreCase = true) ||
                              responseHtml.contains("name='password'", ignoreCase = true)
        val hasLoginForm = hasLoginInput && hasPasswordInput
        
        Log.d(TAG, "Login form check: hasLoginInput=$hasLoginInput, hasPasswordInput=$hasPasswordInput, hasLoginForm=$hasLoginForm")
        
        // Ищем признаки успешной авторизации
        val successIndicators = listOf(
            "Выход",
            "logout",
            "Личный кабинет"
        )
        
        val hasSuccess = successIndicators.any { responseHtml.contains(it, ignoreCase = true) }
        Log.d(TAG, "Success indicators check: hasSuccess=$hasSuccess")
        
        // Если нет формы логина И нет ошибок - считаем успехом
        if (!hasLoginForm && !hasError) {
            Log.d(TAG, "✓ Login SUCCESSFUL - no login form in response, no errors")
            return PurchaseResult.Success("Авторизация успешна")
        }
        
        // Если есть индикаторы успеха - точно успех
        if (hasSuccess) {
            Log.d(TAG, "✓ Login SUCCESSFUL - success indicators found")
            return PurchaseResult.Success("Авторизация успешна")
        }
        
        // Если форма логина всё ещё есть - значит авторизация не прошла
        if (hasLoginForm) {
            Log.d(TAG, "✗ Login FAILED - login form still present in response")
            return PurchaseResult.Error("Ошибка авторизации. Форма логина всё ещё присутствует.", "login")
        }

        // Если непонятно - выводим больше информации
        Log.d(TAG, "⚠ Login result UNCLEAR")
        Log.d(TAG, "Full response (first 1000 chars): ${responseHtml.take(1000)}")
        
        // По умолчанию считаем неудачей, чтобы не зациклиться
        return PurchaseResult.Error("Неясный результат авторизации. Проверьте логи.", "login")
    }




}
