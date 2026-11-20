import { vi } from 'vitest';

/** Stands in for the Razorpay checkout popup: it pays, is closed, or reports a failed payment. */
export function fakeRazorpay(result: 'paid' | 'closed' | 'failed') {
  const opened = vi.fn();
  window.Razorpay = class {
    private failed?: (r: { error: { description: string } }) => void;
    constructor(private options: Record<string, any>) {}
    on(_event: string, handler: (r: { error: { description: string } }) => void) {
      this.failed = handler;
    }
    open() {
      opened(this.options);
      if (result === 'paid') {
        this.options.handler({ razorpay_order_id: this.options.order_id, razorpay_payment_id: 'pay_1', razorpay_signature: 'sig' });
      } else if (result === 'closed') {
        this.options.modal.ondismiss();
      } else {
        this.failed?.({ error: { description: 'Card declined' } });
      }
    }
  };
  return opened;
}
