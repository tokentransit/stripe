package ca.zyra.capacitor.stripe

import android.content.Context
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.google.android.gms.wallet.*
import com.stripe.android.GooglePayConfig
import org.json.JSONArray
import java.lang.IllegalArgumentException

internal fun getGooglePayEnv(isTest: Boolean): Int {
    return if (isTest) {
        WalletConstants.ENVIRONMENT_TEST
    } else {
        WalletConstants.ENVIRONMENT_PRODUCTION
    }
}

internal fun googlePayPaymentsClient(context: Context, env: Int): PaymentsClient {
    return Wallet.getPaymentsClient(
            context,
            Wallet.WalletOptions.Builder()
                    .setEnvironment(env)
                    .build()
    )
}

internal fun googlePayDefaultCardPaymentMethod(): JSObject {
    return JSObject()
            .put("type", "CARD")
            .put(
                    "parameters",
                    JSObject()
                            .put("allowedAuthMethods", JSONArray()
                                    .put("PAN_ONLY")
                                    .put("CRYPTOGRAM_3DS"))
                            .put("allowedCardNetworks",
                                    JSArray()
                                            .put("AMEX")
                                            .put("DISCOVER")
                                            .put("JCB")
                                            .put("MASTERCARD")
                                            .put("VISA"))
                            .put("billingAddressRequired", false)
            )
}

internal fun googlePayReadyRequest(allowedPaymentMethods: JSArray? = null): IsReadyToPayRequest {
    val pms = allowedPaymentMethods ?: JSArray().put(googlePayDefaultCardPaymentMethod())

    val isReadyToPayRequestJson = JSObject()
    isReadyToPayRequestJson.put("apiVersion", 2)
    isReadyToPayRequestJson.put("apiVersionMinor", 0)
    isReadyToPayRequestJson.put("allowedPaymentMethods", pms)

    return IsReadyToPayRequest.fromJson(isReadyToPayRequestJson.toString())
}

internal fun googlePayDataRequest(publishableKey: String, paymentDataRequest: JSObject): PaymentDataRequest {
    if (!paymentDataRequest.has("transactionInfo")) {
        throw IllegalArgumentException("paymentDataRequest must contain transactionInfo")
    }
    if (!paymentDataRequest.has("merchantInfo")) {
        throw IllegalArgumentException("paymentDataRequest must contain merchantInfo")
    }

    paymentDataRequest
            .put("apiVersion", 2)
            .put("apiVersionMinor", 0)

    if (!paymentDataRequest.has("allowedPaymentMethods")) {
        val tokenizationSpec = GooglePayConfig(publishableKey).tokenizationSpecification
        val cardPaymentMethod = googlePayDefaultCardPaymentMethod()
                .put("tokenizationSpecification", tokenizationSpec)
        paymentDataRequest.put("allowedPaymentMethods",
                        JSONArray().put(cardPaymentMethod))
    }
    if (!paymentDataRequest.has("emailRequired")) {
        paymentDataRequest.put("emailRequired", false)
    }

    return PaymentDataRequest.fromJson(paymentDataRequest.toString())
}
