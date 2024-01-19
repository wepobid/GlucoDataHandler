package de.michelinside.glucodatahandler.common.tasks

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.util.Log
import de.michelinside.glucodatahandler.common.Constants
import de.michelinside.glucodatahandler.common.GlucoDataService
import de.michelinside.glucodatahandler.common.ReceiveData
import de.michelinside.glucodatahandler.common.SourceState
import de.michelinside.glucodatahandler.common.SourceStateData
import de.michelinside.glucodatahandler.common.notifier.DataSource
import de.michelinside.glucodatahandler.common.notifier.InternalNotifier
import de.michelinside.glucodatahandler.common.notifier.NotifySource
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

abstract class DataSourceTask(private val enabledKey: String, protected val source: DataSource) : BackgroundTask() {
    private var enabled = false
    private var interval = 1L
    private var delaySec = 10L

    companion object {
        private val LOG_ID = "GDH.Task.DataSourceTask"

        val preferencesToSend = mutableSetOf(
            Constants.SHARED_PREF_SOURCE_DELAY,
            Constants.SHARED_PREF_SOURCE_INTERVAL,
            Constants.SHARED_PREF_LIBRE_USER,
            Constants.SHARED_PREF_LIBRE_PASSWORD,
            Constants.SHARED_PREF_LIBRE_RECONNECT,
            Constants.SHARED_PREF_NIGHTSCOUT_URL,
            Constants.SHARED_PREF_NIGHTSCOUT_SECRET,
            Constants.SHARED_PREF_NIGHTSCOUT_TOKEN,
            Constants.SHARED_PREF_NIGHTSCOUT_IOB_COB
        )
        fun updateSettings(context: Context, bundle: Bundle) {
            val sharedPref = context.getSharedPreferences(Constants.SHARED_PREF_TAG, Context.MODE_PRIVATE)
            with(sharedPref.edit()) {
                putInt(Constants.SHARED_PREF_SOURCE_DELAY, bundle.getInt(Constants.SHARED_PREF_SOURCE_DELAY, -1))
                putString(Constants.SHARED_PREF_SOURCE_INTERVAL, bundle.getString(Constants.SHARED_PREF_SOURCE_INTERVAL, "1"))
                putString(Constants.SHARED_PREF_LIBRE_USER, bundle.getString(Constants.SHARED_PREF_LIBRE_USER, ""))
                putString(Constants.SHARED_PREF_LIBRE_PASSWORD, bundle.getString(Constants.SHARED_PREF_LIBRE_PASSWORD, ""))
                putBoolean(Constants.SHARED_PREF_LIBRE_RECONNECT, bundle.getBoolean(Constants.SHARED_PREF_LIBRE_RECONNECT, false))

                putString(Constants.SHARED_PREF_NIGHTSCOUT_URL, bundle.getString(Constants.SHARED_PREF_NIGHTSCOUT_URL, ""))
                putString(Constants.SHARED_PREF_NIGHTSCOUT_SECRET, bundle.getString(Constants.SHARED_PREF_NIGHTSCOUT_SECRET, ""))
                putString(Constants.SHARED_PREF_NIGHTSCOUT_TOKEN, bundle.getString(Constants.SHARED_PREF_NIGHTSCOUT_TOKEN, ""))
                putBoolean(Constants.SHARED_PREF_NIGHTSCOUT_IOB_COB, bundle.getBoolean(Constants.SHARED_PREF_NIGHTSCOUT_IOB_COB, true))
                apply()
            }
            InternalNotifier.notify(context, NotifySource.SOURCE_SETTINGS, null)
        }
        fun getSettingsBundle(sharedPref: SharedPreferences): Bundle {
            val bundle = Bundle()
            bundle.putInt(Constants.SHARED_PREF_SOURCE_DELAY, sharedPref.getInt(Constants.SHARED_PREF_SOURCE_DELAY, -1))
            bundle.putString(Constants.SHARED_PREF_SOURCE_INTERVAL, sharedPref.getString(Constants.SHARED_PREF_SOURCE_INTERVAL, "1"))
            bundle.putString(Constants.SHARED_PREF_LIBRE_USER, sharedPref.getString(Constants.SHARED_PREF_LIBRE_USER, ""))
            bundle.putString(Constants.SHARED_PREF_LIBRE_PASSWORD, sharedPref.getString(Constants.SHARED_PREF_LIBRE_PASSWORD, ""))
            bundle.putBoolean(Constants.SHARED_PREF_LIBRE_RECONNECT, sharedPref.getBoolean(Constants.SHARED_PREF_LIBRE_RECONNECT, false))

            bundle.putString(Constants.SHARED_PREF_NIGHTSCOUT_URL, sharedPref.getString(Constants.SHARED_PREF_NIGHTSCOUT_URL, ""))
            bundle.putString(Constants.SHARED_PREF_NIGHTSCOUT_SECRET, sharedPref.getString(Constants.SHARED_PREF_NIGHTSCOUT_SECRET, ""))
            bundle.putString(Constants.SHARED_PREF_NIGHTSCOUT_TOKEN, sharedPref.getString(Constants.SHARED_PREF_NIGHTSCOUT_TOKEN, ""))
            bundle.putBoolean(Constants.SHARED_PREF_NIGHTSCOUT_IOB_COB, sharedPref.getBoolean(Constants.SHARED_PREF_NIGHTSCOUT_IOB_COB, true))
            return bundle
        }

        fun isConnected(context: Context): Boolean {
            try {
                val connectivityManager = context.getSystemService(
                    Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
                    ?: return false

                val activeNetwork = connectivityManager.activeNetwork ?: return false
                val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

                // If we check only for "NET_CAPABILITY_INTERNET", we get "true" if we are connected to a wifi
                // which has no access to the internet. "NET_CAPABILITY_VALIDATED" also verifies that we
                // are online
                return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            } catch (exc: Exception) {
                Log.e(LOG_ID, "isConnected exception: " + exc.message.toString() )
            }
            return false
        }
    }

    var lastState = SourceState.NONE
    var lastErrorCode: Int = -1

    fun setLastError(error: String, code: Int = -1) {
        setState( SourceState.ERROR, error, code)
    }

    fun setState(state: SourceState, error: String = "", code: Int = -1) {
        Log.v(LOG_ID,"Set state for source " + source + ": " + state + " - " + error + " (" + code + ")")
        lastErrorCode = code
        lastState = state

        SourceStateData.setState(source, state, getErrorMessage(state, error, code))
    }

    private fun getErrorMessage(state: SourceState, error: String, code: Int): String {
        if (state == SourceState.ERROR && error.isNotEmpty()) {
            var result = ""
            if (code > 0) {
                result = code.toString() + ": "
            }
            result += error
            return result
        }
        return ""
    }

    private fun isShortInterval(): Boolean {
        return when(lastState) {
            SourceState.NO_CONNECTION,
            SourceState.NO_NEW_VALUE -> true
            SourceState.ERROR -> lastErrorCode >= 500
            else -> false
        }
    }

    abstract fun executeRequest(context: Context)

    override fun execute(context: Context) {
        if (enabled) {
            if (!isConnected(context)) {
                setState(SourceState.NO_CONNECTION)
                return
            }
            Log.d(LOG_ID, "Execute request")
            try {
                executeRequest(context)
            } catch (ex: UnknownHostException) {
                Log.w(LOG_ID, "Internet connection issue: " + ex)
                setState(SourceState.NO_CONNECTION)
            } catch (ex: Exception) {
                Log.e(LOG_ID, "Exception during login: " + ex)
                setLastError(ex.message.toString())
            }
        }
    }

    protected fun handleResult(extras: Bundle) {
        val done = AtomicBoolean(false)
        val task = Runnable {
            val lastTime = ReceiveData.time
            val lastIobCobTime = ReceiveData.iobCobTime
            ReceiveData.handleIntent(GlucoDataService.context!!, source, extras)
            if (ReceiveData.time == lastTime && lastIobCobTime != ReceiveData.iobCobTime && !forceExecute())
                setState(SourceState.NO_NEW_VALUE)
            else
                setState(SourceState.NONE)
            done.set(true)
        }
        Handler(GlucoDataService.context!!.mainLooper).post(task)
        while (!done.get()) {
            Thread.sleep(5)
        }
        Log.d(LOG_ID, "handleResult for " + source + " done!")
    }

    protected fun trustAllCertificates(httpURLConnection: HttpsURLConnection) {
        // trust all certificates (see https://www.baeldung.com/okhttp-client-trust-all-certificates)
        val trustAllCerts = arrayOf<TrustManager>(
            @SuppressLint("CustomX509TrustManager")
            object : X509TrustManager {
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> {
                    return arrayOf()
                }
            }
        )
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())
        httpURLConnection.sslSocketFactory = sslContext.socketFactory
        httpURLConnection.setHostnameVerifier { _: String?, _: SSLSession? -> true }
    }

    private fun checkResponse(httpURLConnection: HttpURLConnection): String? {
        val responseCode = httpURLConnection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            setLastError(httpURLConnection.responseMessage, responseCode)
            return null
        }
        val response = httpURLConnection.inputStream.bufferedReader()
        return response.readText()
    }

    open fun getTrustAllCertificates(): Boolean = false

    private fun httpRequest(url: String, header: MutableMap<String, String>, postData: String? = null): String? {
        val urlConnection = URL(url).openConnection()
        if(getTrustAllCertificates() && urlConnection is HttpsURLConnection) {
            trustAllCertificates(urlConnection)
        }

        val httpURLConnection = urlConnection as HttpURLConnection
        header.forEach {
            httpURLConnection.setRequestProperty(it.key, it.value)
        }
        httpURLConnection.doInput = true
        if (postData == null) {
            httpURLConnection.requestMethod = "GET"
            httpURLConnection.doOutput = false
        } else {
            httpURLConnection.requestMethod = "POST"
            httpURLConnection.doOutput = true
            val dataOutputStream = DataOutputStream(httpURLConnection.outputStream)
            val bytes: ByteArray = postData.toByteArray()
            dataOutputStream.write(bytes, 0, bytes.size)
        }

        return checkResponse(httpURLConnection)
    }

    protected fun httpGet(url: String, header: MutableMap<String, String>): String? {
        return httpRequest(url, header)
    }

    protected fun httpPost(url: String, header: MutableMap<String, String>, postData: String): String? {
        return httpRequest(url, header, postData)
    }

    override fun getIntervalMinute(): Long {
        if (interval > 1 && isShortInterval()) {
            Log.d(LOG_ID, "Use short interval of 1 minute.")
            return 1   // retry after a minute
        }
        return interval
    }

    override fun getDelayMs(): Long = delaySec * 1000L

    override fun active(elapsetTimeMinute: Long): Boolean {
        return enabled
    }

    override fun checkPreferenceChanged(sharedPreferences: SharedPreferences, key: String?, context: Context): Boolean {
        if(key == null) {
            enabled = sharedPreferences.getBoolean(enabledKey, false)
            interval = sharedPreferences.getString(Constants.SHARED_PREF_SOURCE_INTERVAL, "1")?.toLong() ?: 1L
            delaySec = sharedPreferences.getInt(Constants.SHARED_PREF_SOURCE_DELAY, 10).toLong()
            return true
        } else {
            var result = false
            when(key) {
                enabledKey -> {
                    if (enabled != sharedPreferences.getBoolean(enabledKey, false)) {
                        enabled = sharedPreferences.getBoolean(enabledKey, false)
                        result = true
                        if (!enabled && source == SourceStateData.lastSource)
                            setState(SourceState.NONE)
                    }
                }
                Constants.SHARED_PREF_SOURCE_INTERVAL -> {
                    if (interval != (sharedPreferences.getString(Constants.SHARED_PREF_SOURCE_INTERVAL, "1")?.toLong() ?: 1L)) {
                        interval = sharedPreferences.getString(Constants.SHARED_PREF_SOURCE_INTERVAL, "1")?.toLong() ?: 1L
                        result = true
                    }
                }
                Constants.SHARED_PREF_SOURCE_DELAY -> {
                    if (delaySec != sharedPreferences.getInt(Constants.SHARED_PREF_SOURCE_DELAY, 10).toLong()) {
                        delaySec = sharedPreferences.getInt(Constants.SHARED_PREF_SOURCE_DELAY, 10).toLong()
                        result = true  // retrigger alarm after delay has changed
                    }
                }
            }
            return result
        }
    }
}