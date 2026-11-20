import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { CUSTOMER, order } from '../../test/backend';
import { fakeRazorpay } from '../../test/razorpay';
import { openStore } from '../../test/render';

describe('your orders', () => {
  it('no orders yet', async () => {
    openStore('/orders', { as: CUSTOMER });
    expect(await screen.findByText(/No orders yet/)).toBeTruthy();
  });

  it('orders show status, tracking, details, and can be cancelled after a confirmation', async () => {
    const { backend, user } = openStore('/orders', {
      as: CUSTOMER,
      before: (b) => {
        b.orders = [
          order({ id: 'order-1', number: 'PGM-100001', status: 'PACKED', delivery: 4000 }),
          order({ id: 'order-2', number: 'PGM-100002', status: 'DELIVERED', paymentStatus: 'PAID' }),
          order({ id: 'order-3', number: 'PGM-100003', status: 'CANCELLED', paymentMethod: 'RAZORPAY', paymentStatus: 'REFUNDED' }),
        ];
      },
    });

    expect(await screen.findByText('Order PGM-100001')).toBeTruthy();
    expect(screen.getByText('Packed. A rider is being assigned.')).toBeTruthy();
    expect(screen.getByText('Delivered. Enjoy your books!')).toBeTruthy();
    expect(screen.getByText('This order was cancelled.')).toBeTruthy();
    expect(screen.getAllByText('₹40').length).toBeGreaterThan(0);

    await user.click(screen.getByRole('button', { name: 'Cancel order' }));
    await user.click(screen.getByRole('button', { name: 'Yes, cancel this order' }));
    await waitFor(() => expect(backend.orders[0].status).toBe('CANCELLED'));
    expect(screen.getByRole('status').textContent).toBe('Order PGM-100001 cancelled');
  });

  it('an order out for delivery shows the moving rider and the cash reminder', async () => {
    openStore('/orders', { as: CUSTOMER, before: (b) => (b.orders = [order({ id: 'o', number: 'PGM-1', status: 'OUT_FOR_DELIVERY' })]) });

    expect(await screen.findByText(/Please keep the cash ready/)).toBeTruthy();
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.getByRole('img', { name: 'Delivery route' })).toBeTruthy();
  });

  it('an order waiting for payment is paid from the list', async () => {
    fakeRazorpay('paid');
    const { backend, user } = openStore('/orders', {
      as: CUSTOMER,
      before: (b) => (b.orders = [order({ id: 'o-pay', number: 'PGM-200', status: 'PAYMENT_PENDING', paymentMethod: 'RAZORPAY', paymentStatus: 'PENDING' })]),
    });

    await user.click(await screen.findByRole('button', { name: 'Pay ₹1,398 now' }));
    await waitFor(() => expect(backend.orders[0].paymentStatus).toBe('PAID'));
    expect(screen.getByRole('status').textContent).toBe('Payment received for PGM-200');
  });

  it('a failed payment and a failed cancel are explained', async () => {
    fakeRazorpay('failed');
    const { backend, user } = openStore('/orders', {
      as: CUSTOMER,
      before: (b) => (b.orders = [order({ id: 'o-f', number: 'PGM-300', status: 'PAYMENT_PENDING', paymentMethod: 'RAZORPAY', paymentStatus: 'PENDING' })]),
    });

    await user.click(await screen.findByRole('button', { name: /Pay .* now/ }));
    await waitFor(() => expect(screen.getByRole('status').textContent).toBe('Card declined'));

    backend.failures['POST /api/orders/o-f/cancel'] = { status: 409, code: 'TOO_LATE', message: 'Already on its way' };
    await user.click(screen.getByRole('button', { name: 'Cancel order' }));
    await user.click(screen.getByRole('button', { name: 'Yes, cancel this order' }));
    await waitFor(() => expect(screen.getByRole('status').textContent).toBe('Already on its way'));
  });

  it('orders that cannot be loaded are reported', async () => {
    openStore('/orders', {
      as: CUSTOMER,
      before: (b) => (b.failures['GET /api/orders'] = { status: 503, code: 'DOWN', message: 'Orders are down' }),
    });

    await waitFor(() => expect(screen.getByRole('status').textContent).toBe('Orders are down'));
  });
});
