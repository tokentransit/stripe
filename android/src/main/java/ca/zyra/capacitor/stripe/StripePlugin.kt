package ca.zyra.capacitor.stripe

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.activity.ComponentActivity
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
import com.google.android.gms.wallet.AutoResolveHelper
import com.google.android.gms.wallet.PaymentData
import com.google.android.gms.wallet.PaymentsClient
import com.stripe.android.*
import com.stripe.android.model.PaymentMethod
import com.stripe.android.model.PaymentMethodCreateParams
import com.stripe.android.view.BillingAddressFields
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine
import com.stripe.android.Stripe as StripeInstance


@CapacitorPlugin(requestCodes = [9972, 50000, 50001, 6000])
class Stripe : Plugin(), EphemeralKeyProvider, PaymentSession.PaymentSessionListener {
    private lateinit var stripeInstance: StripeInstance
    private lateinit var publishableKey: String
    private var isTest = true

    private var customerKeyCallback: PluginCall? = null
    private var customerKeyListener: EphemeralKeyUpdateListener? = null

    private var paymentSession: PaymentSession? = null
    private var currentPaymentSessionData: WrappedPaymentSessionData? = null
    private var paymentSessionFailedToLoadCallback: PluginCall? = null
    private var paymentSessionCreatedPaymentResultCallback: PluginCall? = null
    private var paymentSessionDidChangeCallback: PluginCall? = null
    private var loading: Boolean = false

    private var requestPaymentCallback: PluginCall? = null

    private var totalAmount = 0L

    private var googlePaymentsClient: PaymentsClient? = null
    private var googlePayAvailable: Boolean? = null
    private var googlePayContinuation: Continuation<ActivityResult>? = null

    private data class ActivityResult(val resultCode: Int, val data: Intent?)

    private var paymentConfig = PaymentConfig()
    private data class PaymentConfig(
        var googlePayEnabled: Boolean = false,
        var fpxEnabled: Boolean = false,
        var requiredBillingAddressFields: BillingAddressFields = BillingAddressFields.Full,
        var companyName: String = "",
        var canDeletePaymentOptions: Boolean = false,
        var cardScanningEnabled: Boolean = false
    ) {
        fun config(): PaymentSessionConfig {
            return PaymentSessionConfig.Builder()
                .setShouldPrefetchCustomer(false)
                .setShouldShowGooglePay(googlePayEnabled)
                .setBillingAddressFields(requiredBillingAddressFields)
                .setShippingInfoRequired(false)
                .setShippingMethodsRequired(false)
                .setCanDeletePaymentMethods(canDeletePaymentOptions)
                .build()
        }
    }

    private val PREF_STORE = "capacitor_stripe_plugin"
    private val GPAY_PREF = "use_gpay"

    private lateinit var prefStore: SharedPreferences

    override fun load() {
        prefStore = context.getSharedPreferences(PREF_STORE, Context.MODE_PRIVATE)
    }

    @PluginMethod(returnType = PluginMethod.RETURN_NONE)
    fun setPublishableKey(call: PluginCall) {
        GlobalScope.launch(context = Dispatchers.Main) {
            try {
                Log.i(TAG, "setPublishableKey")

                val key = call.getString("key")

                if (key == null || key == "") {
                    call.reject("you must provide a valid key")
                    return@launch
                }

                stripeInstance = StripeInstance(context, key)
                publishableKey = key
                isTest = key.contains("test")
                call.resolve()
            } catch (e: Exception) {
                call.reject("unable to set publishable key: ${e.localizedMessage}", e)
            }
        }
    }

    private suspend fun resetCustomerContext(context: Context) {
        Log.i(TAG, "resetCustomerContext")

        PaymentConfiguration.init(context, publishableKey)

        CustomerSession.endCustomerSession()
        CustomerSession.initCustomerSession(context, this, true)

        val client = googlePayPaymentsClient(context, getGooglePayEnv(isTest))
        googlePaymentsClient = client
        val isReadyToPayRequest = waitForTask(client.isReadyToPay(googlePayReadyRequest()))
        googlePayAvailable = isReadyToPayRequest.isSuccessful

        totalAmount = 0
    }

    // Implements EphemeralKeyProvider.
    override fun createEphemeralKey(apiVersion: String, keyUpdateListener: EphemeralKeyUpdateListener) {
        GlobalScope.launch(context = Dispatchers.Main) {
            Log.i(TAG, "createEphemeralKey: apiVersion $apiVersion")

            val callback = customerKeyCallback ?: run {
                Log.w(TAG, "customerKeyCallback is null in createEphemeralKey")
                keyUpdateListener.onKeyUpdateFailure(400, "no customerKeyCallback configured")
                return@launch
            }

            customerKeyListener = keyUpdateListener

            val result = JSObject()
            result.put("apiVersion", apiVersion)
            result.put("callbackId", callback.callbackId)

            callback.resolve(result)
        }
    }

    @PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
    fun setCustomerKeyCallback(call: PluginCall) {
        GlobalScope.launch(context = Dispatchers.Main) {
            try {
                Log.i(TAG, "setCustomerKeyCallback")

                customerKeyCallback?.release(bridge)

                call.setKeepAlive(true)
                customerKeyCallback = call
            } catch (e: Exception) {
                call.reject("Unable to set customer key callback: ${e.localizedMessage}", e)
            }
        }
    }

    @PluginMethod(returnType = PluginMethod.RETURN_NONE)
    fun customerKeyCompleted(call: PluginCall) {
        GlobalScope.launch(context = Dispatchers.Main) {
            try {
                Log.i(TAG, "customerKeyCompleted")

                val listener = customerKeyListener ?: run {
                    call.reject("customerKeyListener must be set")
                    return@launch
                }

                val callbackId = call.data.getString("callbackId", null)
                if (callbackId == null) {
                    call.reject("callbackId must not be empty")
                    return@launch
                }

                val callback = customerKeyCallback ?: run {
                    call.reject("customerKeyCallback must not be null")
                    return@launch
                }

                if (callback.callbackId != callbackId) {
                    call.reject("customerKeyCallback callbackId must be equal to callbackId")
                    return@launch
                }

                val error = call.data.getString("error", null)
                if (error != null) {
                    listener.onKeyUpdateFailure(400, error)
                    call.resolve()
                    return@launch
                }

                listener.onKeyUpdate(call.data.getJSObject("response").toString())
                call.resolve()
            } catch (e: Exception) {
                call.reject("Unable to call customer key completed: ${e.localizedMessage}", e)
            }
        }
    }

    @PluginMethod(returnType = PluginMethod.RETURN_PROMISE)
    fun setPaymentConfiguration(call: PluginCall) {
        GlobalScope.launch(context = Dispatchers.Main) {
            try {
                Log.i(TAG, "setPaymentConfiguration")

                // ignore applePayEnabled, it doesn't make sense on Android.
                call.data.getBoolean("googlePayEnabled", null)?.let {
                        paymentConfig.googlePayEnabled = it
                }
                call.data.getBoolean("fpxEnabled", null)?.let {
                    paymentConfig.fpxEnabled = it
                }
                call.data.getString("requiredBillingAddressFields", null)?.let {
                    paymentConfig.requiredBillingAddressFields = when (it) {
                        "none" -> BillingAddressFields.None
                        "postalCode" -> BillingAddressFields.PostalCode
                        "full" -> BillingAddressFields.Full
                        else -> {
                            BillingAddressFields.None
                        }
                    }
                }
                call.data.getString("companyName", null)?.let {
                    paymentConfig.companyName = it
                }
                call.data.getBoolean("canDeletePaymentOptions", null)?.let {
                    paymentConfig.canDeletePaymentOptions = it
                }
                call.data.getBoolean("cardScanningEnabled", null)?.let {
                    paymentConfig.cardScanningEnabled = it
                }
                call.resolve()
            } catch (e: Exception) {
                call.reject("Unable to set payment configuration: ${e.localizedMessage}", e)
            }
        }
    }

    @PluginMethod(returnType = PluginMethod.RETURN_NONE)
    fun updatePaymentContext(call: PluginCall) {
        GlobalScope.launch(context = Dispatchers.Main) {
            try {
                Log.i(TAG, "updatePaymentContext")

                // Ensure that the CustomerSession singleton is configured. (There's no non-exceptional way to check for this in the Stripe library)
                try {
                    CustomerSession.getInstance()
                } catch (e: Exception) {
                    resetCustomerContext(context)
                }

                if (paymentSession == null) {
                    val config = paymentConfig.copy(
                            googlePayEnabled = paymentConfig.googlePayEnabled && (googlePayAvailable ?: false)
                    )
                    val result =
                        PaymentSession(activity as ComponentActivity, config.config())
                    result.init(this@Stripe)
                    paymentSession = result
                }

                val amount = call.data.optLong("amount", 0L)
                totalAmount = amount
                paymentSession?.setCartTotal(amount)
            } catch (e: Exception) {
                call.reject("Unable to update payment context: ${e.localizedMessage}", e)
            }
        }
    }

    @PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
    fun setPaymentContextFailedToLoadCallback(call: PluginCall) {
        GlobalScope.launch(context = Dispatchers.Main) {
            try {
                Log.i(TAG, "setPaymentContextFailedToLoadCallback")

                paymentSessionFailedToLoadCallback?.let {
                    bridge.releaseCall(it)
                }

                call.setKeepAlive(true)
                paymentSessionFailedToLoadCallback = call
            } catch (e: Exception) {
                call.reject(
                    "Unable to set payment context failed to load callback: ${e.localizedMessage}",
                    e
                )
            }
        }
    }

    @PluginMethod(returnType = PluginMethod.RETURN_NONE)
    fun retryLoading(call: PluginCall) {
        Log.i(TAG, "retryLoading. nothing to do on android.")
        call.resolve()
    }

    @PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
    fun setPaymentContextCreatedPaymentResultCallback(call: PluginCall) {
        GlobalScope.launch(context = Dispatchers.Main) {
            try {
                Log.i(TAG, "setPaymentContextCreatedPaymentResultCallback")

                paymentSessionCreatedPaymentResultCallback?.let {
                    bridge.releaseCall(it)
                }

                call.setKeepAlive(true)
                paymentSessionCreatedPaymentResultCallback = call
            } catch (e: Exception) {
                call.reject(
                    "Unable to set payment context created payment result callback: ${e.localizedMessage}",
                    e
                )
            }
        }
    }

    @PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
    fun setPaymentContextDidChangeCallback(call: PluginCall) {
        GlobalScope.launch(context = Dispatchers.Main) {
            try {
                Log.i(TAG, "setPaymentContextDidChangeCallback")

                paymentSessionDidChangeCallback?.let {
                    bridge.releaseCall(it)
                }

                call.setKeepAlive(true)
                paymentSessionDidChangeCallback = call
            } catch (e: Exception) {
                call.reject(
                    "Unable to set payment context did change callback: ${e.localizedMessage}",
                    e
                )
            }
        }
    }

    @PluginMethod(returnType = PluginMethod.RETURN_NONE)
    fun requestPayment(call: PluginCall) {
        GlobalScope.launch(context = Dispatchers.Main) {
            try {
                Log.i(TAG, "requestPayment")

                requestPaymentCallback = call

                val wrappedPaymentSessionData = currentPaymentSessionData ?: throw Exception("No currentPaymentSessionData")
                val paymentMethod = if (wrappedPaymentSessionData.useGooglePay) {
                    payWithGoogle()
                } else {
                    wrappedPaymentSessionData.paymentSessionData.paymentMethod ?: throw Exception("No currentPaymentSessionData paymentMethod")
                }

                paymentSessionCreatedPaymentResultCallback?.let {
                    val result = JSObject()
                    result.put("paymentMethod", paymentMethod?.let { pm -> pmToJson(pm) })
                    it.resolve(result)
                }
            } catch (e: Exception) {
                call.reject("Unable to request payment: ${e.localizedMessage}", e)
            }
        }
    }

    private suspend fun payWithGoogle(): PaymentMethod? {
        val client = googlePaymentsClient ?: throw Exception("No googlePaymentsClient")
        val totalPrice = "${totalAmount / 100}.${totalAmount % 100}"
        val request = JSObject()
                .put("transactionInfo", JSObject()
                        .put("totalPrice", totalPrice)
                        .put("totalPriceStatus", "FINAL")
                        .put("currencyCode", "USD"))
                .put("merchantInfo", JSObject()
                        .put("merchantName", paymentConfig.companyName))
        val activityResult = suspendCoroutine<ActivityResult> { cont ->
            googlePayContinuation = cont
            AutoResolveHelper.resolveTask(
                    client.loadPaymentData(googlePayDataRequest(publishableKey, request)),
                    activity,
                    LOAD_PAYMENT_DATA_REQUEST_CODE
            )
        }
        return when (activityResult.resultCode) {
            Activity.RESULT_OK -> {
                val paymentData = activityResult.data?.let { PaymentData.getFromIntent(it) }
                        ?: throw Exception("Unable to get payment data from Google Pay result")
                val paymentMethodCreateParams = PaymentMethodCreateParams.createFromGooglePay(JSONObject(paymentData.toJson()))
                return waitForStripe { cb -> stripeInstance.createPaymentMethod(paymentMethodCreateParams, callback = cb) }
            }
            Activity.RESULT_CANCELED -> null
            AutoResolveHelper.RESULT_ERROR -> {
                val status = AutoResolveHelper.getStatusFromIntent(activityResult.data)
                throw Exception("Error invoking Google Pay: $status")
            }
            else -> throw Exception("Unexpected result from Google Pay")
        }
    }

    @PluginMethod(returnType = PluginMethod.RETURN_NONE)
    fun showPaymentOptions(call: PluginCall) {
        GlobalScope.launch(context = Dispatchers.Main) {
            try {
                Log.i(TAG, "showPaymentOptions")

                val ps = paymentSession ?: run {
                    Log.w(TAG, "showPaymentOptions paymentSession is null")
                    throw Error("paymentSession is null")
                }

                ps.presentPaymentMethodSelection()

                call.resolve()
            } catch (e: Exception) {
                call.reject("Unable to show payment options: ${e.localizedMessage}", e)
            }
        }
    }

    @PluginMethod(returnType = PluginMethod.RETURN_PROMISE)
    fun currentPaymentOption(call: PluginCall) {
        GlobalScope.launch(context = Dispatchers.Main) {
            try {
                Log.i(TAG, "currentPaymentOption")

                val result = JSObject()
                if (loading) {
                    result.put("loading", true)
                } else {
                    currentPaymentSessionData?.let {
                        val label = if (it.useGooglePay) {
                            "Google Pay"
                        } else {
                            val pm = it.paymentSessionData.paymentMethod
                            val name = pm?.card?.brand?.displayName ?: ""
                            val last4 = pm?.card?.last4 ?: ""
                            "$name $last4"
                        }

                        Log.i(TAG, "Returning currentPaymentSessionData(useGooglePay: ${it.useGooglePay}, paymentMethod: ${it.paymentSessionData.paymentMethod?.id})")

                        result.put("label", label)

                        // The icon resource is: it.paymentMethod?.card?.brand?.icon but we don't have a way to load this into the same format (a data url) as iOS.
                    }
                }

                call.resolve(result)
            } catch (e: Exception) {
                call.reject("Unable to return current payment option: ${e.localizedMessage}", e)
            }
        }
    }

    @PluginMethod(returnType = PluginMethod.RETURN_NONE)
    fun paymentContextCreatedPaymentResultCompleted(call: PluginCall) {
        GlobalScope.launch(context = Dispatchers.Main) {
            try {
                Log.i(TAG, "paymentContextCreatedPaymentResultCompleted")

                call.resolve()
            } catch (e: Exception) {
                call.reject(
                    "Unable to process payment context created payment result completed: ${e.localizedMessage}",
                    e
                )
            }
        }
    }

    @PluginMethod(returnType = PluginMethod.RETURN_NONE)
    fun clearContext(call: PluginCall) {
        GlobalScope.launch(context = Dispatchers.Main) {
            try {
                Log.i(TAG, "clearContext")

                paymentSession?.setCartTotal(0)
                paymentSession?.clearPaymentMethod()
                paymentSession = null
                currentPaymentSessionData = null
                googlePaymentsClient = null
                googlePayAvailable = null
                prefStore.edit().clear().apply()
                CustomerSession.endCustomerSession()

                call.resolve()
            } catch (e: Exception) {
                call.reject("Unable to clear context: ${e.localizedMessage}", e)
            }
        }
    }

    override fun onCommunicatingStateChanged(isCommunicating: Boolean) {
        GlobalScope.launch(context = Dispatchers.Main) {
            Log.i(TAG, "onCommunicatingStateChanged(${isCommunicating})")
            loading = true
        }
    }

    override fun onError(errorCode: Int, errorMessage: String) {
        GlobalScope.launch(context = Dispatchers.Main) {
            Log.w(TAG, "onError($errorCode, $errorMessage)")
            loading = false
            val callback = paymentSessionFailedToLoadCallback ?: run {
                Log.w(
                    TAG,
                    "onError($errorCode, $errorMessage) returned but paymentSessionFailedToLoadCallback is null"
                )
                return@launch
            }

            val result = JSObject()
            result.put("error", errorMessage)
            callback.resolve(result)
        }
    }

    override fun onPaymentSessionDataChanged(data: PaymentSessionData) {
        GlobalScope.launch(context = Dispatchers.Main) {
            loading = false
            Log.i(TAG, "onPaymentSessionDataChanged")

            val useGooglePay = if (!data.useGooglePay && data.paymentMethod == null) {
                // Nothing selected / remembered by Stripe. Fill in our memory of whether the user
                // selected gpay last time.
                prefStore.getBoolean(GPAY_PREF, false)
            } else {
                // User selected something via Stripe, remember whether they want to use gpay (using
                // apply() to write asynchronously).
                prefStore.edit().putBoolean(GPAY_PREF, data.useGooglePay).apply()
                data.useGooglePay
            }
            val it = WrappedPaymentSessionData(useGooglePay, data)
            Log.i(TAG, "Adding currentPaymentSessionData(useGooglePay: ${it.useGooglePay}, paymentMethod: ${it.paymentSessionData.paymentMethod?.id})")
            currentPaymentSessionData = it

            paymentSessionDidChangeCallback?.resolve()
        }
    }

    override fun handleOnActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == LOAD_PAYMENT_DATA_REQUEST_CODE) {
            googlePayContinuation?.resumeWith(Result.success(ActivityResult(resultCode, data)))
        } else {
            super.handleOnActivityResult(requestCode, resultCode, data)
            paymentSession?.handlePaymentData(requestCode, resultCode, data)
        }
    }
}
