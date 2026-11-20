import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { CUSTOMER } from '../../test/backend';
import { openStore } from '../../test/render';

describe('browsing the store', () => {
  it('home shows the shelf, topics with books and the biggest discounts', async () => {
    const { user } = openStore('/');

    expect(await screen.findByRole('heading', { name: 'Biggest discounts today' })).toBeTruthy();
    expect(screen.getByRole('button', { name: /Containers and orchestration/ })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Cloud platforms/ })).toBeNull();
    expect(screen.getAllByRole('link', { name: 'Kubernetes Field Guide' }).length).toBeGreaterThan(0);

    await user.click(screen.getByRole('button', { name: /CI\/CD and GitOps/ }));
    expect(await screen.findByRole('heading', { name: 'Products' })).toBeTruthy();
    expect(window.location.search).toBe('?topic=CI%2FCD');
  });

  it('products filter by topic and by search text', async () => {
    const { user } = openStore('/products');

    expect(await screen.findByRole('heading', { name: 'Containers and orchestration' })).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'IaC' }));
    expect(screen.queryByRole('heading', { name: 'Containers and orchestration' })).toBeNull();
    expect(screen.getByRole('heading', { name: 'Infrastructure as code' })).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'All' }));
    await user.type(screen.getByPlaceholderText(/Search any book/), 'zzz');
    expect(screen.getByText(/No book matches/)).toBeTruthy();
  });

  it('a book page shows the price, stock, look-inside pages and related books', async () => {
    const { user } = openStore('/book/9');

    expect(await screen.findByRole('heading', { name: 'Kubernetes Field Guide', level: 1 })).toBeTruthy();
    expect(screen.getByText('42% off', { selector: 'em' })).toBeTruthy();
    expect(screen.getByText('In stock')).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'More in Containers and orchestration' })).toBeTruthy();

    expect(screen.getByText(/^Page 1 of/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Next page' }));
    expect(screen.getByText(/^Page 2 of/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Previous page' }));
    await user.click(screen.getByRole('button', { name: '3' }));
    expect(screen.getByText(/^Page 3 of/)).toBeTruthy();

    const zoom = document.querySelector('.zm') as HTMLElement;
    await user.pointer({ target: zoom, coords: { clientX: 10, clientY: 10 } });
  });

  it('a book added by the admin has simple look-inside pages from its own fields', async () => {
    openStore('/book/500');

    expect(await screen.findByRole('heading', { name: 'Terraform Recipes', level: 1 })).toBeTruthy();
    expect(screen.getAllByText('Reusable modules').length).toBeGreaterThan(0);
    expect(screen.queryByText(/% off/)).toBeNull();
  });

  it('an unknown or hidden book is not available', async () => {
    openStore('/book/501');

    expect(await screen.findByText(/This book is not available/)).toBeTruthy();
  });

  it('a book card opens with the keyboard as well as the mouse', async () => {
    const { user } = openStore('/products');

    const cover = await screen.findByRole('link', { name: 'Open Docker in Production' });
    cover.focus();
    await user.keyboard('{Enter}');
    expect(await screen.findByRole('heading', { name: 'Docker in Production', level: 1 })).toBeTruthy();
  });

  it('an unknown page says so', async () => {
    openStore('/nothing-here');

    expect(await screen.findByText('Page not found.')).toBeTruthy();
  });

  it('the search palette opens with Ctrl+K and finds books', async () => {
    const { user } = openStore('/');
    await screen.findByRole('heading', { name: 'Biggest discounts today' });

    await user.keyboard('{Control>}k{/Control}');
    const input = screen.getByPlaceholderText('Search books or tools...');
    await user.type(input, 'docker');
    const palette = input.closest('.pal') as HTMLElement;
    await user.click(within(palette).getByRole('button', { name: /Docker in Production/ }));
    expect(await screen.findByRole('heading', { name: 'Docker in Production', level: 1 })).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Search' }));
    await user.type(screen.getByPlaceholderText('Search books or tools...'), 'nothing like this');
    expect(screen.getByText(/No match/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Close search' }));
  });

  it('when the catalog cannot be reached the shopper can try again', async () => {
    const { user } = openStore('/', {
      before: (b) => (b.failures['GET /api/books'] = { status: 503, code: 'DOWN', message: 'Catalog is down' }),
    });

    expect((await screen.findByRole('alert')).textContent).toContain('Catalog is down');
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
    expect(await screen.findByRole('heading', { name: 'Biggest discounts today' })).toBeTruthy();
  });
});

describe('cart', () => {
  it('books are added, counted, changed and removed in the cart drawer', async () => {
    const { user } = openStore('/book/8');
    await screen.findByRole('heading', { name: 'Docker in Production', level: 1 });

    await user.click(screen.getAllByRole('button', { name: 'Add to cart' })[0]);
    expect(screen.getByRole('status').textContent).toBe('Added Docker in Production to cart');
    await user.click(screen.getAllByRole('button', { name: 'Add one' })[0]);
    await user.click(screen.getAllByRole('button', { name: 'Add one' })[0]);
    await user.click(screen.getAllByRole('button', { name: 'Add one' })[0]);
    expect(screen.getByRole('status').textContent).toBe('Only 3 in stock');

    await user.click(screen.getByRole('button', { name: /^Cart/ }));
    const drawer = document.querySelector('aside.drawer') as HTMLElement;
    expect(within(drawer).getByText('Your cart')).toBeTruthy();
    expect(within(drawer).getByText('Docker in Production')).toBeTruthy();
    await user.click(within(drawer).getByRole('button', { name: '−' }));
    await user.click(within(drawer).getByRole('button', { name: '+' }));
    await user.click(within(drawer).getByRole('button', { name: 'Delete' }));
    expect(within(drawer).getByText(/Your cart is empty/)).toBeTruthy();
    await user.click(within(drawer).getByRole('button', { name: 'Close' }));
  });

  it('removing the last copy from a card empties the cart and an out-of-stock book cannot be added', async () => {
    const { user } = openStore('/products', { cart: { 9: 1 } });
    await screen.findByRole('heading', { name: 'Containers and orchestration' });

    await user.click(screen.getByRole('button', { name: 'Remove one' }));
    expect(screen.getByRole('button', { name: /^Cart/ }).textContent).toContain('0');
    expect(screen.getAllByRole('button', { name: 'Out of stock' })[0]).toHaveProperty('disabled', true);
  });

  it('the cart drops books that are hidden or out of stock, and checkout from the drawer needs a login', async () => {
    const { user } = openStore('/', { cart: { 9: 2, 4: 1, 501: 1 } });

    await waitFor(() => expect(screen.getByRole('button', { name: /^Cart/ }).textContent).toContain('2'));
    await user.click(screen.getByRole('button', { name: /^Cart/ }));
    await user.click(screen.getByRole('button', { name: 'Proceed to checkout' }));
    expect(await screen.findByRole('heading', { name: 'Login' })).toBeTruthy();
    expect(window.location.search).toBe('?next=%2Fcheckout');
  });

  it('a logged-in shopper can move a cart book to the wishlist', async () => {
    const { backend, user } = openStore('/', { as: CUSTOMER, cart: { 9: 1 } });
    await screen.findByRole('heading', { name: 'Biggest discounts today' });

    await user.click(screen.getByRole('button', { name: /^Cart/ }));
    await user.click(screen.getByRole('button', { name: 'Save for later' }));
    await waitFor(() => expect(backend.wishlist).toEqual([9]));
    expect(screen.getByRole('status').textContent).toBe('Saved Kubernetes Field Guide for later');
  });
});
