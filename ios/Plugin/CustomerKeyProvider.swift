//
// Created by Ibby Hadeed on 2019-11-05.
// Copyright (c) 2019 Max Lynch. All rights reserved.
//

import Foundation
import Stripe

extension StripePlugin: STPCustomerEphemeralKeyProvider {
    public func createCustomerKey(withAPIVersion apiVersion: String, completion: @escaping STPJSONResponseCompletionBlock) {
        completion(self.ephemeralKey! as? [AnyHashable : Any], nil)
    }
}

extension StripePlugin : STPPaymentContextDelegate {
    public func paymentContext(_ paymentContext: STPPaymentContext,
                               didFailToLoadWithError error: Error) {
        NSLog("failed with errors %s", error.localizedDescription)
    }

    public func paymentContextDidChange(_ paymentContext: STPPaymentContext) {
        NSLog("something changed")

        guard let opt = paymentContext.selectedPaymentOption else {
            return
        }

        self.notifyListeners("paymentMethodSelect", data: [
            "label": opt.label,
            "reusable": opt.isReusable,
        ])
    }

    public func paymentContext(_ paymentContext: STPPaymentContext,
                               didCreatePaymentResult paymentResult: STPPaymentResult,
                               completion: @escaping STPPaymentStatusBlock) {
        self.bridge.triggerJSEvent(eventName: "paymentCreate", target: "", data: "")
        if let call = pCall {
            var val: [String:Any] = [:]
            if paymentResult.paymentMethod?.card?.wallet?.type == .applePay {
                val["useApplePay"] = true
            }
            if let pm = paymentResult.paymentMethod {
                val["paymentMethod"] = pmToJSON(m: pm)
            }
            call.success(val)
        }
        completion(STPPaymentStatus.success, nil)
    }

    public func paymentContext(_ paymentContext: STPPaymentContext,
                               didFinishWith status: STPPaymentStatus,
                               error: Error?) {
        switch status {
        case .userCancellation:
            NSLog("User cancelled PaymentContext")
            if let call = pCall {
                call.error("User cancelled PaymentContext")
            }

        case .error:
            NSLog("Error occurred in PaymentContext")

            if error != nil {
                NSLog(error!.localizedDescription)
            }

            if let call = pCall {
                call.error(error?.localizedDescription ?? "unknown error in PaymentContext")
            }

        case .success:
            NSLog("PaymentContext returned success status")
        }

        pCall = nil
    }
}
