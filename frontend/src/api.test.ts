import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, getToken, setToken, whenLoggedOut } from './api';

const respond = (status: number, body?: unknown) =>
  vi.stubGlobal('fetch', vi.fn(async () => new Response(body === undefined ? null : JSON.stringify(body), { status })));

describe('api', () => {
  afterEach(() => setToken(null));

  it('sends the token and the JSON body, and reads the answer', async () => {
    respond(200, { message: 'ok' });
    setToken('jwt');

    expect(await api.forgotPassword('a@b.com')).toEqual({ message: 'ok' });
    const [path, init] = vi.mocked(fetch).mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/auth/forgot-password');
    expect(init.headers).toMatchObject({ Authorization: 'Bearer jwt', 'Content-Type': 'application/json' });
    expect(init.body).toBe('{"email":"a@b.com"}');
  });

  it('a 204 has no body', async () => {
    respond(204);
    expect(await api.removeFromWishlist(9)).toBeUndefined();
  });

  it('an error keeps the code and message from the service, or gets a general one', async () => {
    respond(409, { code: 'OUT_OF_STOCK', message: 'Only 1 left' });
    await expect(api.placeOrder({}, 'key')).rejects.toMatchObject({ status: 409, code: 'OUT_OF_STOCK', message: 'Only 1 left' });

    vi.stubGlobal('fetch', vi.fn(async () => new Response('not json', { status: 500 })));
    await expect(api.orders()).rejects.toMatchObject({ code: 'ERROR', message: 'Request failed (500)' });
  });

  it('a 401 with a token logs out; without a token it does not', async () => {
    const loggedOut = vi.fn();
    whenLoggedOut(loggedOut);

    respond(401, { code: 'UNAUTHORIZED', message: 'Please log in again' });
    await expect(api.me()).rejects.toBeInstanceOf(ApiError);
    expect(loggedOut).not.toHaveBeenCalled();

    setToken('expired');
    await expect(api.me()).rejects.toBeInstanceOf(ApiError);
    expect(loggedOut).toHaveBeenCalledOnce();
  });

  it('every admin call goes to its own path', async () => {
    respond(200, {});
    await api.adminOrders('PACKED');
    await api.adminOrders();
    await api.deleteAddress(3);
    await api.book(9);
    expect(vi.mocked(fetch).mock.calls.map((c) => c[0])).toEqual([
      '/api/admin/orders?status=PACKED', '/api/admin/orders', '/api/users/me/addresses/3', '/api/books/9',
    ]);
  });

  it('a browser without storage still works for the session', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('private mode');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('private mode');
    });

    expect(getToken()).toBeNull();
    expect(() => setToken('jwt')).not.toThrow();
  });
});
