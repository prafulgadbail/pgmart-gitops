import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { CUSTOMER } from '../../test/backend';
import { fakeRazorpay } from '../../test/razorpay';
import { openStore } from '../../test/render';

async function fillAddress(user: ReturnType<typeof openStore>['user'], mobile = '9876543210') {
  await user.clear(screen.getByPlaceholderText('Full name'));
  await user.type(screen.getByPlaceholderText('Full name'), 'Asha Kumar');
  await user.type(screen.getByPlaceholderText(/Mobile number/), mobile);
  await user.type(screen.getByPlaceholderText(/House no/), '12 MG Road');
  await user.type(screen.getByPlaceholderText('City'), 'Pune');
  await user.type(screen.getByPlaceholderText('State'), 'Maharashtra');
  await user.type(screen.getByPlaceholderText(/Pincode/), '411001');
}

describe('checkout', () => {
  it('checkout needs a login and a cart', async () => {
    openStore('/checkout');
    expect(await screen.findByRole('heading', { name: 'Login' })).toBeTruthy();
  });

  it('an empty cart has nothing to check out', async () => {
    openStore('/checkout', { as: CUSTOMER });
    expect(await screen.findByText(/Your cart is empty/)).toBeTruthy();
  });

  it('a Cash on Delivery order is placed to a new address, which is saved', async () => {
    const { backend, user } = openStore('/checkout', { as: CUSTOMER, cart: { 500: 1 } });
    await screen.findByRole('heading', { name: 'Delivery address' });
    expect(screen.getByText('₹40')).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Place order (Cash on Delivery)' }));
    expect(screen.getByText('Please fill the full address')).toBeTruthy();

    await fillAddress(user, '98765');
    await user.click(screen.getByRole('button', { name: 'Place order (Cash on Delivery)' }));
    expect(screen.getByText('Enter a 10 digit mobile number')).toBeTruthy();

    await user.type(screen.getByPlaceholderText(/Mobile number/), '43210');
    await user.clear(screen.getByPlaceholderText(/Pincode/));
    await user.type(screen.getByPlaceholderText(/Pincode/), '4110');
    await user.click(screen.getByRole('button', { name: 'Place order (Cash on Delivery)' }));
    expect(screen.getByText('Enter a 6 digit pincode')).toBeTruthy();

    await user.type(screen.getByPlaceholderText(/Pincode/), '01');
    await user.click(screen.getByRole('button', { name: 'Place order (Cash on Delivery)' }));
    expect(await screen.findByRole('heading', { name: 'Order confirmed!' })).toBeTruthy();
    expect(backend.addresses).toHaveLength(1);
    expect(backend.orders[0].paymentMethod).toBe('COD');

    await user.click(screen.getByRole('button', { name: 'Track order' }));
    expect(await screen.findByRole('heading', { name: 'Your orders' })).toBeTruthy();
  });

  it('a saved address is used, and a stock problem is shown', async () => {
    const { backend, user } = openStore('/checkout', {
      as: CUSTOMER,
      cart: { 9: 1 },
      before: (b) => {
        b.addresses = [{ id: 1, fullName: 'Asha Kumar', mobile: '9876543210', line1: '12 MG Road', city: 'Pune',
          state: 'Maharashtra', pincode: '411001', isDefault: true }];
        b.failures['POST /api/orders'] = { status: 409, code: 'OUT_OF_STOCK', message: 'Only 0 left of Kubernetes Field Guide' };
      },
    });

    expect(await screen.findByText(/12 MG Road, Pune/)).toBeTruthy();
    expect(screen.getByText('Free')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Place order (Cash on Delivery)' }));
    expect(await screen.findByText('Only 0 left of Kubernetes Field Guide')).toBeTruthy();

    await user.click(screen.getByRole('radio', { name: /Deliver to a new address/ }));
    expect(screen.getByPlaceholderText('City')).toBeTruthy();
    await user.click(screen.getByRole('checkbox', { name: /Save this address/ }));
    await user.click(screen.getByRole('radio', { name: /12 MG Road/ }));
    await user.click(screen.getByRole('button', { name: 'Place order (Cash on Delivery)' }));
    expect(await screen.findByRole('heading', { name: 'Order confirmed!' })).toBeTruthy();
    expect(backend.orders[0].address.city).toBe('Pune');

    await user.click(screen.getByRole('button', { name: 'Keep shopping' }));
    expect(await screen.findByRole('heading', { name: 'Products' })).toBeTruthy();
  });

  it('an online order is paid in the Razorpay popup', async () => {
    const opened = fakeRazorpay('paid');
    const { backend, user } = openStore('/checkout', { as: CUSTOMER, cart: { 9: 1 }, before: (b) => (b.online = true) });
    await screen.findByRole('heading', { name: 'Delivery address' });

    await fillAddress(user);
    await user.click(await screen.findByRole('radio', { name: /Card \/ UPI/ }));
    await user.click(screen.getByRole('button', { name: 'Pay ₹699' }));

    expect(await screen.findByText(/Paid online/)).toBeTruthy();
    expect(opened).toHaveBeenCalledOnce();
    expect(backend.orders[0].paymentStatus).toBe('PAID');
  });

  it('closing the popup leaves the order waiting for payment', async () => {
    fakeRazorpay('closed');
    const { user } = openStore('/checkout', { as: CUSTOMER, cart: { 9: 1 }, before: (b) => (b.online = true) });
    await screen.findByRole('heading', { name: 'Delivery address' });

    await fillAddress(user);
    await user.click(await screen.findByRole('radio', { name: /Card \/ UPI/ }));
    await user.click(screen.getByRole('button', { name: 'Pay ₹699' }));

    expect(await screen.findByRole('heading', { name: 'Your orders' })).toBeTruthy();
    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('Payment window closed'));
  });

  it('the continue shopping button goes back to the products', async () => {
    const { user } = openStore('/checkout', { as: CUSTOMER, cart: { 9: 1 } });

    await user.click(await screen.findByRole('button', { name: '← Continue shopping' }));
    expect(await screen.findByRole('heading', { name: 'Products' })).toBeTruthy();
  });

  it('buy now on a book page goes straight to checkout', async () => {
    const { user } = openStore('/book/9', { as: CUSTOMER });

    await user.click(await screen.findByRole('button', { name: 'Buy now' }));
    expect(await screen.findByRole('heading', { name: 'Order summary' })).toBeTruthy();
  });
});
