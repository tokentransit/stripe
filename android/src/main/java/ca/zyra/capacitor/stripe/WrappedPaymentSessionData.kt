package ca.zyra.capacitor.stripe

import com.stripe.android.PaymentSessionData

/* A little wrapper around Stripe's PaymentSessionData class, since we cannot construct one of
 * those, so we can have a separate field tracking whether the user wants to use google pay,
 * since the Stripe library does not remember that for us (the way it remembers a selected card).
 */
class WrappedPaymentSessionData(
        val useGooglePay: Boolean,
        val paymentSessionData: PaymentSessionData
)