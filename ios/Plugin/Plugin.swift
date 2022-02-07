import Foundation
import Capacitor
import Stripe
import PassKit

@objc(StripePlugin)
public class StripePlugin: CAPPlugin, STPCustomerEphemeralKeyProvider, STPPaymentContextDelegate {
    private var customerCtx: STPCustomerContext?
    private var customerKeyCallback: CAPPluginCall?
    private var customerKeyCompletion: STPJSONResponseCompletionBlock?

    private var paymentCtx: STPPaymentContext?
    private var paymentCtxFailedToLoadCallback: CAPPluginCall?
    private var paymentContextCreatedPaymentResultCallback: CAPPluginCall?
    private var paymentContextCreatedPaymentResultCompletion: STPPaymentStatusBlock?
    private var paymentContextDidChangeCallback: CAPPluginCall?
    private var paymentDidFinishCallback: CAPPluginCall?

    private var requestPaymentCallback: CAPPluginCall?

    enum StripePluginError: Error {
        case missingArgument
        case noCustomerKeyCallbackSet
        case clientError(error: String)
    }

    @objc func setPublishableKey(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            if let key = call.getString("key") {
                StripeAPI.defaultPublishableKey = key
            }
        }
    }

    // MARK: Customer Context

    func resetCustomerContext() -> STPCustomerContext {
        customerCtx?.clearCache()
        let ctx = STPCustomerContext(keyProvider: self)
        customerCtx = ctx
        return ctx
    }

    func getCustomerContext() -> STPCustomerContext {
        guard let ctx = customerCtx else {
            return resetCustomerContext()
        }
        return ctx
    }

    @objc(createCustomerKeyWithAPIVersion:completion:) public func createCustomerKey(
        withAPIVersion apiVersion: String, completion: @escaping STPJSONResponseCompletionBlock) {
        guard let customerKeyCallback = customerKeyCallback, let callbackId = customerKeyCallback.callbackId else {
            completion(nil, StripePluginError.noCustomerKeyCallbackSet)
            return
        }

        customerKeyCompletion = completion

        customerKeyCallback.resolve([
            "apiVersion": apiVersion,
            "callbackId": callbackId
        ])
    }

    // setCustomerKeyCallback sets the plugin to call the callback repeatedly when the Stripe library needs to refresh the customer key. In this callback, 
    @objc func setCustomerKeyCallback(_ call: CAPPluginCall) {
        // If we're replacing an existing call, release the existing call first.
        if let customerKeyCallback = customerKeyCallback, let bridge = bridge {
            bridge.releaseCall(customerKeyCallback)
        }
        call.isSaved = true
        customerKeyCallback = call
    }

    // customerKeyCompleted updates the Stripe library with the server response ephemeral key. It requires the following parameters:
    //   - "callbackId": string id sent in the callback of setCustomerEphemeralKeyProvider
    // and one of:
    //   - "response": parsed JSON object of the server response
    //   - "error": string error message
    @objc func customerKeyCompleted(_ call: CAPPluginCall) {
        guard let callbackId = call.getString("callbackId") else {
            call.reject("you must provide a callbackId")
            return
        }

        guard let customerKeyCallback = customerKeyCallback, customerKeyCallback.callbackId == callbackId else {
            call.reject("you must provide the same callbackId as the enclosing call to setCustomerKeyCallback")
            return
        }

        guard let customerKeyCallbackCompletion = customerKeyCompletion else {
            call.reject("you must call setCustomerKeyResponse inside an enclosing setCustomerKeyCallback callback")
            return
        }

        guard let response = call.getObject("response") else {
            guard let error = call.getString("error") else {
                call.reject("you must provide either a response or an error")
                customerKeyCallbackCompletion(nil, StripePluginError.missingArgument)
                return
            }

            customerKeyCallbackCompletion(nil, StripePluginError.clientError(error: error))
            call.resolve()
            return
        }

        customerKeyCallbackCompletion(response, nil)
        call.resolve()
    }

    // MARK: Payment Context

    // setPaymentConfiguration updates the shared payment configuration. It accepts the following:
    //   - googlePayEnabled: bool, not applicable on iOS
    //   - applePayEnabled: bool
    //   - fpxEnabled: bool
    //   - requiredBillingAddressFields: string containing one of: "none", "postalCode", "zip", "full", "name"
    //   - companyName: string
    //   - appleMerchantIdentifier: string
    //   - canDeletePaymentOptions: bool
    //   - cardScanningEnabled: bool
    @objc func setPaymentConfiguration(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            let cfg = STPPaymentConfiguration.shared

            if let applePayEnabled = call.getBool("applePayEnabled") {
                cfg.applePayEnabled = applePayEnabled
            }
            if let fpxEnabled = call.getBool("fpxEnabled") {
                cfg.fpxEnabled = fpxEnabled
            }
            if let requiredBillingAddressFields = call.getString("requiredBillingAddressFields") {
                switch requiredBillingAddressFields {
                case "none":
                    cfg.requiredBillingAddressFields = .none
                case "postalCode", "zip":
                    cfg.requiredBillingAddressFields = .postalCode
                case "full":
                    cfg.requiredBillingAddressFields = .full
                case "name":
                    cfg.requiredBillingAddressFields = .name
                default:
                    break
                }
            }
            if let companyName = call.getString("companyName") {
                cfg.companyName = companyName
            }
            if let appleMerchantIdentifier = call.getString("appleMerchantIdentifier") {
                cfg.appleMerchantIdentifier = appleMerchantIdentifier
            }
            if let canDeletePaymentOptions = call.getBool("canDeletePaymentOptions") {
                cfg.canDeletePaymentOptions = canDeletePaymentOptions
            }
            if let cardScanningEnabled = call.getBool("cardScanningEnabled") {
                cfg.cardScanningEnabled = cardScanningEnabled
            }
            call.resolve()
        }
    }

    // updatePaymentContext creates or updates the payment context, which must be created before requestPayment is called. Takes either no parameters, or amount and currency. If amount and currency are set, the payment context's amount and currency will be updated.
    @objc func updatePaymentContext(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            let ctx = self.paymentCtx ?? {
                let newCtx = STPPaymentContext(customerContext: self.getCustomerContext())
                assert(self.bridge != nil, "bridge is null?!")
                assert(self.bridge?.viewController != nil, "viewController was null?!")
                newCtx.hostViewController = self.bridge?.viewController
                newCtx.delegate = self
                return newCtx
            }()
            self.paymentCtx = ctx
            if let amount = call.getInt("amount"), let currency = call.getString("currency") {
                ctx.paymentAmount = amount
                ctx.paymentCurrency = currency
            } else {
                ctx.paymentAmount = 0
                ctx.paymentCurrency = ""
            }
        }
    }

    @objc func setPaymentContextFailedToLoadCallback(_ call: CAPPluginCall) {
        // If we're replacing an existing call, release the existing call first.
        if let paymentCtxFailedToLoadCallback = paymentCtxFailedToLoadCallback, let bridge = bridge {
            bridge.releaseCall(paymentCtxFailedToLoadCallback)
        }
        call.isSaved = true
        paymentCtxFailedToLoadCallback = call
    }

    @objc func setPaymentContextCreatedPaymentResultCallback(_ call: CAPPluginCall) {
        // If we're replacing an existing call, release the existing call first.
        if let paymentContextCreatedPaymentResultCallback = paymentContextCreatedPaymentResultCallback, let bridge = bridge {
            bridge.releaseCall(paymentContextCreatedPaymentResultCallback)
        }
        call.isSaved = true
        paymentContextCreatedPaymentResultCallback = call
    }

    @objc func setPaymentContextDidChangeCallback(_ call: CAPPluginCall) {
        if let paymentContextDidChangeCallback = paymentContextDidChangeCallback {
            bridge?.releaseCall(paymentContextDidChangeCallback)
        }
        call.isSaved = true
        paymentContextDidChangeCallback = call
    }

    @objc func requestPayment(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            if let requestPaymentCallback = self.requestPaymentCallback {
                self.bridge?.releaseCall(requestPaymentCallback)
            }

            self.requestPaymentCallback = call
            self.paymentCtx?.requestPayment()
        }
    }

    @objc func showPaymentOptions(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            self.paymentCtx?.presentPaymentOptionsViewController()
        }
    }

    // currentPaymentOption returns an object:
    //   - loading: true (if loading), no other keys will be set
    //   - label: string ("Apple Pay" or "Visa 4242")
    //   - imageDataUrl: string, image encoded as base64 image data usable by a browser as an img src
    @objc func currentPaymentOption(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            guard let paymentCtx = self.paymentCtx else {
                call.reject("no payment context")
                return
            }

            var result: [String:Any] = [:]
            if paymentCtx.loading {
                result["loading"] = true
            } else {
                if let selectedPaymentOption = paymentCtx.selectedPaymentOption {
                    result["label"] = selectedPaymentOption.label
                    if let png = selectedPaymentOption.image.pngData() {
                        result["imageDataUrl"] = "data:image/png;base64," + png.base64EncodedString()
                    }
                }
            }

            call.resolve(result)
        }
    }

    // paymentContextCreatedPaymentResultCompleted updates the Stripe library with the server response to the payment result callback. It requires the following parameters:
    //   - "callbackId": string id sent in the callback of setPaymentContextCreatedPaymentResultCallback
    // and optionally:
    //   - "error": string error message, if an error occurred. success is assumed otherwise.
    @objc func paymentContextCreatedPaymentResultCompleted(_ call: CAPPluginCall) {
        guard let paymentContextCreatedPaymentResultCompletion = paymentContextCreatedPaymentResultCompletion else {
            call.reject("you must call paymentContextCreatedPaymentResultCompleted inside an enclosing setPaymentContextCreatedPaymentResultCallback callback")
            return
        }

        guard let callbackId = call.getString("callbackId") else {
            call.reject("you must provide a callbackId")
            paymentContextCreatedPaymentResultCompletion(.error, StripePluginError.clientError(error: "you must provide a callbackId"))
            return
        }

        guard let paymentContextCreatedPaymentResultCallback = paymentContextCreatedPaymentResultCallback, paymentContextCreatedPaymentResultCallback.callbackId == callbackId else {
            call.reject("you must provide the same callbackId as the enclosing call to setPaymentContextCreatedPaymentResultCallback")
            paymentContextCreatedPaymentResultCompletion(.error, StripePluginError.clientError(error: "you must provide the same callbackId as the enclosing call to setPaymentContextCreatedPaymentResultCallback"))
            return
        }

        if let error = call.getString("error") {
            call.reject("you must provide either a response or an error")
            paymentContextCreatedPaymentResultCompletion(.error, StripePluginError.clientError(error: error))
            return
        }

        paymentContextCreatedPaymentResultCompletion(.success, nil)
        call.resolve()
    }

    @objc public func paymentContext(_ paymentContext: STPPaymentContext, didFailToLoadWithError error: Error) {
        if let paymentCtxFailedToLoadCallback = paymentCtxFailedToLoadCallback {
            paymentCtxFailedToLoadCallback.resolve(["error":error.localizedDescription])
        }
    }

    @objc public func paymentContext(_ paymentContext: STPPaymentContext, didCreatePaymentResult paymentResult: STPPaymentResult, completion: @escaping STPPaymentStatusBlock) {
        guard let paymentContextCreatedPaymentResultCallback = self.paymentContextCreatedPaymentResultCallback else {
            return
        }

        var result: [String: Any] = [:]
        if let callbackId = paymentContextCreatedPaymentResultCallback.callbackId {
            result["callbackId"] = callbackId
        }
        if let paymentMethod = paymentResult.paymentMethod {
            result["paymentMethod"] = pmToJSON(m: paymentMethod)
        }

        self.paymentContextCreatedPaymentResultCompletion = completion
        paymentContextCreatedPaymentResultCallback.resolve(result)
    }

    @objc public func paymentContext(_ paymentContext: STPPaymentContext, didFinishWith status: STPPaymentStatus, error: Error?) {
        guard let requestPaymentCallback = self.requestPaymentCallback else {
            return
        }

        switch status {
        case .error:
            requestPaymentCallback.reject(error?.localizedDescription ?? "error")
        case .userCancellation:
            requestPaymentCallback.resolve(["userCancellation": true])
        case .success:
            requestPaymentCallback.resolve([:])
        }
    }

    @objc public func paymentContextDidChange(_ paymentContext: STPPaymentContext) {
        guard let paymentContextDidChangeCallback = paymentContextDidChangeCallback else {
            return
        }

        paymentContextDidChangeCallback.resolve()
    }

    @objc func clearContext(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            self.customerCtx = nil
            self.paymentCtx = nil
            call.resolve()
        }
    }
}
