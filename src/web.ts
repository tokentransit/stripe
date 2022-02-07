import { WebPlugin } from '@capacitor/core';
import {
  AccountParams,
  ApplePayOptions,
  ApplePayResponse,
  AvailabilityResponse,
  BankAccountTokenRequest,
  BankAccountTokenResponse,
  CallbackID,
  CardBrand,
  CardBrandResponse,
  CardTokenRequest,
  CardTokenResponse,
  ConfirmPaymentIntentOptions,
  ConfirmPaymentIntentResponse,
  ConfirmSetupIntentOptions,
  ConfirmSetupIntentResponse,
  CreatePiiTokenOptions,
  CreateSourceTokenOptions,
  CurrentPaymentOption,
  CustomerPaymentMethodsResponse,
  FinalizeApplePayTransactionOptions,
  GooglePayOptions,
  GooglePayResponse,
  IdentifyCardBrandOptions,
  PaymentConfiguration,
  PaymentMethod,
  SetPublishableKeyOptions,
  StripePlugin,
  TokenResponse,
  ValidateCardNumberOptions,
  ValidateCVCOptions,
  ValidateExpiryDateOptions,
  ValidityResponse,
} from './definitions';
import { ConfirmCardPaymentData, Stripe } from '@stripe/stripe-js'

function flatten(json: any, prefix?: string, omit?: string[]): any {
  let obj: any = {};

  for (const prop of Object.keys(json)) {
    if (typeof json[prop] !== 'undefined' && json[prop] !== null && (!Array.isArray(omit) || !omit.includes(prop))) {
      if (typeof json[prop] === 'object') {
        obj = {
          ...obj,
          ...flatten(json[prop], prefix ? `${prefix}[${prop}]` : prop),
        };
      } else {
        const key = prefix ? `${prefix}[${prop}]` : prop;
        obj[key] = json[prop];
      }
    }
  }

  return obj;
}

function stringify(json: any): string {
  let str = '';
  json = flatten(json);

  for (const prop of Object.keys(json)) {
    const key = encodeURIComponent(prop);
    const val = encodeURIComponent(json[prop]);
    str += `${key}=${val}&`;
  }

  return str;
}

function formBody(json: any, prefix?: string, omit?: string[]): string {
  json = flatten(json, prefix, omit);
  return stringify(json);
}

async function _callStripeAPI(fetchUrl: string, fetchOpts: RequestInit) {
  const res = await fetch(fetchUrl, fetchOpts);

  let parsed;

  try {
    parsed = await res.json();
  } catch (e) {
    parsed = await res.text();
  }

  if (res.ok) {
    return parsed;
  } else {
    throw parsed && parsed.error && parsed.error.message ? parsed.error.message : parsed;
  }
}

async function _stripePost(path: string, body: string, key: string, extraHeaders?: any) {
  extraHeaders = extraHeaders || {};

  return _callStripeAPI(`https://api.stripe.com${path}`, {
    body: body,
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      'Accept': 'application/json',
      'Authorization': `Bearer ${key}`,
      'Stripe-version': '2020-03-02',
      ...extraHeaders,
    },
  });
}

async function _stripeGet(path: string, key: string, extraHeaders?: any) {
  extraHeaders = extraHeaders || {};

  return _callStripeAPI(`https://api.stripe.com${path}`, {
    method: 'GET',
    headers: {
      'Accept': 'application/json',
      'Authorization': `Bearer ${key}`,
      'Stripe-version': '2020-03-02',
      ...extraHeaders,
    },
  });
}

export class StripePluginWeb extends WebPlugin implements StripePlugin {
  private publishableKey: string;
  private stripe: Stripe;

  constructor() {
    super({
      name: 'Stripe',
      platforms: ['web'],
    });
  }

  async setPublishableKey(opts: SetPublishableKeyOptions): Promise<void> {
    if (typeof opts.key !== 'string' || opts.key.trim().length === 0) {
      throw new Error('you must provide a valid key');
    }

    const scriptEl: HTMLScriptElement = document.createElement('script');
    scriptEl.src = 'https://js.stripe.com/v3/';
    document.body.appendChild(scriptEl);
    this.publishableKey = opts.key;

    return new Promise((resolve, reject) => {
      scriptEl.addEventListener('error', (ev: ErrorEvent) => {
        document.body.removeChild(scriptEl);
        reject('Failed to load Stripe JS: ' + ev.message);
      }, { once: true });

      scriptEl.addEventListener('load', () => {
        try {
          this.stripe = new (window as any).Stripe(opts.key);
          resolve();
        } catch (err) {
          document.body.removeChild(scriptEl);
          reject(err);
        }
      }, { once: true });
    });
  }

  async setCustomerKeyCallback(cb: (data:{apiVersion:string, callbackId:CallbackID}) => void): Promise<CallbackID> {
    return ""
  }

  async customerKeyCompleted(opts:{callbackId:CallbackID, response:any}): Promise<void> {
    return;
  }

  async updatePaymentContext(opts:{amount?:number,currency?:string}): Promise<void> {
    return;
  }

  async setPaymentConfiguration(opts:PaymentConfiguration): Promise<void> {
    return;
  }

  async setPaymentContextFailedToLoadCallback(cb: (data:{error:string}) => void): Promise<CallbackID> {
    return "";
  }

  async setPaymentContextCreatedPaymentResultCallback(cb: (data:{paymentMethod:PaymentMethod}) => void): Promise<CallbackID> {
    return "";
  }

  async paymentContextCreatedPaymentResultCompleted(opts:{callbackId:CallbackID, error?:string}): Promise<void> {
    return;
  }

  async setPaymentContextDidChangeCallback(cb: () => void): Promise<CallbackID> {
    return;
  }

  async currentPaymentOption(): Promise<CurrentPaymentOption> {
    return {
      loading: true
    }
  }

  async requestPayment(): Promise<void> {
    return;
  }

  async showPaymentOptions(): Promise<void> {
    return;
  }

  async clearContext(): Promise<void> {
    return;
  }

  async isApplePayAvailable(): Promise<AvailabilityResponse> {
    return { available: false };
  }

  async isGooglePayAvailable(): Promise<AvailabilityResponse> {
    return { available: false };
  }

  async validateCardNumber(opts: ValidateCardNumberOptions): Promise<ValidityResponse> {
    return {
      valid: opts.number.length > 0,
    };
  }

  async validateExpiryDate(opts: ValidateExpiryDateOptions): Promise<ValidityResponse> {
    let { exp_month, exp_year } = opts;

    if (exp_month < 1 || exp_month > 12) {
      return {
        valid: false,
      };
    }

    if (String(exp_year).length === 2) {
      exp_year = parseInt('20' + String(exp_year));
    }

    const currentYear = new Date().getFullYear();

    if (exp_year > currentYear) {
      return {
        valid: true,
      };
    } else if (exp_year === currentYear && exp_month >= (new Date().getMonth() + 1)) {
      return {
        valid: true,
      };
    } else {
      return {
        valid: false,
      };
    }
  }

  async validateCVC(opts: ValidateCVCOptions): Promise<ValidityResponse> {
    if (typeof opts.cvc !== 'string') {
      return { valid: false };
    }

    const len = opts.cvc.trim().length;

    return {
      valid: len > 0 && len < 4,
    };
  }

  async identifyCardBrand(opts: IdentifyCardBrandOptions): Promise<CardBrandResponse> {
    return {
      brand: CardBrand.UNKNOWN,
    };
  }

  addCustomerSource(opts: { sourceId: string; type?: string }): Promise<CustomerPaymentMethodsResponse> {
    return this.cs.addSrc(opts.sourceId);
  }

  customerPaymentMethods(): Promise<CustomerPaymentMethodsResponse> {
    return this.cs.listPm();
  }

  deleteCustomerSource(opts: { sourceId: string }): Promise<CustomerPaymentMethodsResponse> {
    return undefined;
  }

  private cs: CustomerSession;

  async initCustomerSession(opts: any | { id: string; object: 'ephemeral_key'; associated_objects: Array<{ type: 'customer'; id: string }>; created: number; expires: number; livemode: boolean; secret: string }): Promise<void> {
    this.cs = new CustomerSession(opts);
  }

  setCustomerDefaultSource(opts: { sourceId: string; type?: string }): Promise<CustomerPaymentMethodsResponse> {
    return this.cs.setDefaultSrc(opts.sourceId);
  }
}

class CustomerSession {
  private readonly customerId: string;

  constructor(private key: any) {
    if (!key.secret || !Array.isArray(key.associated_objects) || !key.associated_objects.length || !key.associated_objects[0].id) {
      throw new Error('you must provide a valid configuration');
    }

    this.customerId = key.associated_objects[0].id;
  }

  async listPm(): Promise<CustomerPaymentMethodsResponse> {
    const res = await _stripeGet(`/v1/customers/${this.customerId}`, this.key.secret);

    return {
      paymentMethods: res.sources.data,
    };
  }

  async addSrc(id: string): Promise<CustomerPaymentMethodsResponse> {
    await _stripePost('/v1/customers/' + this.customerId, formBody({
      source: id,
    }), this.key.secret);

    return this.listPm();
  }


  async setDefaultSrc(id: string): Promise<CustomerPaymentMethodsResponse> {
    await _stripePost('/v1/customers/' + this.customerId, formBody({
      default_source: id,
    }), this.key.secret);

    return await this.listPm();
  }
}
