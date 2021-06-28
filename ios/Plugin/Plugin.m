#import <Foundation/Foundation.h>
#import <Capacitor/Capacitor.h>

// Define the plugin using the CAP_PLUGIN Macro, and
// each method the plugin supports using the CAP_PLUGIN_METHOD macro.
CAP_PLUGIN(StripePlugin, "Stripe",
    CAP_PLUGIN_METHOD(setPublishableKey, CAPPluginReturnNone);

    CAP_PLUGIN_METHOD(setCustomerKeyCallback, CAPPluginReturnCallback);
    CAP_PLUGIN_METHOD(customerKeyCompleted, CAPPluginReturnNone);

    CAP_PLUGIN_METHOD(updatePaymentContext, CAPPluginReturnNone);
    CAP_PLUGIN_METHOD(setPaymentConfiguration, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(setPaymentContextFailedToLoadCallback, CAPPluginReturnCallback);
    CAP_PLUGIN_METHOD(setPaymentContextCreatedPaymentResultCallback, CAPPluginReturnCallback);
    CAP_PLUGIN_METHOD(paymentContextCreatedPaymentResultCompleted, CAPPluginReturnNone);
    CAP_PLUGIN_METHOD(setPaymentContextDidChangeCallback, CAPPluginReturnCallback);
    CAP_PLUGIN_METHOD(currentPaymentOption, CAPPluginReturnCallback);

    CAP_PLUGIN_METHOD(requestPayment, CAPPluginReturnNone);
    CAP_PLUGIN_METHOD(showPaymentOptions, CAPPluginReturnNone);

    CAP_PLUGIN_METHOD(currentPaymentMethod, CAPPluginReturnPromise);
)
