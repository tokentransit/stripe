package ca.zyra.capacitor.stripe

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.ComponentActivity
import com.getcapacitor.*
import com.stripe.android.*
import com.stripe.android.view.BillingAddressFields
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import com.stripe.android.Stripe as StripeInstance


@NativePlugin(requestCodes = [9972, 50000, 50001, 6000])
class Stripe : Plugin(), EphemeralKeyProvider, PaymentSession.PaymentSessionListener {
    private lateinit var stripeInstance: StripeInstance
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
                .setShouldShowGooglePay(googlePayEnabled)
                .setBillingAddressFields(requiredBillingAddressFields)
                .setShippingInfoRequired(false)
                .setShippingMethodsRequired(false)
                .setCanDeletePaymentMethods(canDeletePaymentOptions)
                .build()
        }
    }

    @PluginMethod(returnType = PluginMethod.RETURN_NONE)
    fun setPublishableKey(call: PluginCall) {
        GlobalScope.launch(context = Dispatchers.Main) {
            try {
                Log.i(TAG, "setPublishableKey")

                val key = call.getString("key")

                if (key == null || key == "") {
                    call.error("you must provide a valid key")
                    return@launch
                }

                stripeInstance = StripeInstance(context, key)
                publishableKey = key
                isTest = key.contains("test")
                call.success()
            } catch (e: Exception) {
                call.error("unable to set publishable key: ${e.localizedMessage}", e)
            }
        }
    }

    private fun resetCustomerContext(context: Context): CustomerSession? {
        Log.i(TAG, "resetCustomerContext")

        PaymentConfiguration.init(context, publishableKey)

        CustomerSession.endCustomerSession()
        CustomerSession.initCustomerSession(context, this, true)
        customerSession = CustomerSession.getInstance()

        return customerSession
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

                call.save()
                customerKeyCallback = call
            } catch (e: Exception) {
                call.error("Unable to set customer key callback: ${e.localizedMessage}", e)
            }
        }
    }

    @PluginMethod(returnType = PluginMethod.RETURN_NONE)
    fun customerKeyCompleted(call: PluginCall) {
        GlobalScope.launch(context = Dispatchers.Main) {
            try {
                Log.i(TAG, "customerKeyCompleted")

                val listener = customerKeyListener ?: run {
                    call.error("customerKeyListener must be set")
                    return@launch
                }

                val callbackId = call.data.getString("callbackId", null)
                if (callbackId == null) {
                    call.error("callbackId must not be empty")
                    return@launch
                }

                val callback = customerKeyCallback ?: run {
                    call.error("customerKeyCallback must not be null")
                    return@launch
                }

                if (callback.callbackId != callbackId) {
                    call.error("customerKeyCallback callbackId must be equal to callbackId")
                    return@launch
                }

                val error = call.data.getString("error", null)
                if (error != null) {
                    listener.onKeyUpdateFailure(400, error)
                    call.success()
                }

                listener.onKeyUpdate(call.data.getJSObject("response").toString())
                call.success()
            } catch (e: Exception) {
                call.error("Unable to call customer key completed: ${e.localizedMessage}", e)
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
                call.success()
            } catch (e: Exception) {
                call.error("Unable to set payment configuration: ${e.localizedMessage}", e)
            }
        }
    }

    @PluginMethod(returnType = PluginMethod.RETURN_NONE)
    fun updatePaymentContext(call: PluginCall) {
        GlobalScope.launch(context = Dispatchers.Main) {
            try {
                Log.i(TAG, "updatePaymentContext")

                if (customerSession == null) {
                    resetCustomerContext(context)
                }

                if (paymentSession == null) {
                    val result =
                        PaymentSession(activity as ComponentActivity, paymentConfig.config())
                    result.init(this@Stripe)
                    paymentSession = result
                }

                val amount = call.data.optLong("amount", 0L)

                paymentSession?.setCartTotal(amount)
            } catch (e: Exception) {
                call.error("Unable to update payment context: ${e.localizedMessage}", e)
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

                call.save()
                paymentSessionFailedToLoadCallback = call
            } catch (e: Exception) {
                call.error(
                    "Unable to set payment context failed to load callback: ${e.localizedMessage}",
                    e
                )
            }
        }
    }

    @PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
    fun setPaymentContextCreatedPaymentResultCallback(call: PluginCall) {
        GlobalScope.launch(context = Dispatchers.Main) {
            try {
                Log.i(TAG, "setPaymentContextCreatedPaymentResultCallback")

                paymentSessionCreatedPaymentResultCallback?.let {
                    bridge.releaseCall(it)
                }

                call.save()
                paymentSessionCreatedPaymentResultCallback = call
            } catch (e: Exception) {
                call.error(
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

                call.save()
                paymentSessionDidChangeCallback = call
            } catch (e: Exception) {
                call.error(
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
                val paymentMethod = currentPaymentSessionData?.paymentMethod ?: throw Exception("No currentPaymentSessionData paymentMethod")

                paymentSessionCreatedPaymentResultCallback?.let {
                    val result = JSObject()
                    result.put("paymentMethod", pmToJson(paymentMethod))
                    it.success(result)
                }
            } catch (e: Exception) {
                call.error("Unable to request payment: ${e.localizedMessage}", e)
            }
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

                call.success()
            } catch (e: Exception) {
                call.error("Unable to show payment options: ${e.localizedMessage}", e)
            }
        }
    }

    @PluginMethod(returnType = PluginMethod.RETURN_PROMISE)
    fun currentPaymentOption(call: PluginCall) {
        GlobalScope.launch(context = Dispatchers.Main) {
            try {
                Log.i(TAG, "currentPaymentOption")

                val result = JSObject()
                currentPaymentSessionData?.let {
                    val name = it.paymentMethod?.card?.brand?.displayName
                    val last4 = it.paymentMethod?.card?.last4

                    result.put("label", (name ?: "") + " " + (last4 ?: ""))

                    // The icon resource is: it.paymentMethod?.card?.brand?.icon but we don't have a way to load this into the same format (a data url) as iOS.
                }

                call.success(result)
            } catch (e: Exception) {
                call.error("Unable to return current payment option: ${e.localizedMessage}", e)
            }
        }
    }

    @PluginMethod(returnType = PluginMethod.RETURN_NONE)
    fun paymentContextCreatedPaymentResultCompleted(call: PluginCall) {
        GlobalScope.launch(context = Dispatchers.Main) {
            try {
                Log.i(TAG, "paymentContextCreatedPaymentResultCompleted")

                call.success()
            } catch (e: Exception) {
                call.error(
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

                CustomerSession.endCustomerSession()
                customerSession = null
                paymentSession = null

                call.success()
            } catch (e: Exception) {
                call.error("Unable to clear context: ${e.localizedMessage}", e)
            }
        }
    }

    override fun onCommunicatingStateChanged(isCommunicating: Boolean) {
        GlobalScope.launch(context = Dispatchers.Main) {
            Log.i(TAG, "onCommunicatingStateChanged(${isCommunicating})")
        }
    }

    override fun onError(errorCode: Int, errorMessage: String) {
        GlobalScope.launch(context = Dispatchers.Main) {
            Log.w(TAG, "onError($errorCode, $errorMessage)")
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
            Log.i(TAG, "onPaymentSessionDataChanged")

            currentPaymentSessionData = data
            paymentSessionDidChangeCallback?.let {
                it.resolve()
            }
        }
    }

    override fun handleOnActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.handleOnActivityResult(requestCode, resultCode, data)
        paymentSession?.handlePaymentData(requestCode, resultCode, data)
    }
}
