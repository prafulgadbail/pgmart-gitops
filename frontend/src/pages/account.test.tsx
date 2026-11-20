import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ADMIN, CUSTOMER } from '../../test/backend';
import { openStore } from '../../test/render';

const submit = () => document.querySelector('form.auth button[type=submit]') as HTMLButtonElement;

describe('login and sign-up', () => {
  it('a customer logs in and goes back to the page that asked for it', async () => {
    const { user } = openStore('/login?next=/wishlist');

    await user.type(await screen.findByLabelText('Email'), 'asha@example.com');
    await user.type(screen.getByLabelText('Password'), 'secret-pass');
    await user.click(submit());

    expect(await screen.findByRole('heading', { name: 'Wishlist' })).toBeTruthy();
    expect(screen.getByRole('status').textContent).toBe('Welcome, Asha Kumar');
  });

  it('a new account is created with the emailed code', async () => {
    const { backend, user } = openStore('/login');

    await user.click(await screen.findByRole('button', { name: 'Register' }));
    await user.type(screen.getByLabelText('Name'), 'Asha Kumar');
    await user.type(screen.getByLabelText('Email'), 'asha@example.com');
    await user.type(screen.getByLabelText('Password'), 'short');
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    expect(screen.getByText('Use at least 8 characters for the password')).toBeTruthy();

    await user.type(screen.getByLabelText('Password'), '-and-longer');
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByText('We sent a 6-digit code to asha@example.com')).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Send a new code' }));
    expect(await screen.findByText('A new code is on its way')).toBeTruthy();

    await user.type(screen.getByLabelText(/6-digit code/), '12a3456');
    await user.click(screen.getByRole('button', { name: 'Verify and continue' }));
    expect(await screen.findByRole('heading', { name: 'Biggest discounts today' })).toBeTruthy();
    expect(backend.requests.find((r) => r.path === '/api/auth/verify')?.body).toEqual({ email: 'asha@example.com', code: '123456' });
  });

  it('an unverified account is asked for its code, and a wrong password shows the error', async () => {
    const { backend, user } = openStore('/login');
    backend.failures['POST /api/auth/login'] = { status: 403, code: 'EMAIL_NOT_VERIFIED', message: 'Please verify your email first.' };

    await user.type(await screen.findByLabelText('Email'), 'asha@example.com');
    await user.type(screen.getByLabelText('Password'), 'secret-pass');
    await user.click(submit());
    expect(await screen.findByRole('heading', { name: 'Verify your email' })).toBeTruthy();
    expect(screen.getByText('Please verify your email first.')).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Back to login' }));
    backend.failures['POST /api/auth/login'] = { status: 401, code: 'BAD_CREDENTIALS', message: 'Wrong email or password' };
    await user.type(screen.getByLabelText('Password'), 'wrong-pass');
    await user.click(submit());
    expect(await screen.findByText('Wrong email or password')).toBeTruthy();
  });

  it('a forgotten password is reset with the emailed code', async () => {
    const { user } = openStore('/login');

    await user.click(await screen.findByRole('button', { name: 'Forgot password?' }));
    await user.type(screen.getByLabelText('Email'), 'asha@example.com');
    await user.click(screen.getByRole('button', { name: 'Send reset code' }));
    expect(await screen.findByText(/A reset code is on its way/)).toBeTruthy();

    await user.type(screen.getByLabelText(/6-digit code/), '654321');
    await user.type(screen.getByLabelText('New password'), 'short');
    await user.click(screen.getByRole('button', { name: 'Save password and login' }));
    expect(screen.getByText('Use at least 8 characters for the new password')).toBeTruthy();

    await user.type(screen.getByLabelText('New password'), '-and-longer');
    await user.click(screen.getByRole('button', { name: 'Save password and login' }));
    expect(await screen.findByRole('heading', { name: 'Biggest discounts today' })).toBeTruthy();
  });

  it('a network failure and the back button', async () => {
    const { user } = openStore('/login');
    await screen.findByLabelText('Email');
    vi.mocked(fetch).mockRejectedValueOnce(new TypeError('offline'));

    await user.type(screen.getByLabelText('Email'), 'asha@example.com');
    await user.type(screen.getByLabelText('Password'), 'secret-pass');
    await user.click(submit());
    expect(await screen.findByText(/Cannot reach the store/)).toBeTruthy();

    await user.click(screen.getByRole('button', { name: '← Back' }));
  });

  it('the admin logs in to the console', async () => {
    const { user } = openStore('/login');

    await user.type(await screen.findByLabelText('Email'), ADMIN.email);
    await user.type(screen.getByLabelText('Password'), 'admin-pass');
    await user.click(submit());

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeTruthy();
  });
});

describe('wishlist and session', () => {
  it('a guest is sent to login to save a book, and to see the wishlist', async () => {
    const { user } = openStore('/book/9');

    await user.click(await screen.findByRole('button', { name: '♡ Wishlist' }));
    expect(await screen.findByRole('heading', { name: 'Login' })).toBeTruthy();
    expect(screen.getByRole('status').textContent).toBe('Log in to save books to your wishlist');

    await user.click(screen.getByRole('link', { name: 'Products' }));
    await user.click(screen.getByRole('link', { name: 'Wishlist' }));
    expect(screen.getByText('Log in to see your wishlist.')).toBeTruthy();
  });

  it('a customer saves and removes books, and sees them in the wishlist', async () => {
    const { backend, user } = openStore('/products', { as: CUSTOMER });

    await user.click(await screen.findByRole('button', { name: 'Save Kubernetes Field Guide to wishlist' }));
    await waitFor(() => expect(backend.wishlist).toEqual([9]));
    expect(screen.getByRole('status').textContent).toBe('Saved to wishlist');

    await user.click(screen.getByRole('link', { name: 'Wishlist' }));
    expect(await screen.findByRole('link', { name: 'Kubernetes Field Guide' })).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Remove Kubernetes Field Guide from wishlist' }));
    expect(await screen.findByText(/Your wishlist is empty/)).toBeTruthy();
  });

  it('a failed wishlist change is undone and explained', async () => {
    const { backend, user } = openStore('/book/9', { as: CUSTOMER });
    await screen.findByRole('heading', { name: 'Kubernetes Field Guide', level: 1 });
    backend.failures['PUT /api/wishlist/9'] = { status: 404, code: 'NOT_FOUND', message: 'This book is not available' };

    await user.click(screen.getByRole('button', { name: '♡ Wishlist' }));
    expect(await screen.findByText('This book is not available', { selector: '[role=status]' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '♡ Wishlist' })).toBeTruthy();
  });

  it('notifications show in the bell, open the orders page, and can be cleared', async () => {
    const { backend, user } = openStore('/', {
      as: CUSTOMER,
      before: (b) => {
        b.notifications = [
          { id: 2, orderId: 'order-1', title: 'Order PGM-100001 is packed', body: 'On its way soon', read: false, createdAt: new Date().toISOString() },
          { id: 1, orderId: null, title: 'Welcome', body: 'Thanks for joining', read: true, createdAt: '2026-01-01T00:00:00Z' },
        ];
      },
    });

    const bell = await screen.findByRole('button', { name: 'Notifications' });
    await waitFor(() => expect(bell.textContent).toContain('1'));
    await user.click(bell);
    const drawer = document.querySelector('aside.drawer') as HTMLElement;
    expect(within(drawer).getByText('On its way soon')).toBeTruthy();
    await user.click(within(drawer).getByRole('button', { name: /is packed/ }));
    expect(await screen.findByRole('heading', { name: 'Your orders' })).toBeTruthy();
    expect(backend.requests.some((r) => r.path === '/api/notifications/read')).toBe(true);

    await user.click(bell);
    await user.click(within(drawer).getByRole('button', { name: 'Clear all' }));
    expect(within(drawer).getByText(/No notifications yet/)).toBeTruthy();
    await user.click(within(drawer).getByRole('button', { name: 'Close' }));
  });

  it('logout from a page that needs login goes to the home page', async () => {
    const { user } = openStore('/orders', { as: CUSTOMER });
    expect(await screen.findByRole('heading', { name: 'Your orders' })).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Logout' }));
    expect(await screen.findByRole('heading', { name: 'Biggest discounts today' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Login' })).toBeTruthy();
    expect(screen.getByRole('status').textContent).toBe('Logged out');
  });

  it('a 401 from a service logs the shopper out', async () => {
    openStore('/orders', {
      as: CUSTOMER,
      before: (b) => (b.failures['GET /api/orders'] = { status: 401, code: 'UNAUTHORIZED', message: 'Please log in again' }),
    });

    expect(await screen.findByRole('heading', { name: 'Login' })).toBeTruthy();
    expect(localStorage.getItem('pgmart.token')).toBeNull();
  });
});
