import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ADMIN, order } from '../../test/backend';
import { openStore } from '../../test/render';

const withOrders = { as: ADMIN, before: (b: { orders: ReturnType<typeof order>[] }) => {
  b.orders = [
    order({ id: 'o-1', number: 'PGM-100001', status: 'PLACED' }),
    order({ id: 'o-2', number: 'PGM-100002', status: 'OUT_FOR_DELIVERY', paymentMethod: 'RAZORPAY', paymentStatus: 'PAID' }),
  ];
} };

describe('admin console', () => {
  it('the admin sees the dashboard instead of the store, on any address', async () => {
    openStore('/products', withOrders);

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeTruthy();
    expect(screen.getByText('Admin console')).toBeTruthy();
    expect(await screen.findByText('PGM-100001')).toBeTruthy();
    expect(screen.getByText('To pack').previousSibling?.textContent).toBe('1');
  });

  it('orders move to the next step, and a cancel asks first', async () => {
    const { backend, user } = openStore('/admin', withOrders);
    await screen.findByText('PGM-100001');

    await user.click(screen.getByRole('button', { name: 'Mark packed' }));
    await waitFor(() => expect(backend.orders[0].status).toBe('PACKED'));
    expect(screen.getByRole('status').textContent).toBe('PGM-100001: Packed. The customer gets an email.');

    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true);
    await user.click(await screen.findByRole('button', { name: 'Cancel' }));
    expect(backend.orders[0].status).toBe('PACKED');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(backend.orders[0].status).toBe('CANCELLED'));
    expect(confirm).toHaveBeenCalledTimes(2);

    await user.click(screen.getByRole('button', { name: 'Mark delivered' }));
    await waitFor(() => expect(backend.orders[1].status).toBe('DELIVERED'));
  });

  it('orders are filtered by status, and a failed change is reported', async () => {
    const { backend, user } = openStore('/admin', withOrders);
    await screen.findByText('PGM-100001');

    await user.click(screen.getByRole('button', { name: /^Delivered/ }));
    expect(await screen.findByText('No orders here yet.')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: /^Placed/ }));
    expect(await screen.findByText('PGM-100001')).toBeTruthy();

    backend.failures['PATCH /api/admin/orders/o-1/status'] = { status: 409, code: 'BAD_STATUS', message: 'Already packed' };
    await user.click(screen.getByRole('button', { name: 'Mark packed' }));
    await waitFor(() => expect(screen.getByRole('status').textContent).toBe('Already packed'));
  });

  it('a book is added with a cover preview, after the form is checked', async () => {
    const { backend, user } = openStore('/admin', { as: ADMIN });
    await user.click(await screen.findByRole('button', { name: 'Books and stock' }));
    await user.click(screen.getByRole('button', { name: '+ Add book' }));

    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(screen.getByRole('status').textContent).toBe('Fill name, MRP, price and stock');

    await user.type(screen.getByPlaceholderText('Book name'), 'Vault Secrets');
    await user.type(screen.getByPlaceholderText('MRP ₹'), '499');
    await user.type(screen.getByPlaceholderText('Selling price ₹'), '599');
    await user.type(screen.getByPlaceholderText('Stock'), '12');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(screen.getByRole('status').textContent).toBe('Selling price cannot be above the MRP');

    await user.clear(screen.getByPlaceholderText('Selling price ₹'));
    await user.type(screen.getByPlaceholderText('Selling price ₹'), '399');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Tool logo' }), 'docker');
    await user.type(screen.getByPlaceholderText(/One point per line/), 'Dynamic secrets{enter} {enter}Leases');
    await user.type(screen.getByPlaceholderText(/Image URL/), 'https://example.com/vault.png');
    expect(screen.getAllByText('Vault Secrets').length).toBeGreaterThan(0);
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(backend.books.at(-1)?.title).toBe('Vault Secrets'));
    expect(backend.books.at(-1)).toMatchObject({ mrp: 49900, price: 39900, stock: 12, logo: 'docker', highlights: ['Dynamic secrets', 'Leases'] });
  });

  it('a book is edited, its stock changed, and it is hidden and shown again', async () => {
    const { backend, user } = openStore('/admin', { as: ADMIN });
    await user.click(await screen.findByRole('button', { name: 'Books and stock' }));
    const row = (await screen.findByText('Docker in Production')).closest('tr') as HTMLElement;

    await user.click(within(row).getByRole('button', { name: 'Edit' }));
    const title = screen.getByPlaceholderText('Book name');
    await user.clear(title);
    await user.type(title, 'Docker in Production, 2nd edition');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(backend.books.find((b) => b.id === 8)?.title).toBe('Docker in Production, 2nd edition'));

    const edited = (await screen.findByText('Docker in Production, 2nd edition')).closest('tr') as HTMLElement;
    await user.type(within(edited).getByRole('spinbutton'), '25');
    await user.click(within(edited).getByRole('button', { name: 'Update' }));
    await waitFor(() => expect(backend.books.find((b) => b.id === 8)?.stock).toBe(25));

    await user.click(within(edited).getByRole('button', { name: 'Hide' }));
    await waitFor(() => expect(backend.books.find((b) => b.id === 8)?.active).toBe(false));
    await user.click(await within(edited).findByRole('button', { name: 'Show' }));
    await waitFor(() => expect(backend.books.find((b) => b.id === 8)?.active).toBe(true));

    await user.click(within(edited).getByRole('button', { name: 'Edit' }));
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByPlaceholderText('Book name')).toBeNull();
  });

  it('a failed load and a failed stock change are reported, and the admin logs out', async () => {
    const { backend, user } = openStore('/admin', {
      as: ADMIN,
      before: (b) => (b.failures['GET /api/admin/books'] = { status: 503, code: 'DOWN', message: 'Catalog is down' }),
    });
    await waitFor(() => expect(screen.getByRole('status').textContent).toBe('Catalog is down'));

    // choosing a filter loads everything again
    await user.click(screen.getByRole('button', { name: /^Placed/ }));
    await user.click(screen.getByRole('button', { name: 'Books and stock' }));
    backend.failures['PATCH /api/admin/books/9/stock'] = { status: 400, code: 'VALIDATION', message: 'stock: must be at most 100000' };
    const row = (await screen.findByText('Kubernetes Field Guide')).closest('tr') as HTMLElement;
    await user.type(within(row).getByRole('spinbutton'), '999999');
    await user.click(within(row).getByRole('button', { name: 'Update' }));
    await waitFor(() => expect(screen.getByRole('status').textContent).toBe('stock: must be at most 100000'));

    await user.click(screen.getByRole('button', { name: 'Logout' }));
    expect(await screen.findByRole('heading', { name: 'Login' })).toBeTruthy();
  });
});
