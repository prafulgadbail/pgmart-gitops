import { describe, expect, it } from 'vitest';
import { fakeRazorpay } from '../test/razorpay';
import { payWithRazorpay } from './razorpay';
import type { User } from './types';

const user: User = { id: 7, name: 'Asha', email: 'asha@example.com', phone: null, role: 'CUSTOMER' };
const start = { keyId: 'rzp_test_key', razorpayOrderId: 'order_rzp', amount: 79900 };

describe('Razorpay checkout', () => {
  it('loads the checkout script once, then opens the popup with the order', async () => {
    const paying = payWithRazorpay(start, 'PGM-1', user, '9876543210');
    const script = document.querySelector('script[src*="checkout.razorpay.com"]') as HTMLScriptElement;
    expect(script).toBeTruthy();

    const opened = fakeRazorpay('paid');
    script.onload?.(new Event('load'));

    expect(await paying).toEqual({ paid: true, response: { razorpay_order_id: 'order_rzp', razorpay_payment_id: 'pay_1', razorpay_signature: 'sig' } });
    expect(opened.mock.calls[0][0]).toMatchObject({ key: 'rzp_test_key', amount: 79900, prefill: { contact: '9876543210' } });
  });

  it('a script that cannot load is an error', async () => {
    const paying = payWithRazorpay(start, 'PGM-1', user);
    const scripts = document.querySelectorAll('script[src*="checkout.razorpay.com"]');
    (scripts[scripts.length - 1] as HTMLScriptElement).onerror?.(new Event('error'));

    await expect(paying).rejects.toThrow('Could not load Razorpay');
  });

  it('a closed popup or a failed payment is not paid', async () => {
    fakeRazorpay('closed');
    expect(await payWithRazorpay(start, 'PGM-1', user)).toEqual({ paid: false, reason: 'Payment window closed' });

    fakeRazorpay('failed');
    expect(await payWithRazorpay(start, 'PGM-1', user)).toEqual({ paid: false, reason: 'Card declined' });
  });
});
