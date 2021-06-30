package ca.zyra.capacitor.stripe

import android.content.Context
import androidx.activity.ComponentActivity
import com.getcapacitor.*
import com.stripe.android.*
import com.stripe.android.view.BillingAddressFields


@NativePlugin(requestCodes = [9972, 50000, 50001, 6000])
class StripePlugin : Plugin(), EphemeralKeyProvider, PaymentSession.PaymentSessionListener {
    private lateinit var stripeInstance: Stripe
    private lateinit var publishableKey: String
    private var isTest = true
    private var customerSession: CustomerSession? = null

    private var customerKeyCallback: PluginCall? = null
    private var customerKeyListener: EphemeralKeyUpdateListener? = null

    private var paymentSession: PaymentSession? = null
    private var currentPaymentSessionData: PaymentSessionData? = null
    private var paymentSessionFailedToLoadCallback: PluginCall? = null
    private var paymentSessionCreatedPaymentResultCallback: PluginCall? = null
    private var paymentSessionDidChangeCallback: PluginCall? = null

    private var requestPaymentCallback: PluginCall? = null

    private var paymentConfiguration = PaymentConfiguration()
    private data class PaymentConfiguration(
        var googlePayEnabled: Boolean = false,
        var fpxEnabled: Boolean = false,
        var requiredBillingAddressFields: BillingAddressFields = BillingAddressFields.Full,
        var companyName: String = "",
        var canDeletePaymentOptions: Boolean = false,
        var cardScanningEnabled: Boolean = false
    ) {
        fun config(): PaymentSessionConfig {
            return PaymentSessionConfig.Builder()
                .setShouldShowGooglePay(googlePayEnabled)
                .setBillingAddressFields(requiredBillingAddressFields)
                .setShippingInfoRequired(false)
                .setShippingMethodsRequired(false)
                .setCanDeletePaymentMethods(canDeletePaymentOptions)
                .build()
        }
    }

    @PluginMethod
    fun setPublishableKey(call: PluginCall) {
        try {
            val key = call.getString("key")

            if (key == null || key == "") {
                call.error("you must provide a valid key")
                return
            }

            stripeInstance = Stripe(context, key)
            publishableKey = key
            isTest = key.contains("test")
            call.success()
        } catch (e: Exception) {
            call.error("unable to set publishable key: " + e.localizedMessage, e)
        }
    }

    fun resetCustomerContext(context: Context): CustomerSession? {
        CustomerSession.endCustomerSession()
        CustomerSession.initCustomerSession(context, this, true)
        customerSession = CustomerSession.getInstance()
        return customerSession
    }

    override fun createEphemeralKey(apiVersion: String, keyUpdateListener: EphemeralKeyUpdateListener) {
        val callback = customerKeyCallback ?: return
        val callbackId = callback.data.get("callbackId") ?: return

        customerKeyListener = keyUpdateListener

        val result = JSObject()
        result.put("apiVersion", apiVersion)
        result.put("callbackId", callbackId)

        callback.resolve(result)
    }

    @PluginMethod
    fun setCustomerKeyCallback(call: PluginCall) {
        customerKeyCallback?.let {
            bridge.releaseCall(it)
        }

        call.save()
        customerKeyCallback = call
    }

    @PluginMethod
    fun setStripeAccount(call: PluginCall) {
        call.success()
    }

    @PluginMethod
    fun customerKeyCompleted(call: PluginCall) {
        val callbackId = call.data.get("callbackId") ?: return
        val callback = customerKeyCallback ?: return

        // customerKeyListener should be called with error for missing values callbackId, response, and if it contains error.

        customerKeyListener?.onKeyUpdate(call.data.getJSObject("response").toString())
        call.success()
    }

    @PluginMethod
    fun setPaymentConfiguration(call: PluginCall) {
        // ignore applePayEnabled, it doesn't make sense on Android.
        call.data.getBool("googlePayEnabled")?.let { paymentConfiguration.googlePayEnabled = it }
        call.data.getBool("fpxEnabled")?.let { paymentConfiguration.fpxEnabled = it }
        call.data.getString("requiredBillingAddressFields")?.let {
            paymentConfiguration.requiredBillingAddressFields = when (it) {
                "none" -> BillingAddressFields.None
                "postalCode" -> BillingAddressFields.PostalCode
                "full" -> BillingAddressFields.Full
                else -> {
                    BillingAddressFields.None
                }
            }
        }
        call.data.getString("companyName")?.let {
            paymentConfiguration.companyName = it
        }
        call.data.getBool("canDeletePaymentOptions")?.let {
            paymentConfiguration.canDeletePaymentOptions = it
        }
        call.data.getBool("cardScanningEnabled")?.let {
            paymentConfiguration.cardScanningEnabled = it
        }
        call.success()
    }

    @PluginMethod
    fun updatePaymentContext(call: PluginCall) {
        val ps = PaymentSession(activity as ComponentActivity, paymentConfiguration.config())
        ps.init(this)
        paymentSession = ps
    }

    @PluginMethod
    fun setPaymentContextFailedToLoadCallback(call: PluginCall) {
        paymentSessionFailedToLoadCallback?.let {
            bridge.releaseCall(it)
        }

        call.save()
        paymentSessionFailedToLoadCallback = call
    }

    @PluginMethod
    fun setPaymentContextCreatedPaymentResultCallback(call: PluginCall) {
        paymentSessionCreatedPaymentResultCallback?.let {
            bridge.releaseCall(it)
        }

        call.save()
        paymentSessionCreatedPaymentResultCallback = call
    }

    @PluginMethod
    fun setPaymentContextDidChangeCallback(call: PluginCall) {
        paymentSessionDidChangeCallback?.let {
            bridge.releaseCall(it)
        }

        call.save()
        paymentSessionDidChangeCallback = call
    }

    @PluginMethod
    fun requestPayment(call: PluginCall) {
        requestPaymentCallback = call
        val paymentMethod = currentPaymentSessionData?.paymentMethod ?: return

        paymentSessionCreatedPaymentResultCallback?.let {
            var result = JSObject()
            result.put("paymentMethod", paymentMethod)
            it.success(result)
        }
    }

    @PluginMethod
    fun showPaymentOptions(call: PluginCall) {
        paymentSession?.presentPaymentMethodSelection()
    }

    @PluginMethod
    fun currentPaymentOption(call: PluginCall) {
        var result = JSObject()
        currentPaymentSessionData?.let {
            val name = it.paymentMethod?.card?.brand?.displayName
            val last4 = it.paymentMethod?.card?.last4

            result.put("label", (name ?: "") + " " + (last4 ?: ""))
        }

        call.success(result)
    }

    @PluginMethod
    fun paymentContextCreatedPaymentResultCompleted(call: PluginCall) {
    }

    @PluginMethod
    fun clearContext(call: PluginCall) {
        customerSession = null
        paymentSession = null
    }

    override fun onCommunicatingStateChanged(isCommunicating: Boolean) {
    }

    override fun onError(errorCode: Int, errorMessage: String) {
        paymentSessionFailedToLoadCallback?.let {
            val result = JSObject()
            result.put("error", errorMessage)
            it.resolve(result)
        }
    }

    override fun onPaymentSessionDataChanged(data: PaymentSessionData) {
        currentPaymentSessionData = data
    }
}
